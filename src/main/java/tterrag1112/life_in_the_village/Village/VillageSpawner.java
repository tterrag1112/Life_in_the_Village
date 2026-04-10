// src/main/java/tterrag1112/life_in_the_village/Village/VillageSpawner.java
package tterrag1112.life_in_the_village.Village;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Inhabitants.VillageInhabitantPopulator;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Planning.*;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;

import java.util.*;

/**
 * Spawns a fully-realised village at a given origin position.
 *
 * <h3>Pipeline (Phase 5)</h3>
 * <ol>
 *   <li>Guards (known type, distance from existing villages)</li>
 *   <li>Site preparation</li>
 *   <li>Layout planning</li>
 *   <li>Building placement using rotations baked into the layout slots</li>
 *   <li>Farm plots</li>
 *   <li>Starter item stocking</li>
 *   <li>Inhabitant population (NPCs + households, building-driven)</li>
 *   <li>Merchant stall claim</li>
 *   <li>Decoration</li>
 *   <li>Trade routes</li>
 *   <li>Simulation baseline</li>
 * </ol>
 *
 * <h3>What changed in Phase 5</h3>
 * <ul>
 *   <li>The emergency placement pass is gone. Buildings the planner
 *       could not place are simply absent — there is no spiral fallback.</li>
 *   <li>NPC spawning is now driven by {@link VillageInhabitantPopulator}
 *       which reads {@link tterrag1112.life_in_the_village.Village.Inhabitants
 *       .BuildingInhabitantRegistry} per placed building. The old
 *       {@code starter_npcs} JSON list is no longer consulted.</li>
 *   <li>Households form during the inhabitant pass, not in a post-pass.</li>
 *   <li>Building rotation is decided by the planner during slot creation
 *       and stored on {@link LayoutSlot}, then read here at placement time.
 *       This eliminates the rotation/footprint mismatch bug.</li>
 * </ul>
 */
public class VillageSpawner {

    private static final int MIN_VILLAGE_DISTANCE = 128;

    // =========================================================================
    // Public API
    // =========================================================================

