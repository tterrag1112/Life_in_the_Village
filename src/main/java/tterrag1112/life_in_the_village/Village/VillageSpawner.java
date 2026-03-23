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
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;

import java.util.*;

/**
 * Spawns a fully-realised village at a given origin position.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Guard checks (known type, distance from existing villages)</li>
 *   <li>Site preparation — clear trees, fill surface caves, gentle smoothing</li>
 *   <li>Layout planning on the now-clean terrain</li>
 *   <li>Building placement — re-queries live heightmap at placement time so
 *       any residual Y drift from prep is corrected</li>
 *   <li><b>Emergency placement pass</b> — guarantees critical buildings
 *       (TOWN_HALL, STOCKPILE, MARKET, FARMHOUSE) are always present</li>
 *   <li>Farm plot placement and registration</li>
 *   <li>Item stocking — stocks the first occurrence of each building type;
 *       supports multiple instances per type (two FARMHOUSEs, etc.)</li>
 *   <li>NPC spawning — assigns each NPC to the correct building instance,
 *       cycling through multi-instance types</li>
 *   <li>Decoration (paths, town square, lampposts, flowers, etc.)</li>
 *   <li>Trade route establishment</li>
 *   <li>Simulation baseline seeding</li>
 * </ol>
 *
 * <h3>Multi-instance buildings</h3>
 * Previous code used {@code placedBuildings.putIfAbsent} which silently
 * discarded the second FARMHOUSE (or MARKET, HOUSE, etc.). This rewrite
 * maintains two maps:
 * <ul>
 *   <li>{@code placedBuildings} — first instance of each type, used for
 *       starter item targeting and backward-compatible lookups</li>
 *   <li>{@code placedBuildingsAll} — all instances, used for NPC assignment
 *       so the second FARMER gets the second FARMHOUSE</li>
 * </ul>
 */
public class VillageSpawner {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int MIN_VILLAGE_DISTANCE = 128;

    /**
     * Building types whose absence breaks gameplay. If the planner fails to
     * place any of these, the emergency pass will attempt rescue placement.
     */
    private static final Set<BuildingType> CRITICAL_TYPES = Set.of(
            BuildingType.TOWN_HALL,
            BuildingType.STOCKPILE,
            BuildingType.MARKET,
            BuildingType.FARMHOUSE
    );

    // =========================================================================
    // Public API
    // =========================================================================

