// src/main/java/tterrag1112/life_in_the_village/Village/VillageSpawner.java
package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Planning.*;

import java.util.*;

/**
 * Spawns a fully-realised village at a given origin position.
 *
 * <h3>Spawn pipeline</h3>
 * <ol>
 *   <li><b>Guard checks</b> — distance from existing villages,
 *       unknown type, etc.</li>
 *   <li><b>VillagePlanner</b> — terrain analysis, ring-based layout
 *       planning, farm/path/decoration slot generation. Returns a
 *       {@link VillageLayout}; aborts if terrain is unsuitable.</li>
 *   <li><b>Building placement</b> — iterates {@code layout.buildings()}
 *       and calls {@link BuildingPlacer#placeAndRegister}.</li>
 *   <li><b>Farm plot placement</b> — delegates to
 *       {@link FarmPlotPlacer}.</li>
 *   <li><b>Item stocking</b> — fills starter items from
 *       {@link VillageTypeData}.</li>
 *   <li><b>NPC spawning</b> — creates {@link TownspersonMob} entities
 *       inside their assigned buildings.</li>
 *   <li><b>Decoration</b> — {@link VillageDecorator} handles paths,
 *       fences, market stalls, etc., now guided by layout decoration
 *       anchors.</li>
 *   <li><b>Trade routes</b> — {@link TradeRouteManager} establishes
 *       inter-village roads.</li>
 * </ol>
 */
public class VillageSpawner {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Minimum block distance between any two village centres. */
    private static final int MIN_VILLAGE_DISTANCE = 128;
    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Attempts to spawn a village of the given type at the supplied
     * origin. The origin is a coarse hint (e.g. chunk-centre); the
     * planner will snap it to the nearest suitable surface.
     *
     * @return the created {@link Village}, or empty if spawning failed.
     */
    public static Optional<Village> spawnVillage(ServerLevel level,
                                                 BlockPos origin,
                                                 String villageType,
                                                 String villageName) {
        // ── Guard: known type ────────────────────────────────────────────────
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE
                .getType(villageType);
        if (typeData == null) {
            System.out.println("VillageSpawner: unknown village type '"
                    + villageType + "' — aborting");
            return Optional.empty();
        }

        // ── Guard: distance from existing villages ───────────────────────────
        VillageSavedData data = VillageSavedData.get(level);
        if (!isFarEnoughFromExistingVillages(level, origin)) return Optional.empty();

        Random rng = new Random((long) origin.hashCode() * 31
                + villageName.hashCode());

        // Derive level from type data
        int villageLevel = deriveVillageLevel(typeData);

        // ── Phase -2: Pre-clear and smooth the site FIRST ────────────────────
        // We do a preliminary terrain analysis just to find the centre,
        // then prep, then do the full planning pass on the clean terrain.
        BlockPos roughSurface = findSurface(level, origin);
        VillageSitePreparer.prepare(level,
                roughSurface, villageLevel);   // ← new signature, see below

        // ── Phase -1: Plan layout on the NOW-CLEAN terrain ───────────────────
        Optional<VillageLayout> layoutOpt = VillagePlanner.plan(
                level, roughSurface, typeData, rng, villageLevel);

        if (layoutOpt.isEmpty()) {
            System.out.println("VillageSpawner: planner rejected terrain — aborting '"
                    + villageName + "'");
            return Optional.empty();
        }

        VillageLayout layout = layoutOpt.get();
        System.out.println("VillageSpawner: planned '" + villageName + "' — " + layout);

        // ── Register village ──────────────────────────────────────────────────
        Village village = new Village(villageName);
        data.addVillage(village);

        // ── Phase 1: Place buildings ─────────────────────────────────────────
        // The layout gives us one LayoutSlot per building, already positioned
        // on terrain-aware rings. We iterate them in order so that buildings
        // appearing multiple times in the type definition (e.g. several HOUSEs)
        // each get their own slot if the planner emitted enough.
        Map<BuildingType, Building> placedBuildings = new LinkedHashMap<>();

        for (LayoutSlot slot : layout.buildings()) {
            BuildingType buildingType = slot.getBuildingType();
            if (buildingType == null) continue;

            // Re-query the actual surface at this XZ at placement time.
            // VillageSitePreparer may have slightly shifted the terrain
            // between planning and placement (rare but possible).
            BlockPos plannedPos = slot.getPos();
            int actualSurfY = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                    plannedPos.getX(), plannedPos.getZ());
            BlockPos buildPos = new BlockPos(
                    plannedPos.getX(),
                    actualSurfY,      // solid surface block Y
                    plannedPos.getZ());

            // Resolve the structure identifier.
            // If the planner slot has a path, use it; otherwise fall back
            // to the first matching starter building definition.
            String structurePath = slot.getStructurePath();
            if (structurePath == null || structurePath.isBlank()) {
                structurePath = findStructurePath(typeData, buildingType);
            }
            if (structurePath == null) {
                System.out.println("VillageSpawner: no structure path for "
                        + buildingType + " — skipping");
                continue;
            }

            Identifier structId = Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, structurePath);