    public static Optional<Village> spawnVillage(ServerLevel level,
                                                 BlockPos origin,
                                                 String villageType,
                                                 String villageName) {
        // ── Guards ───────────────────────────────────────────────────────────
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

        // ── Phase -2: Pre-planning tree clearing ─────────────────────────────
        // The old VillageSitePreparer.prepare is replaced with per-slot
        // terrain work driven by the village type's TerrainStrategy. But
        // tree clearing still needs to happen BEFORE planning so the
        // planner's getHeight calls ignore canopy cover.
        BlockPos roughSurface = findSurface(level, origin);
        int buildingCount = typeData.getStarterBuildings().size();
        if (buildingCount >= LayoutDensityProfile.CAPITAL_THRESHOLD) {
            VillageSitePreparer.prepareCapital(level, roughSurface, villageLevel);
        } else {
            // Minimal pre-plan prep: tree clearing only. Full prep runs after
            // planning via the terrain strategy.
            VillageSitePreparer.prepare(level, roughSurface, villageLevel);
        }

        // ── Phase -1: Plan layout ────────────────────────────────────────────
        Optional<VillageLayout> layoutOpt = VillagePlanner.plan(
                level, roughSurface, typeData, rng, villageLevel);
        if (layoutOpt.isEmpty()) {
            System.out.println("VillageSpawner: planner rejected terrain — aborting");
            return Optional.empty();
        }
        VillageLayout layout = layoutOpt.get();

        // ── Register village ─────────────────────────────────────────────────
        Village village = new Village(villageName, typeData.getType());
        village.applyLayout(layout, villageLevel);
        data.addVillage(village);

        if (layout.buildings().isEmpty()) {
            System.out.println("VillageSpawner: no buildings planned — aborting");
            return Optional.empty();
        }
        // ── Phase 0: Terrain preparation (per-slot, strategy-driven) ────────
        // Runs AFTER planning so each building's pad Y is committed on the
        // slot before the terrain is modified. The strategy reads slot
        // pad Y and executes carving/filling/retaining walls/foundations
        // in the order defined by the village type's terrain_strategy.
        VillageBiomeStyle biomeStyle = VillageBiomeStyle.detect(level, roughSurface);
        TerrainProfile terrainProfile = TerrainAnalyzer.analyze(level, roughSurface);
        typeData.getTerrainStrategy().execute(level, layout, terrainProfile, biomeStyle);



        // ── Phase 1: Place buildings ─────────────────────────────────────────
        BuildingFootprint footprint = new BuildingFootprint();
        Map<BuildingType, Building>       placedBuildings    = new LinkedHashMap<>();
        Map<BuildingType, List<Building>> placedBuildingsAll = new LinkedHashMap<>();
        Map<BuildingType, Integer>        typeCounters       = new HashMap<>();

        BlockPos squareCenter = layout.getTownSquarePos() != null
                ? layout.getTownSquarePos() : layout.getCenter();

        for (LayoutSlot slot : layout.buildings()) {
            BuildingType buildingType = slot.getBuildingType();
            if (buildingType == null) continue;

            BlockPos buildPos = slot.getPos();


            int typeIndex = typeCounters.merge(buildingType, 1, Integer::sum);
            String buildingName = villageName + "_"
                    + buildingType.name().toLowerCase() + "_" + typeIndex;

            // ── Rotation comes from the slot — set by the planner ───────────
            Rotation rotation = slot.getRotation();

            Identifier structId = CultureResolver.resolveFromPath(
                    typeData.getCulture(), slot.getStructurePath(), level);

            try {
                int w = slot.getFootprintWidth();
                int l = slot.getFootprintLength();
                Optional<Building> placed = BuildingPlacer.placeAndRegister(
                        level, buildPos, structId, buildingName, buildingType, rotation);
                if (placed.isEmpty()) continue;

                Building newBuilding = placed.get();

                village.addBuilding(newBuilding);
                placedBuildings.putIfAbsent(buildingType, newBuilding);
                placedBuildingsAll
                        .computeIfAbsent(buildingType, k -> new ArrayList<>())
                        .add(newBuilding);

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

        // ── Phase 2: Farm plots ──────────────────────────────────────────────
        FarmPlotPlacer.placeAll(level, layout, village, data, rng);

        // ── Phase 3: Stock starter items ─────────────────────────────────────
        stockStarterItems(level, typeData, village, data, placedBuildings);

        // ── Phase 4: Populate inhabitants (NPCs + households) ────────────────
        VillageInhabitantPopulator.populate(
                level, village, data, placedBuildingsAll, rng);

        // ── Phase 4b: Merchant stalls ────────────────────────────────────────
        setupMerchantStalls(level, village, data, placedBuildingsAll, rng);

        // ── Phase 5: Decorate ────────────────────────────────────────────────
        VillageDecorator.decorateVillage(
                level, village, data, layout, footprint);


        // ── Phase 6: Trade routes ────────────────────────────────────────────
        TradeRouteManager.establishRoutes(level, village, data);

        // ── Phase 7: Simulation baseline ─────────────────────────────────────
        VillageSimEngine.buildBaseline(village, data, level.getGameTime());

        System.out.println("VillageSpawner: '" + villageName
                + "' spawned — buildings=" + village.getBuildingIds().size()
                + " farms=" + layout.farmPlots().size());

        return Optional.of(village);
    }

    // =========================================================================
    // Phase 3 — Starter items (unchanged from previous version)
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
    // Phase 4b — Merchant stall setup (unchanged from previous version)
    // =========================================================================

    private static void setupMerchantStalls(ServerLevel level,
                                            Village village,
                                            VillageSavedData data,
                                            Map<BuildingType, List<Building>> placedBuildingsAll,
                                            Random rng) {
        net.minecraft.world.phys.AABB villageBounds = village.getBounds(data)
                .map(b -> b.inflate(32))
                .orElse(null);
        if (villageBounds == null) return;

        List<TownspersonMob> merchants = level.getEntitiesOfClass(
                TownspersonMob.class, villageBounds,
                mob -> mob.getProfession() == Profession.MERCHANT
                        && mob.getAssignedVillageName()
                        .map(n -> n.equals(village.getName()))
                        .orElse(false));

        for (TownspersonMob merchant : merchants) {
            Building market = merchant.getAssignedBuildingId()
                    .flatMap(data::getBuildingById)
                    .filter(b -> b.getType() == BuildingType.MARKET)
                    .orElse(null);
            if (market == null) continue;

            MarketStallPlacer.claimSlot(
                            level, market,
                            merchant.getUUID(),
                            tterrag1112.life_in_the_village.Village.Economy.Market
                                    .MarketStall.OwnerType.NPC,
                            Long.MAX_VALUE,
                            data)
                    .ifPresent(stall -> {
                        data.addMarketStall(stall);
                        MarketStallPlacer.assignGoalIfNpc(level, stall);

                        System.out.println("VillageSpawner: merchant "
                                + merchant.getNpcName()
                                + " claimed stall " + stall.getSlotIndex()
                                + " in " + market.getName());
                    });
        }
    }

    // =========================================================================
    // Public utilities (unchanged from previous version)
    // =========================================================================

    public static boolean isFarEnoughFromExistingVillages(ServerLevel level,
                                                          BlockPos candidate) {
        VillageSavedData data = VillageSavedData.get(level);
        for (Village v : data.getAllVillages()) {
            BlockPos anchor = v.getAnchorPos();
            if (anchor == null) continue;
            if (anchor.distSqr(candidate) < MIN_VILLAGE_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Chooses the rotation that makes the building face toward {@code target}.
     * Kept here so the spawner can use it for capital fallback paths even
     * though the planner now decides rotations during slot creation.
     */
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
}