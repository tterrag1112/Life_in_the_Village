package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.HouseholdManager;
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
 *   <li>Site preparation (clear trees, fill caves, smooth terrain)</li>
 *   <li>Layout planning on the clean terrain</li>
 *   <li>Building placement with BuildingFootprint collision validation</li>
 *   <li>Emergency placement pass for critical missing buildings</li>
 *   <li>Farm plot placement</li>
 *   <li>Item stocking</li>
 *   <li>NPC spawning with multi-instance building cycling</li>
 *   <li>Decoration (roads, town square, perimeter, furniture)</li>
 *   <li>Trade route establishment</li>
 *   <li>Simulation baseline seeding</li>
 * </ol>
 *
 * <h3>Key change: BuildingFootprint</h3>
 * The footprint grid replaces the old O(N²) shapesOverlapXZ check
 * with O(W×L) HashSet lookups per candidate. It tracks building
 * footprints with buffers AND road reservations so nothing overlaps.
 * The footprint is built incrementally during Phase 1 and passed to
 * the decorator for road placement in Phase 5.
 */
public class VillageSpawner {

    private static final int MIN_VILLAGE_DISTANCE = 128;

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
        // ── Guards ────────────────────────────────────────────────────────────
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE.getType(villageType);
        if (typeData == null) {
            System.out.println("VillageSpawner: unknown type '" + villageType + "'");
            return Optional.empty();
        }

        VillageSavedData data = VillageSavedData.get(level);
        if (!isFarEnoughFromExistingVillages(level, origin)) {
            System.out.println("VillageSpawner: too close to existing village");
            return Optional.empty();
        }

        Random rng = new Random(
                (long) origin.hashCode() * 31L + villageName.hashCode());
        int villageLevel = deriveVillageLevel(typeData);

        // ── Phase -2: Site preparation ────────────────────────────────────────
        BlockPos roughSurface = findSurface(level, origin);
        int buildingCount = typeData.getStarterBuildings().size();
        if (buildingCount >= LayoutDensityProfile.CAPITAL_THRESHOLD) {
            VillageSitePreparer.prepareCapital(level, roughSurface, villageLevel);
        } else {
            VillageSitePreparer.prepare(level, roughSurface, villageLevel);
        }

        // ── Phase -1: Plan layout ─────────────────────────────────────────────
        Optional<VillageLayout> layoutOpt = VillagePlanner.plan(
                level, roughSurface, typeData, rng, villageLevel);
        if (layoutOpt.isEmpty()) {
            System.out.println("VillageSpawner: planner rejected terrain — aborting");
            return Optional.empty();
        }
        VillageLayout layout = layoutOpt.get();

        // ── Register village ──────────────────────────────────────────────────
        Village village = new Village(villageName, typeData.getType());
        village.applyLayout(layout, villageLevel);
        data.addVillage(village);

        if (layout.buildings().isEmpty()) {
            System.out.println("VillageSpawner: no buildings planned — aborting");
            return Optional.empty();
        }

        // ── Capital pre-placement streets ─────────────────────────────────────
        if (layout.hasCapitalStreetGraph()) {
            VillageDecorator.placeCapitalStreets(level, village, data, layout);
        }

        // ── Phase 1: Place buildings ──────────────────────────────────────────
        BuildingFootprint footprint = new BuildingFootprint();
        Map<BuildingType, Building>       placedBuildings    = new LinkedHashMap<>();
        Map<BuildingType, List<Building>> placedBuildingsAll = new LinkedHashMap<>();
        Map<BuildingType, Integer>        typeCounters       = new HashMap<>();

        BlockPos squareCenter = layout.getTownSquarePos() != null
                ? layout.getTownSquarePos() : layout.getCenter();

        for (LayoutSlot slot : layout.buildings()) {
            BuildingType buildingType = slot.getBuildingType();
            if (buildingType == null) continue;

            int surfY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    slot.getPos().getX(), slot.getPos().getZ());
            BlockPos buildPos = new BlockPos(
                    slot.getPos().getX(), surfY, slot.getPos().getZ());

            int typeIndex = typeCounters.merge(buildingType, 1, Integer::sum);
            String buildingName = villageName + "_"
                    + buildingType.name().toLowerCase() + "_" + typeIndex;