    public static Optional<Village> spawnVillage(ServerLevel level,
                                                 BlockPos origin,
                                                 String villageType,
                                                 String villageName) {

        // ── Guard: known type ─────────────────────────────────────────────────
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE.getType(villageType);
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

        Random rng          = new Random((long) origin.hashCode() * 31L + villageName.hashCode());
        int    villageLevel = deriveVillageLevel(typeData);

        // ── Phase -2: Prepare the site BEFORE planning ────────────────────────
        // Site prep clears trees, fills surface caves, and gently averages
        // terrain. Planning runs AFTER so slot Y values reflect the prepared
        // surface — preventing floating buildings.
        BlockPos roughSurface = findSurface(level, origin);
        int buildingCount2 = typeData.getStarterBuildings().size();
        if (buildingCount2 >= LayoutDensityProfile.CAPITAL_THRESHOLD) {
            VillageSitePreparer.prepareCapital(level, roughSurface, villageLevel);
        } else {
            VillageSitePreparer.prepare(level, roughSurface, villageLevel);
        }
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
        village.applyLayout(layout, villageLevel);
        data.addVillage(village);

        if (layout.buildings().isEmpty()) {
            System.out.println("VillageSpawner: planner produced no buildings — aborting");
            return Optional.empty();
        }

        System.out.println("VillageSpawner: planning complete for '"
                + villageName + "' — " + layout);

        // ── Phase 1: Place buildings ──────────────────────────────────────────
        // first instance per type (for items/backward compat)
        Map<BuildingType, Building>       placedBuildings    = new LinkedHashMap<>();
        // all instances per type (for NPC assignment cycling)
        Map<BuildingType, List<Building>> placedBuildingsAll = new LinkedHashMap<>();
        Map<BuildingType, Integer>        typeCounters       = new HashMap<>();

        BlockPos squareCenter = layout.getTownSquarePos() != null
                ? layout.getTownSquarePos()
                : layout.getCenter();

        for (LayoutSlot slot : layout.buildings()) {
            BuildingType buildingType = slot.getBuildingType();
            if (buildingType == null) continue;

            // Re-query live heightmap at placement time.
            // MOTION_BLOCKING_NO_LEAVES returns the solid surface block Y —
            // the structure origin goes here.
            BlockPos plannedPos  = slot.getPos();
            int      actualSurfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    plannedPos.getX(), plannedPos.getZ());
            BlockPos buildPos = new BlockPos(
                    plannedPos.getX(), actualSurfY, plannedPos.getZ());

            String structurePath = slot.getStructurePath();
            if (structurePath == null || structurePath.isBlank()) {
                structurePath = findStructurePath(typeData, buildingType);
            }
            if (structurePath == null) {
                System.out.println("VillageSpawner: no structure path for "
                        + buildingType + " — skipping");
                continue;
            }

            Identifier structId   = Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, structurePath);
            int        typeIndex  = typeCounters.merge(buildingType, 1, Integer::sum);
            String     buildingName = villageName + "_"
                    + buildingType.name().toLowerCase() + "_" + typeIndex;
            Rotation rotation;
            if (slot instanceof LayoutSlot.LayoutSlotWithRotation lsr) {
                rotation = lsr.getPresetRotation();
            } else {
                rotation = chooseFacingRotation(buildPos, squareCenter);
            }
            try {
                Optional<Building> placed = BuildingPlacer.placeAndRegister(
                        level, buildPos, structId, buildingName, buildingType, rotation);

                if (placed.isEmpty()) {
                    System.out.println("VillageSpawner: placeAndRegister returned empty for "
                            + buildingType + " at " + buildPos);
                    continue;
                }

                Building newBuilding = placed.get();

                // Check overlap against every already-placed building for this village
                boolean overlapsExisting = placedBuildingsAll.values().stream()
                        .flatMap(List::stream)
                        .anyMatch(existing -> shapesOverlapXZ(
                                existing.getShape(), newBuilding.getShape(),
                                StructureSizeCache.MIN_GAP));

                if (overlapsExisting) {
                    System.out.println("VillageSpawner: " + buildingType
                            + " at " + buildPos + " overlaps — removing");
                    data.removeBuilding(newBuilding);
                    continue;
                }

                village.addBuilding(newBuilding);
                // First instance → primary map (for starter items)
                placedBuildings.putIfAbsent(buildingType, newBuilding);
                // All instances → multi-map (for NPC cycling)
                placedBuildingsAll
                        .computeIfAbsent(buildingType, k -> new ArrayList<>())
                        .add(newBuilding);
                data.setDirty();

                System.out.println("VillageSpawner: placed "
                        + buildingType + " #" + typeIndex
                        + " at " + buildPos + " facing=" + rotation.name());

            } catch (Exception e) {
                System.out.println("VillageSpawner: exception placing "
                        + buildingType + " — " + e.getMessage());
            }
        }

        // Abort if nothing at all was placed
        if (placedBuildings.isEmpty()) {
            System.out.println("VillageSpawner: no buildings placed — aborting");
            return Optional.empty();
        }

        // ── Phase 1b: Emergency placement for critical missing buildings ───────
        emergencyPlaceMissing(level, layout, typeData, village, data,
                placedBuildings, placedBuildingsAll, typeCounters,
                villageName, squareCenter, rng);

        // ── Phase 2: Place and register farm plots ────────────────────────────
        FarmPlotPlacer.placeAll(level, layout, village, data, rng);

        // ── Phase 3: Stock starter items ──────────────────────────────────────
        // Items target the FIRST instance of each building type.
        for (VillageTypeData.StarterItem si : typeData.getStarterItems()) {
            try {
                BuildingType targetType     = BuildingType.valueOf(si.buildingType());
                Building     targetBuilding = placedBuildings.get(targetType);
                if (targetBuilding == null) continue;

                Identifier itemId = Identifier.parse(si.item());
                var itemHolder = BuiltInRegistries.ITEM.get(itemId);
                if (itemHolder.isEmpty()) continue;

                ItemStack stack = new ItemStack(itemHolder.get().value(), si.count());
                BuildingStorageAccess.storeItem(level, targetBuilding, stack);

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to stock '"
                        + si.item() + "' — " + e.getMessage());
            }
        }

        // ── Phase 4: Spawn NPCs ───────────────────────────────────────────────
        // Per-type counters so the nth NPC gets the nth building instance.
        Map<BuildingType, Integer> npcTypeCounters = new HashMap<>();

        for (VillageTypeData.StarterNpc sn : typeData.getStarterNpcs()) {
            try {
                BuildingType buildingType = BuildingType.valueOf(sn.buildingType());
                List<Building> instances  = placedBuildingsAll.get(buildingType);
                if (instances == null || instances.isEmpty()) continue;

                // Cycle through instances: first NPC → first building,
                // second NPC → second building (wraps if more NPCs than buildings)
                int npcIdx        = npcTypeCounters.merge(buildingType, 1, Integer::sum) - 1;
                Building assigned = instances.get(npcIdx % instances.size());

                TownspersonMob npc = ModEntities.TOWNSPERSON
                        .get().create(level, EntitySpawnReason.NATURAL);
                if (npc == null) continue;

                BlockPos spawnPos = randomPosInsideBuilding(level, assigned, rng);
                npc.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
                npc.finalizeSpawn(level,
                        level.getCurrentDifficultyAt(spawnPos),
                        EntitySpawnReason.NATURAL, null);

                Profession profession = Profession.valueOf(sn.profession());
                npc.setProfession(profession);
                npc.setAssignedBuildingId(assigned.getId());
                npc.setAssignedVillageName(villageName);
                npc.setFamilyRole(FamilyRole.valueOf(sn.familyRole()));

                boolean isResidence = buildingType == BuildingType.HOUSE
                        || buildingType == BuildingType.FARMHOUSE;
                npc.setHouseId(isResidence ? assigned.getId() : null);

                npc.receive(profession == Profession.MERCHANT
                        ? CurrencyValue.ofGold(2)
                        : CurrencyValue.ofSilver(5));

                level.addFreshEntity(npc);
                System.out.println("VillageSpawner: spawned "
                        + sn.profession() + " '"
                        + npc.getNpcName() + "' in "
                        + buildingType + " #" + (npcIdx % instances.size() + 1));

            } catch (Exception e) {
                System.out.println("VillageSpawner: failed to spawn NPC — "
                        + e.getMessage());
            }
        }

        // ── Phase 5: Decorate ─────────────────────────────────────────────────
        VillageDecorator.decorateVillage(level, village, data, layout);

        // ── Phase 6: Establish trade routes ───────────────────────────────────
        TradeRouteManager.establishRoutes(level, village, data);

        // ── Phase 7: Seed simulation baseline ─────────────────────────────────
        // Build an initial sim snapshot so the kingdom economy engine can
        // reason about this village even before its chunks are ever loaded again.
        VillageSimEngine.buildBaseline(village, data, level.getGameTime());

        System.out.println("VillageSpawner: '" + villageName
                + "' spawned — buildings=" + village.getBuildingIds().size()
                + " farms=" + layout.farmPlots().size()
                + " density=" + layout.getDensity().label());

        return Optional.of(village);
    }

    // =========================================================================
    // Phase 1b — Emergency placement for critical missing buildings
    // =========================================================================

    /**
     * Scans every CRITICAL building type declared in the village JSON. If any
     * are missing from {@code placedBuildingsAll} after the normal placement
     * pass, this method attempts rescue placement by spiralling outward from
     * just outside ring2 until flat ground is found.
     *
     * <p>This prevents a trade hub from spawning with no markets, or a village
     * from spawning with no stockpile, which would break NPC AI and stocking.
     */
    private static void emergencyPlaceMissing(
            ServerLevel level,
            VillageLayout layout,
            VillageTypeData typeData,
            Village village,
            VillageSavedData data,
            Map<BuildingType, Building>       placedBuildings,
            Map<BuildingType, List<Building>> placedBuildingsAll,
            Map<BuildingType, Integer>        typeCounters,
            String villageName,
            BlockPos squareCenter,
            Random rng) {

        BlockPos centre = layout.getCenter() != null
                ? layout.getCenter() : squareCenter;

        for (VillageTypeData.StarterBuilding sb : typeData.getStarterBuildings()) {
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            // Only rescue critical types that are completely absent
            if (!CRITICAL_TYPES.contains(btype)) continue;
            if (placedBuildingsAll.containsKey(btype)) continue;

            System.out.println("VillageSpawner: EMERGENCY placing missing "
                    + btype + " for " + villageName);

            int      startR      = layout.getDensity().getRing2Radius() + 16;
            BlockPos emergencyPos = null;

            outer:
            for (int r = startR; r <= startR + 200; r += 12) {
                for (int angleDeg = 0; angleDeg < 360; angleDeg += 18) {
                    double rad = Math.toRadians(angleDeg + rng.nextInt(9));
                    int    ex  = centre.getX() + (int)(Math.cos(rad) * r);
                    int    ez  = centre.getZ() + (int)(Math.sin(rad) * r);
                    int    ey  = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ex, ez);
                    BlockPos candidate = new BlockPos(ex, ey, ez);

                    if (!isReasonablyFlat(level, candidate, 6)) continue;

                    // Don't overlap any already-placed building
                    boolean overlaps = placedBuildingsAll.values().stream()
                            .flatMap(List::stream)
                            .anyMatch(b -> shapesOverlapXZ(
                                    b.getShape(),
                                    new Building.BuildingShape(candidate, 14, 8, 14),
                                    2));
                    if (overlaps) continue;

                    emergencyPos = candidate;
                    break outer;
                }
            }

            if (emergencyPos == null) {
                System.out.println("VillageSpawner: WARN — could not emergency-place "
                        + btype + " for " + villageName);
                continue;
            }

            Identifier structId     = Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, sb.structure());
            int        typeIndex    = typeCounters.merge(btype, 1, Integer::sum);
            String     buildingName = villageName + "_"
                    + btype.name().toLowerCase() + "_" + typeIndex;
            Rotation   rotation     = chooseFacingRotation(emergencyPos, squareCenter);

            try {
                Optional<Building> placed = BuildingPlacer.placeAndRegister(
                        level, emergencyPos, structId, buildingName, btype, rotation);

                if (placed.isPresent()) {
                    Building newBuilding = placed.get();
                    village.addBuilding(newBuilding);
                    placedBuildings.putIfAbsent(btype, newBuilding);
                    placedBuildingsAll
                            .computeIfAbsent(btype, k -> new ArrayList<>())
                            .add(newBuilding);
                    data.setDirty();
                    System.out.println("VillageSpawner: emergency placed "
                            + btype + " at " + emergencyPos.toShortString());
                }
            } catch (Exception e) {
                System.out.println("VillageSpawner: emergency place failed for "
                        + btype + " — " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Public utilities
    // =========================================================================

    public static boolean isFarEnoughFromExistingVillages(ServerLevel level, BlockPos pos) {
        VillageSavedData data = VillageSavedData.get(level);
        for (Village village : data.getAllVillages()) {
            Optional<net.minecraft.world.phys.AABB> bounds = village.getBounds(data);
            if (bounds.isPresent()) {
                double cx   = (bounds.get().minX + bounds.get().maxX) / 2;
                double cz   = (bounds.get().minZ + bounds.get().maxZ) / 2;
                double dist = Math.sqrt(Math.pow(pos.getX() - cx, 2)
                        + Math.pow(pos.getZ() - cz, 2));
                if (dist < MIN_VILLAGE_DISTANCE) return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Chooses the {@link Rotation} that makes the building face toward the
     * town square as closely as possible.
     *
     * <p>NBT structures are placed with their origin at the north-west corner
     * and their entrance facing south (positive Z) with {@link Rotation#NONE}.
     * To point the entrance toward a target we find which cardinal direction
     * the target lies in and pick the matching rotation.
     */
    static Rotation chooseFacingRotation(BlockPos buildPos, BlockPos target) {
        int dx = target.getX() - buildPos.getX();
        int dz = target.getZ() - buildPos.getZ();

        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0
                    ? Rotation.COUNTERCLOCKWISE_90  // entrance faces east
                    : Rotation.CLOCKWISE_90;        // entrance faces west
        } else {
            return dz > 0
                    ? Rotation.NONE                 // entrance faces south (default)
                    : Rotation.CLOCKWISE_180;       // entrance faces north
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
                    try { return BuildingType.valueOf(sb.type()) == targetType; }
                    catch (IllegalArgumentException e) { return false; }
                })
                .map(VillageTypeData.StarterBuilding::structure)
                .findFirst()
                .orElse(null);
    }

    private static BlockPos randomPosInsideBuilding(ServerLevel level,
                                                    Building building,
                                                    Random rng) {
        BlockPos min    = building.getShape().getMin();
        int      w      = building.getShape().getWidth();
        int      l      = building.getShape().getLength();
        int      floorY = building.getShape().getOrigin().getY();

        for (int attempt = 0; attempt < 10; attempt++) {
            int      x         = min.getX() + 1 + rng.nextInt(Math.max(1, w - 2));
            int      z         = min.getZ() + 1 + rng.nextInt(Math.max(1, l - 2));
            BlockPos candidate = new BlockPos(x, floorY + 1, z);
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()) {
                return candidate;
            }
        }
        return building.getShape().getOrigin().offset(w / 2, 1, l / 2);
    }

    /**
     * Returns the surface position at the given XZ.
     * Uses {@code WORLD_SURFACE} and descends through water to solid ground.
     */
    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int      surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        BlockPos surface  = new BlockPos(pos.getX(), surfaceY, pos.getZ());

        BlockState state = level.getBlockState(surface.below());
        if (state.liquid()) {
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos   check = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState s     = level.getBlockState(check);
                if (s.isSolidRender() && !s.liquid()) return check.above();
            }
        }
        return surface;
    }

    /**
     * Returns true if the XZ footprints of two building shapes overlap,
     * including a gap padding on shape {@code a}.
     */
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

    /**
     * Returns true if no corner of a {@code 2*radius × 2*radius} footprint
     * centred on {@code centre} differs by more than 6 blocks in Y from the
     * centre surface. Used by emergency placement to avoid cliffs.
     */
    private static boolean isReasonablyFlat(ServerLevel level,
                                            BlockPos centre,
                                            int radius) {
        int centreY = centre.getY();
        int[] corners = {
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        centre.getX() - radius, centre.getZ() - radius),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        centre.getX() + radius, centre.getZ() - radius),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        centre.getX() - radius, centre.getZ() + radius),
                level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        centre.getX() + radius, centre.getZ() + radius),
        };
        for (int y : corners) {
            if (Math.abs(y - centreY) > 6) return false;
        }
        return true;
    }
}