            // Unique name so multiple houses etc. don't collide
            String buildingName = villageName + "_"
                    + buildingType.name().toLowerCase() + "_"
                    + (countType(placedBuildings, buildingType) + 1);

            Rotation rotation = randomRotation(level.getRandom());

            try {
                Optional<Building> placed = BuildingPlacer.placeAndRegister(
                        level, buildPos, structId,
                        buildingName, buildingType, rotation);

                if (placed.isEmpty()) {
                    System.out.println("VillageSpawner: placeAndRegister "
                            + "returned empty for " + buildingType
                            + " at " + buildPos);
                    continue;
                }

                village.addBuilding(placed.get());
                // Only store the first instance per type for NPC assignment;
                // additional instances (e.g. extra houses) remain registered
                // but we don't overwrite the primary entry.
                placedBuildings.putIfAbsent(buildingType, placed.get());

                data.setDirty();
                System.out.println("VillageSpawner: placed "
                        + buildingType + " at " + buildPos);

            } catch (Exception e) {
                System.out.println("VillageSpawner: exception placing "
                        + buildingType + " — " + e.getMessage());
            }
        }

        // ── Phase 2: Place farm plots ─────────────────────────────────────────
        FarmPlotPlacer.placeAll(level, layout, village, data, rng);

        // ── Phase 3: Stock starter items ──────────────────────────────────────
        for (VillageTypeData.StarterItem si : typeData.getStarterItems()) {
            try {
                BuildingType targetType =
                        BuildingType.valueOf(si.buildingType());
                Building targetBuilding = placedBuildings.get(targetType);
                if (targetBuilding == null) continue;

                Identifier itemId = Identifier.parse(si.item());
                var itemHolder = BuiltInRegistries.ITEM.get(itemId);
                if (itemHolder.isEmpty()) continue;

                ItemStack stack = new ItemStack(
                        itemHolder.get().value(), si.count());
                BuildingStorageAccess.storeItem(
                        level, targetBuilding, stack);

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to stock '"
                        + si.item() + "' — " + e.getMessage());
            }
        }

        // ── Phase 4: Spawn NPCs ───────────────────────────────────────────────
        for (VillageTypeData.StarterNpc sn : typeData.getStarterNpcs()) {
            try {
                BuildingType buildingType =
                        BuildingType.valueOf(sn.buildingType());
                Building assignedBuilding =
                        placedBuildings.get(buildingType);
                if (assignedBuilding == null) continue;

                TownspersonMob npc = ModEntities.TOWNSPERSON
                        .get().create(level, EntitySpawnReason.NATURAL);
                if (npc == null) continue;

                BlockPos spawnPos = randomPosInsideBuilding(
                        level, assignedBuilding, rng);

                npc.setPos(spawnPos.getX() + 0.5,
                        spawnPos.getY(),
                        spawnPos.getZ() + 0.5);
                npc.finalizeSpawn(level,
                        level.getCurrentDifficultyAt(spawnPos),
                        EntitySpawnReason.NATURAL, null);

                Profession profession =
                        Profession.valueOf(sn.profession());
                npc.setProfession(profession);
                npc.setAssignedBuildingId(assignedBuilding.getId());
                npc.setAssignedVillageName(villageName);
                npc.setFamilyRole(FamilyRole.valueOf(sn.familyRole()));

                boolean isResidence =
                        buildingType == BuildingType.HOUSE
                                || buildingType == BuildingType.FARMHOUSE;
                npc.setHouseId(isResidence
                        ? assignedBuilding.getId() : null);

                if (profession == Profession.MERCHANT) {
                    npc.receive(CurrencyValue.ofGold(2));
                } else {
                    npc.receive(CurrencyValue.ofSilver(5));
                }

                level.addFreshEntity(npc);
                System.out.println("VillageSpawner: spawned "
                        + sn.profession() + " '"
                        + npc.getNpcName() + "' in "
                        + sn.buildingType());

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to spawn NPC — "
                        + e.getMessage());
            }
        }

        // ── Phase 5: Decorate ─────────────────────────────────────────────────
        // VillageDecorator handles paths (using PathRouter), fences, wells,
        // market stalls, and landmarks. It uses layout decoration anchors
        // internally via the village bounds.
        VillageDecorator.decorateVillage(level, village, data, layout);

        // ── Phase 6: Establish trade routes ───────────────────────────────────
        TradeRouteManager.establishRoutes(level, village, data);

        System.out.println("VillageSpawner: '" + villageName
                + "' spawned successfully with "
                + village.getBuildingIds().size() + " buildings, "
                + layout.farmPlots().size() + " farm plot(s), "
                + "density=" + layout.getDensity().label());

        return Optional.of(village);
    }

    // -------------------------------------------------------------------------
    // Public utilities (used by NaturalVillageSpawnEvent and commands)
    // -------------------------------------------------------------------------

    /**
     * Returns true if {@code pos} is far enough from every known village
     * centre to allow a new village to spawn there.
     */
    public static boolean isFarEnoughFromExistingVillages(
            ServerLevel level, BlockPos pos) {
        VillageSavedData data = VillageSavedData.get(level);
        for (Village village : data.getAllVillages()) {
            Optional<net.minecraft.world.phys.AABB> bounds =
                    village.getBounds(data);
            if (bounds.isPresent()) {
                double cx = (bounds.get().minX + bounds.get().maxX) / 2;
                double cz = (bounds.get().minZ + bounds.get().maxZ) / 2;
                double dist = Math.sqrt(
                        Math.pow(pos.getX() - cx, 2)
                                + Math.pow(pos.getZ() - cz, 2));
                if (dist < MIN_VILLAGE_DISTANCE) return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Derives a 1–10 village level from the number of starter buildings
     * declared in the type data. This is the initial complexity hint fed
     * to the planner's density profile.
     *
     * <ul>
     *   <li>1–3 buildings  → level 1–2  (sparse hamlet)</li>
     *   <li>4–6 buildings  → level 3–5  (moderate village)</li>
     *   <li>7–9 buildings  → level 6–8  (dense town)</li>
     *   <li>10+ buildings  → level 9–10 (packed city)</li>
     * </ul>
     */
    private static int deriveVillageLevel(VillageTypeData typeData) {
        int count = typeData.getStarterBuildings().size();
        if (count <= 3)  return Math.max(1, count);          // 1–3
        if (count <= 6)  return 3 + (count - 4);             // 3–5
        if (count <= 9)  return 6 + (count - 7);             // 6–8
        return Math.min(10, 9 + (count - 10));               // 9–10
    }

    /**
     * Finds the structure path for the first starter building of the
     * given type in the type data definition.
     * Returns null if no match is found.
     */
    private static String findStructurePath(VillageTypeData typeData,
                                            BuildingType targetType) {
        return typeData.getStarterBuildings().stream()
                .filter(sb -> {
                    try {
                        return BuildingType.valueOf(sb.type()) == targetType;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .map(VillageTypeData.StarterBuilding::structure)
                .findFirst()
                .orElse(null);
    }

    /**
     * Counts how many buildings of a given type have been placed so far.
     * Used to generate unique building names.
     */
    private static long countType(Map<BuildingType, Building> placed,
                                  BuildingType type) {
        return placed.keySet().stream()
                .filter(t -> t == type)
                .count();
    }

    /**
     * Returns a random walkable position inside the building footprint.
     * Falls back to the building centre if no clear spot is found.
     */
    private static BlockPos randomPosInsideBuilding(
            ServerLevel level, Building building, Random rng) {
        BlockPos min = building.getShape().getMin();
        int w = building.getShape().getWidth();
        int l = building.getShape().getLength();

        for (int attempt = 0; attempt < 10; attempt++) {
            int x = min.getX() + 1 + rng.nextInt(Math.max(1, w - 2));
            int z = min.getZ() + 1 + rng.nextInt(Math.max(1, l - 2));
            int floorY = building.getShape().getOrigin().getY();
            BlockPos candidate = new BlockPos(x, floorY + 1, z);

            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()) {
                return candidate;
            }
        }

        // Fallback — geometric centre at floor level
        return building.getShape().getOrigin().offset(w / 2, 1, l / 2);
    }

    /**
     * Picks a uniformly random {@link Rotation}.
     */
    private static Rotation randomRotation(
            net.minecraft.util.RandomSource random) {
        Rotation[] rotations = Rotation.values();
        return rotations[random.nextInt(rotations.length)];
    }

    /**
     * Returns the surface block position (first air block above solid
     * ground) at the given XZ, skipping over water.
     *
     * <p>This is kept here as a utility for any code in this class that
     * still needs a quick surface lookup; the planner uses
     * {@link tterrag1112.life_in_the_village.Village.Planning.TerrainAnalyzer}
     * for bulk analysis.
     */
    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(
                Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        BlockPos surface = new BlockPos(pos.getX(), surfaceY, pos.getZ());

        // If the surface is water, descend until we hit solid ground
        BlockState state = level.getBlockState(surface.below());
        if (state.liquid()) {
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState s = level.getBlockState(check);
                if (s.isSolidRender() && !s.liquid()) {
                    return check.above();
                }
            }
        }

        return surface;
    }
}