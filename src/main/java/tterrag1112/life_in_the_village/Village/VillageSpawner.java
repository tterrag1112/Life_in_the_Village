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
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Guard checks (known type, distance from existing villages)</li>
 *   <li>Site preparation — clear trees, fill surface caves, gentle smoothing</li>
 *   <li>Layout planning on the now-clean terrain</li>
 *   <li>Building placement — re-queries live heightmap at placement time
 *       so any residual Y drift from prep is corrected</li>
 *   <li>Farm plot placement and registration</li>
 *   <li>Item stocking</li>
 *   <li>NPC spawning</li>
 *   <li>Decoration (paths, town square, lampposts, flowers, etc.)</li>
 *   <li>Trade route establishment</li>
 * </ol>
 */
public class VillageSpawner {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int MIN_VILLAGE_DISTANCE = 128;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static Optional<Village> spawnVillage(ServerLevel level,
                                                 BlockPos origin,
                                                 String villageType,
                                                 String villageName) {

        // ── Guard: known type ─────────────────────────────────────────────────
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE
                .getType(villageType);
        if (typeData == null) {
            System.out.println("VillageSpawner: unknown village type '"
                    + villageType + "' — aborting");
            return Optional.empty();
        }

        // ── Guard: too close to another village ───────────────────────────────
        VillageSavedData data = VillageSavedData.get(level);
        if (!isFarEnoughFromExistingVillages(level, origin)) {
            System.out.println("VillageSpawner: too close to existing village");
            return Optional.empty();
        }

        // Seeded RNG for deterministic results
        Random rng = new Random((long) origin.hashCode() * 31L
                + villageName.hashCode());

        int villageLevel = deriveVillageLevel(typeData);

        // ── Phase -2: Prepare the site BEFORE planning ────────────────────────
        // Site prep clears trees, fills surface caves, and gently averages
        // terrain height. Planning runs AFTER so slot Y values reflect the
        // prepared surface — preventing floating buildings.
        BlockPos roughSurface = findSurface(level, origin);
        VillageSitePreparer.prepare(level, roughSurface, villageLevel);

        // ── Phase -1: Plan layout on the clean terrain ────────────────────────
        Optional<VillageLayout> layoutOpt = VillagePlanner.plan(
                level, roughSurface, typeData, rng, villageLevel);


        if (layoutOpt.isEmpty()) {
            System.out.println("VillageSpawner: planner rejected terrain at "
                    + origin + " — aborting '" + villageName + "'");
            return Optional.empty();
        }

        VillageLayout layout = layoutOpt.get();

        // ── Register the village ──────────────────────────────────────────────

        Village village = new Village(villageName);
        village.applyLayout(layout, villageLevel);   // ← NEW
        data.addVillage(village);

        if (layout.buildings().isEmpty()) {
            System.out.println("VillageSpawner: planner produced no buildings — aborting");
            return Optional.empty();
        }

        System.out.println("VillageSpawner: planning complete for '"
                + villageName + "' — " + layout);



        // ── Phase 1: Place buildings ──────────────────────────────────────────
        // Per-type counters for unique building names (putIfAbsent in
        // placedBuildings only stores the first, so we track counts separately)
        Map<BuildingType, Building> placedBuildings = new LinkedHashMap<>();
        Map<BuildingType, Integer>  typeCounters    = new HashMap<>();

        // Resolve the town square centre so we can orient buildings toward it
        BlockPos squareCenter = layout.getTownSquarePos() != null
                ? layout.getTownSquarePos()
                : layout.getCenter();


        for (LayoutSlot slot : layout.buildings()) {
            BuildingType buildingType = slot.getBuildingType();
            if (buildingType == null) continue;

            // Re-query the live heightmap at placement time.
            // MOTION_BLOCKING_NO_LEAVES is more reliable than WORLD_SURFACE
            // for walkable ground (ignores snow layers, leaves canopy, etc.).
            // The result is the Y of the first motion-blocking block from above,
            // which is the solid surface — the structure origin goes HERE.
            BlockPos plannedPos = slot.getPos();
            int actualSurfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    plannedPos.getX(), plannedPos.getZ());

            BlockPos buildPos = new BlockPos(
                    plannedPos.getX(),
                    actualSurfY,
                    plannedPos.getZ());

            // Resolve structure path from slot, falling back to typeData
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

            // Increment per-type counter for unique names
            int typeIndex = typeCounters.merge(buildingType, 1, Integer::sum);
            String buildingName = villageName + "_"
                    + buildingType.name().toLowerCase() + "_" + typeIndex;

            // Choose rotation that faces the building toward the town square
            Rotation rotation = chooseFacingRotation(buildPos, squareCenter);

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
                Building newBuilding = placed.get();

                boolean overlapsExisting = placedBuildings.values().stream()
                        .anyMatch(existing ->
                                shapesOverlapXZ(existing.getShape(),
                                        newBuilding.getShape(),
                                        StructureSizeCache.MIN_GAP));

                if (overlapsExisting) {
                    System.out.println("VillageSpawner: "
                            + buildingType
                            + " at " + buildPos
                            + " overlaps existing building — removing");
                    // Remove the just-placed building from saved data
                    data.removeBuilding(newBuilding);
                    continue;
                }

                village.addBuilding(newBuilding);
                placedBuildings.putIfAbsent(buildingType, newBuilding);
                data.setDirty();

                System.out.println("VillageSpawner: placed "
                        + buildingType + " at " + buildPos
                        + " facing=" + rotation.name());

            } catch (Exception e) {
                System.out.println("VillageSpawner: exception placing "
                        + buildingType + " — " + e.getMessage());
            }
        }

        // Abort if no buildings were placed at all
        if (placedBuildings.isEmpty()) {
            System.out.println("VillageSpawner: no buildings placed — aborting");
            data.removeBuilding(null); // village was added but has no buildings
            return Optional.empty();
        }

        // ── Phase 2: Place and register farm plots ────────────────────────────
        FarmPlotPlacer.placeAll(level, layout, village, data, rng);

        // ── Phase 3: Stock starter items ──────────────────────────────────────
        for (VillageTypeData.StarterItem si : typeData.getStarterItems()) {
            try {
                BuildingType targetType = BuildingType.valueOf(si.buildingType());
                Building targetBuilding = placedBuildings.get(targetType);
                if (targetBuilding == null) continue;

                Identifier itemId = Identifier.parse(si.item());
                var itemHolder = BuiltInRegistries.ITEM.get(itemId);
                if (itemHolder.isEmpty()) continue;

                ItemStack stack = new ItemStack(
                        itemHolder.get().value(), si.count());
                BuildingStorageAccess.storeItem(level, targetBuilding, stack);

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to stock '"
                        + si.item() + "' — " + e.getMessage());
            }
        }

        // ── Phase 4: Spawn NPCs ───────────────────────────────────────────────
        for (VillageTypeData.StarterNpc sn : typeData.getStarterNpcs()) {
            try {
                BuildingType buildingType = BuildingType.valueOf(sn.buildingType());
                Building assignedBuilding = placedBuildings.get(buildingType);
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

                Profession profession = Profession.valueOf(sn.profession());
                npc.setProfession(profession);
                npc.setAssignedBuildingId(assignedBuilding.getId());
                npc.setAssignedVillageName(villageName);
                npc.setFamilyRole(FamilyRole.valueOf(sn.familyRole()));

                boolean isResidence = buildingType == BuildingType.HOUSE
                        || buildingType == BuildingType.FARMHOUSE;
                npc.setHouseId(isResidence ? assignedBuilding.getId() : null);

                npc.receive(profession == Profession.MERCHANT
                        ? CurrencyValue.ofGold(2)
                        : CurrencyValue.ofSilver(5));

                level.addFreshEntity(npc);
                System.out.println("VillageSpawner: spawned "
                        + sn.profession() + " '"
                        + npc.getNpcName() + "' in " + sn.buildingType());

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to spawn NPC — "
                        + e.getMessage());
            }
        }

        // ── Phase 5: Decorate ─────────────────────────────────────────────────
        VillageDecorator.decorateVillage(level, village, data, layout);

        // ── Phase 6: Establish trade routes ───────────────────────────────────
        TradeRouteManager.establishRoutes(level, village, data);

        System.out.println("VillageSpawner: '" + villageName
                + "' spawned — buildings=" + village.getBuildingIds().size()
                + " farms=" + layout.farmPlots().size()
                + " density=" + layout.getDensity().label());

        return Optional.of(village);
    }

    // -------------------------------------------------------------------------
    // Public utilities
    // -------------------------------------------------------------------------

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
     * Chooses the {@link Rotation} that makes the building face toward the
     * town square as closely as possible.
     *
     * <p>NBT structures are placed with their origin at the north-west corner
     * and their entrance facing south (positive Z) with {@link Rotation#NONE}.
     * To point the entrance toward a target we find which cardinal direction
     * the target lies in and pick the matching rotation.
     */
    private static Rotation chooseFacingRotation(BlockPos buildPos,
                                                 BlockPos target) {
        int dx = target.getX() - buildPos.getX();
        int dz = target.getZ() - buildPos.getZ();

        // Determine the dominant direction to the target
        if (Math.abs(dx) >= Math.abs(dz)) {
            // Target is mostly east or west
            return dx > 0
                    ? Rotation.COUNTERCLOCKWISE_90  // entrance faces east
                    : Rotation.CLOCKWISE_90;         // entrance faces west
        } else {
            // Target is mostly north or south
            return dz > 0
                    ? Rotation.NONE                  // entrance faces south (default)
                    : Rotation.CLOCKWISE_180;        // entrance faces north
        }
    }

    private static int deriveVillageLevel(VillageTypeData typeData) {
        int count = typeData.getStarterBuildings().size();
        if (count <= 3)  return Math.max(1, count);
        if (count <= 6)  return 3 + (count - 4);
        if (count <= 9)  return 6 + (count - 7);
        return Math.min(10, 9 + (count - 10));
    }

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

        return building.getShape().getOrigin().offset(w / 2, 1, l / 2);
    }

    private static Rotation randomRotation(
            net.minecraft.util.RandomSource random) {
        Rotation[] rotations = Rotation.values();
        return rotations[random.nextInt(rotations.length)];
    }

    /**
     * Returns the surface position at the given XZ.
     * Uses {@code WORLD_SURFACE} and descends through water to solid ground.
     */
    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(
                Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        BlockPos surface = new BlockPos(pos.getX(), surfaceY, pos.getZ());

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
    private static boolean shapesOverlapXZ(Building.BuildingShape a,
                                           Building.BuildingShape b,
                                           int gap) {
        int aMinX = a.getOrigin().getX() - gap;
        int aMaxX = a.getOrigin().getX() + a.getWidth()  + gap;
        int aMinZ = a.getOrigin().getZ() - gap;
        int aMaxZ = a.getOrigin().getZ() + a.getLength() + gap;

        int bMinX = b.getOrigin().getX();
        int bMaxX = b.getOrigin().getX() + b.getWidth();
        int bMinZ = b.getOrigin().getZ();
        int bMaxZ = b.getOrigin().getZ() + b.getLength();

        return aMinX < bMaxX && aMaxX > bMinX
                && aMinZ < bMaxZ && aMaxZ > bMinZ;
    }
}