            Rotation rotation;
            if (slot instanceof LayoutSlot.LayoutSlotWithRotation lr) {
                rotation = lr.getPresetRotation();
            } else {
                rotation = chooseFacingRotation(buildPos, squareCenter);
            }

            Identifier structId = CultureResolver.resolveFromPath(
                    typeData.getCulture(), slot.getStructurePath(), level);


            try {
                Optional<Building> placed = BuildingPlacer.placeAndRegister(
                        level, buildPos, structId, buildingName,
                        buildingType, rotation);
                if (placed.isEmpty()) continue;

                Building newBuilding = placed.get();

                // ── Collision check using footprint grid ──────────────────────
                boolean isCapitalPlot =
                        slot instanceof LayoutSlot.LayoutSlotWithRotation;
                if (!isCapitalPlot) {
                    Building.BuildingShape shape = newBuilding.getShape();
                    if (!footprint.isClear(shape.getOrigin(),
                            shape.getWidth(), shape.getLength(),
                            BuildingFootprint.DEFAULT_BUFFER)) {
                        System.out.println("VillageSpawner: " + buildingType
                                + " at " + buildPos + " overlaps — removing");
                        data.removeBuilding(newBuilding);
                        continue;
                    }
                }

                village.addBuilding(newBuilding);
                placedBuildings.putIfAbsent(buildingType, newBuilding);
                placedBuildingsAll
                        .computeIfAbsent(buildingType, k -> new ArrayList<>())
                        .add(newBuilding);

                // Register in collision grid
                footprint.occupyBuilding(newBuilding,
                        BuildingFootprint.DEFAULT_BUFFER);
                data.setDirty();

                System.out.println("VillageSpawner: placed " + buildingType
                        + " #" + typeIndex + " at " + buildPos
                        + " facing=" + rotation.name());

            } catch (Exception e) {
                System.out.println("VillageSpawner: exception placing "
                        + buildingType + " — " + e.getMessage());
            }
        }

        if (placedBuildings.isEmpty()) {
            System.out.println("VillageSpawner: no buildings placed — aborting");
            return Optional.empty();
        }

        // ── Phase 1b: Emergency placement ─────────────────────────────────────
        emergencyPlaceMissing(level, layout, typeData, village, data,
                placedBuildings, placedBuildingsAll, typeCounters,
                villageName, squareCenter, footprint, rng);

        // ── Phase 2: Farm plots ───────────────────────────────────────────────
        FarmPlotPlacer.placeAll(level, layout, village, data, rng);

        // ── Phase 3: Stock starter items ──────────────────────────────────────
        stockStarterItems(level, typeData, village, data, placedBuildings);

        // ── Phase 4: Spawn NPCs ───────────────────────────────────────────────
        spawnNpcs(level, typeData, village, data, placedBuildingsAll,
                squareCenter, rng);

        // ── Phase 5: Decorate ─────────────────────────────────────────────────
        VillageDecorator.decorateVillage(
                level, village, data, layout, footprint);

        if (layout.hasCapitalStreetGraph()
                && !layout.getGatePositions().isEmpty()) {
            layout.getGatePositions().forEach(village::addGatePosition);
        }

        // ── Phase 6: Trade routes ─────────────────────────────────────────────
        TradeRouteManager.establishRoutes(level, village, data);

        // ── Phase 7: Simulation baseline ──────────────────────────────────────
        VillageSimEngine.buildBaseline(village, data, level.getGameTime());

        // ── Phase 8: Households ───────────────────────────────────────────────
        HouseholdManager.buildHouseholdsForVillage(level,
                village, data);

        System.out.println("VillageSpawner: '" + villageName
                + "' spawned — buildings=" + village.getBuildingIds().size()
                + " farms=" + layout.farmPlots().size());

        return Optional.of(village);
    }

    // =========================================================================
    // Phase 1b — Emergency placement
    // =========================================================================

    private static void emergencyPlaceMissing(
            ServerLevel level, VillageLayout layout,
            VillageTypeData typeData, Village village,
            VillageSavedData data,
            Map<BuildingType, Building> placedBuildings,
            Map<BuildingType, List<Building>> placedBuildingsAll,
            Map<BuildingType, Integer> typeCounters,
            String villageName, BlockPos squareCenter,
            BuildingFootprint footprint, Random rng) {

        BlockPos centre = layout.getCenter() != null
                ? layout.getCenter() : squareCenter;

        for (VillageTypeData.StarterBuilding sb
                : typeData.getStarterBuildings()) {
            BuildingType btype;
            try { btype = BuildingType.valueOf(sb.type()); }
            catch (IllegalArgumentException e) { continue; }

            if (!CRITICAL_TYPES.contains(btype)) continue;
            if (placedBuildingsAll.containsKey(btype)) continue;

            System.out.println("VillageSpawner: EMERGENCY placing " + btype);

            int startR = layout.getDensity().getRing2Radius() + 16;
            BlockPos emergencyPos = null;

            outer:
            for (int r = startR; r <= startR + 200; r += 12) {
                for (int angleDeg = 0; angleDeg < 360; angleDeg += 18) {
                    double rad = Math.toRadians(angleDeg + rng.nextInt(9));
                    int ex = centre.getX() + (int)(Math.cos(rad) * r);
                    int ez = centre.getZ() + (int)(Math.sin(rad) * r);
                    int ey = level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ex, ez);
                    BlockPos candidate = new BlockPos(ex, ey, ez);

                    if (!isReasonablyFlat(level, candidate, 6)) continue;

                    // Use footprint instead of AABB loop
                    if (!footprint.isClear(candidate, 14, 14,
                            BuildingFootprint.DEFAULT_BUFFER)) continue;

                    emergencyPos = candidate;
                    break outer;
                }
            }

            if (emergencyPos == null) {
                System.out.println("VillageSpawner: WARN — could not "
                        + "emergency-place " + btype);
                continue;
            }

            Identifier structId = CultureResolver.resolveFromPath(
                    typeData.getCulture(), sb.structure(), level);
            int typeIndex = typeCounters.merge(btype, 1, Integer::sum);
            String buildingName = villageName + "_"
                    + btype.name().toLowerCase() + "_" + typeIndex;
            Rotation rotation = chooseFacingRotation(
                    emergencyPos, squareCenter);

            try {
                Optional<Building> placed = BuildingPlacer.placeAndRegister(
                        level, emergencyPos, structId, buildingName,
                        btype, rotation);
                if (placed.isPresent()) {
                    Building b = placed.get();
                    village.addBuilding(b);
                    placedBuildings.putIfAbsent(btype, b);
                    placedBuildingsAll
                            .computeIfAbsent(btype, k -> new ArrayList<>())
                            .add(b);
                    footprint.occupyBuilding(b,
                            BuildingFootprint.DEFAULT_BUFFER);
                    data.setDirty();
                    System.out.println("VillageSpawner: emergency placed "
                            + btype + " at " + emergencyPos.toShortString());
                }
            } catch (Exception e) {
                System.out.println("VillageSpawner: emergency place failed — "
                        + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Phase 3 — Starter items
    // =========================================================================

    private static void stockStarterItems(ServerLevel level,
                                          VillageTypeData typeData,
                                          Village village,
                                          VillageSavedData data,
                                          Map<BuildingType, Building> placedBuildings) {
        for (VillageTypeData.StarterItem si : typeData.getStarterItems()) {
            BuildingType targetType;
            try { targetType = BuildingType.valueOf(si.buildingType()); }
            catch (IllegalArgumentException e) { continue; }

            Building target = placedBuildings.get(targetType);
            if (target == null) continue;

            BuiltInRegistries.ITEM.get(Identifier.parse(si.item()))
                    .ifPresent(holder -> {
                        ItemStack stack = new ItemStack(
                                holder.value(), si.count());
                        BuildingStorageAccess.storeItem(
                                level, target, stack);
                    });
        }
    }

    // =========================================================================
    // Phase 4 — NPC spawning
    // =========================================================================

    private static void spawnNpcs(ServerLevel level,
                                  VillageTypeData typeData,
                                  Village village,
                                  VillageSavedData data,
                                  Map<BuildingType, List<Building>> placedBuildingsAll,
                                  BlockPos squareCenter,
                                  Random rng) {
        int npcIdx = 0;
        for (VillageTypeData.StarterNpc sn : typeData.getStarterNpcs()) {
            BuildingType buildingType;
            try { buildingType = BuildingType.valueOf(sn.buildingType()); }
            catch (IllegalArgumentException e) { continue; }

            Profession profession;
            try { profession = Profession.valueOf(sn.profession()); }
            catch (IllegalArgumentException e) { continue; }

            FamilyRole familyRole;
            try { familyRole = FamilyRole.valueOf(sn.familyRole()); }
            catch (IllegalArgumentException e) {
                familyRole = FamilyRole.UNASSIGNED;
            }

            List<Building> instances =
                    placedBuildingsAll.get(buildingType);
            if (instances == null || instances.isEmpty()) continue;

            Building assigned = instances.get(npcIdx % instances.size());
            BlockPos spawnPos = randomPosInsideBuilding(
                    level, assigned, rng);

            try {
                TownspersonMob npc = ModEntities.TOWNSPERSON.get()
                        .create(level, EntitySpawnReason.MOB_SUMMONED);
                if (npc == null) continue;

                npc.setPos(spawnPos.getX() + 0.5,spawnPos.getY(), spawnPos.getZ() + 0.5);
                npc.setYRot(rng.nextFloat() * 360);
                npc.setXRot(0);


                npc.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
                        EntitySpawnReason.MOB_SUMMONED, null);

                npc.setProfession(profession);
                npc.setFamilyRole(familyRole);
                npc.assignToBuilding(assigned.getId(), village.getName());

                if (familyRole == FamilyRole.HEAD) {
                    npc.setHouseId(assigned.getType() == BuildingType.HOUSE
                            ? assigned.getId() : null);
                }

                npc.receive(profession == Profession.MERCHANT
                        ? CurrencyValue.ofGold(2)
                        : CurrencyValue.ofSilver(5));

                level.addFreshEntity(npc);
                npcIdx++;

            } catch (Exception e) {
                System.out.println("VillageSpawner: NPC spawn failed — "
                        + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Public utilities
    // =========================================================================

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

    // =========================================================================
    // Private helpers
    // =========================================================================

    static Rotation chooseFacingRotation(BlockPos buildPos, BlockPos target) {
        int dx = target.getX() - buildPos.getX();
        int dz = target.getZ() - buildPos.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0
                    ? Rotation.COUNTERCLOCKWISE_90
                    : Rotation.CLOCKWISE_90;
        } else {
            return dz > 0
                    ? Rotation.NONE
                    : Rotation.CLOCKWISE_180;
        }
    }

    private static int deriveVillageLevel(VillageTypeData typeData) {
        int count = typeData.getStarterBuildings().size();
        if (count <= 3) return Math.max(1, count);
        if (count <= 6) return 3 + (count - 4);
        if (count <= 9) return 6 + (count - 7);
        return Math.min(10, 9 + (count - 10));
    }

    private static BlockPos randomPosInsideBuilding(ServerLevel level,
                                                    Building building,
                                                    Random rng) {
        BlockPos min = building.getShape().getMin();
        int w = building.getShape().getWidth();
        int l = building.getShape().getLength();
        int floorY = building.getShape().getOrigin().getY();

        for (int attempt = 0; attempt < 10; attempt++) {
            int x = min.getX() + 1 + rng.nextInt(Math.max(1, w - 2));
            int z = min.getZ() + 1 + rng.nextInt(Math.max(1, l - 2));
            BlockPos candidate = new BlockPos(x, floorY + 1, z);
            if (level.getBlockState(candidate).isAir()
                    && level.getBlockState(candidate.above()).isAir()) {
                return candidate;
            }
        }
        return building.getShape().getOrigin().offset(w / 2, 1, l / 2);
    }

    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE,
                pos.getX(), pos.getZ());
        BlockPos surface = new BlockPos(pos.getX(), surfaceY, pos.getZ());
        BlockState state = level.getBlockState(surface.below());
        if (state.liquid()) {
            for (int y = surfaceY - 1; y > level.getMinY(); y--) {
                BlockPos check = new BlockPos(pos.getX(), y, pos.getZ());
                BlockState s = level.getBlockState(check);
                if (s.isSolidRender() && !s.liquid()) return check.above();
            }
        }
        return surface;
    }

    private static boolean isReasonablyFlat(ServerLevel level,
                                            BlockPos centre, int radius) {
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