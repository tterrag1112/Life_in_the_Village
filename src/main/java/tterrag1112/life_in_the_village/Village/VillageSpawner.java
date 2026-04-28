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
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.NeighborColorIndex;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.TintPass;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantSelector;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VillagePaletteResolver;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageDecorator;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Roads.Planning.ConnectorPlanner;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Kingdom.Placement.DeepTerrainInspector;
import tterrag1112.life_in_the_village.Village.Planning.*;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.World.Atlas.AtlasSampler;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimEngine;

import java.util.*;

/**
 * Spawns a fully-realised village at an origin position.
 *
 * <h3>Changes in the primitive rewrite</h3>
 * <ul>
 *   <li>Capital branch removed — capitals will be reintroduced as a
 *       ShapeRecipe. There is one spawn path now.</li>
 *   <li>Rotation comes from the slot (set by layout primitives when
 *       they decided which road the building faces), not re-computed
 *       here.</li>
 *   <li>Footprint is pre-checked against an incremental
 *       {@link BuildingFootprint} before calling
 *       {@link BuildingPlacer#placeAndRegister} — fixes the ghost-building
 *       bug where a failed placement left NBT in the world.</li>
 *   <li>Building Y uses the slot's pre-committed pad Y — no
 *       re-querying {@code level.getHeight} at placement time, which
 *       was the source of Y mismatches.</li>
 * </ul>
 */
public class VillageSpawner {

    private static final int MIN_VILLAGE_DISTANCE = 128;

    /** Maximum offset (blocks) searched during local refinement. */
    private static final int LOCAL_REFINEMENT_RADIUS = 40;
    /** Grid step for the local refinement search. */
    private static final int LOCAL_SEARCH_STEP = 8;

    public static Optional<Village> spawnVillage(ServerLevel level,
                                                 BlockPos origin,
                                                 String villageType,
                                                 String villageName) {
        // ── Guards ──────────────────────────────────────────────────────────
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

        Random rng = new Random((long) origin.hashCode() * 31L + villageName.hashCode());
        int villageLevel = deriveVillageLevel(typeData);

        // ── Pre-planning tree clearing (single path, no capital branch) ─────
        BlockPos roughSurface = findSurface(level, origin);
        VillageSitePreparer.prepare(level, roughSurface, villageLevel);

        // ── Plan ────────────────────────────────────────────────────────────
        Optional<VillageLayout> layoutOpt = VillagePlanner.plan(
                level, roughSurface, typeData, rng, villageLevel);

        if (layoutOpt.isEmpty()) {
            // Safety net: TerrainAnalyzer rejected the planned position. Search a
            // small radius for a better offset (handles noise-vs-chunk height
            // disagreements of a few blocks, or minor water/cliff at the planned spot).
            BlockPos refined = findBetterLocalSite(level, roughSurface, LOCAL_REFINEMENT_RADIUS, typeData);
            if (refined != null) {
                System.out.println("VillageSpawner: local refinement — retrying at "
                        + refined.toShortString());
                VillageSitePreparer.prepare(level, refined, villageLevel);
                layoutOpt = VillagePlanner.plan(level, refined, typeData, rng, villageLevel);
                if (layoutOpt.isPresent()) roughSurface = refined;
            }
        }

        if (layoutOpt.isEmpty()) {
            System.out.println("VillageSpawner: planner rejected (incl. local refinement) — aborting");
            return Optional.empty();
        }
        VillageLayout layout = layoutOpt.get();

        // ── Register village ────────────────────────────────────────────────
        Village village = new Village(villageName, typeData.getType());
        village.applyLayout(layout, villageLevel);
        // Copy the main gate endpoint onto the persistent Village
        village.setMainGateEndpoint(layout.getMainGateEndpoint());
        data.addVillage(village);
        // Ensure a (initially empty) village road graph exists for this village,
        // then populate it with gateway nodes derived from the layout.
        tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData
                .get(level).getOrCreate(village.getId());
        tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayPopulator
                .populate(level, village, layout);
        tterrag1112.life_in_the_village.Village.Roads.Planning.InternalRoadCommitter
                .commit(level, village, layout);

        // Phase 9 — fire VillagePlacementEvent with the network-alignment score
        // contribution that informed (or would have informed) the placement
        // decision. isCapital is approximated by "kingdom has only this village"
        // since the spawner doesn't know its caller's intent here.
        try {
            tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph roadGraph =
                    tterrag1112.life_in_the_village.Networking.WorldRoadSavedData.get(level).getGraph();
            net.minecraft.core.BlockPos anchor = village.getAnchorPos();
            float netScore = anchor != null
                    ? tterrag1112.life_in_the_village.Village.Roads.Lifecycle
                            .NetworkAlignmentScorer.networkAlignmentScore(anchor, roadGraph)
                    : 0f;
            boolean firstInKingdom = data.getKingdomForVillage(village.getId())
                    .map(k -> k.getVillageIds().size() <= 1).orElse(true);
            tterrag1112.life_in_the_village.Village.Roads.Lifecycle.VillagePlacementEvent.fire(
                    new tterrag1112.life_in_the_village.Village.Roads.Lifecycle
                            .VillagePlacementEvent.Event(
                                    village.getId(),
                                    village.getVillageType(),
                                    anchor != null ? anchor : net.minecraft.core.BlockPos.ZERO,
                                    firstInKingdom,
                                    netScore,
                                    level.getGameTime()));
        } catch (Exception ignore) { /* event hook must never block placement */ }

        if (layout.buildings().isEmpty()) {
            System.out.println("VillageSpawner: no buildings planned — aborting");
            return Optional.empty();
        }

        // ── Terrain preparation (strategy runs against committed pad Ys) ────
        VillageBiomeStyle biomeStyle = VillageBiomeStyle.detect(level, roughSurface);
        TerrainProfile terrainProfile = TerrainAnalyzer.analyze(level, roughSurface);
        typeData.getTerrainStrategy().execute(level, layout, terrainProfile, biomeStyle);

        // ── Place buildings ─────────────────────────────────────────────────
        BuildingFootprint footprint = new BuildingFootprint();
        Map<BuildingType, Building> placedBuildings = new LinkedHashMap<>();
        Map<BuildingType, List<Building>> placedBuildingsAll = new LinkedHashMap<>();
        Map<BuildingType, Integer> typeCounters = new HashMap<>();
        // P0a-11: per-village running index of placed primary colours.
        // Built incrementally as each placement completes — placement
        // order (the matcher's iteration order) is the source of truth
        // for which buildings are "already placed" at sample time.
        NeighborColorIndex neighborIndex = new NeighborColorIndex();

        for (LayoutSlot slot : layout.buildings()) {
            BuildingType buildingType = slot.getBuildingType();
            if (buildingType == null) continue;

            BlockPos slotCentre = slot.getPos();  // planner's centre-based pos
            int w = slot.getFootprintWidth();
            int l = slot.getFootprintLength();

            // Convert centre → pre-rotation NW corner that placeAndRegister expects.
            //
            // BuildingPlacer treats `pos` as the pivot StructureTemplate.placeInWorld
            // rotates around. For each rotation, the pivot that produces a
            // centred footprint is different — we compute it by working
            // backwards from "I want the actual placed footprint centred on slotCentre".
            //
            // The unrotated structure size is (rawW, rawL) — these are the
            // slot's footprint dimensions BEFORE rotation was applied by
            // StructureSizeCache. For 90/270 rotations, the cache swapped
            // them, so we need to swap back to get raw dimensions.
            int rawW = w;
            int rawL = l;
            Rotation rotation = slot.getRotation();
            if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
                rawW = l;
                rawL = w;
            }

            // After placeInWorld with rotation R and pivot P, the real NW
            // corner of the placed footprint is:
            //   NONE:                  NW = P
            //   CLOCKWISE_90:          NW = P.offset(-(rawL-1), 0, 0)
            //   COUNTERCLOCKWISE_90:   NW = P.offset(0, 0, -(rawW-1))
            //   CLOCKWISE_180:         NW = P.offset(-(rawW-1), 0, -(rawL-1))
            //
            // We want NW such that (NW + (w/2, l/2)) == slotCentre.
            // Solve for P in each rotation:
            BlockPos buildPos;
            int halfW = w / 2;
            int halfL = l / 2;
            BlockPos targetNW = new BlockPos(
                    slotCentre.getX() - halfW,
                    slotCentre.getY(),
                    slotCentre.getZ() - halfL);

            switch (rotation) {
                case CLOCKWISE_90 -> buildPos =
                        targetNW.offset(rawL - 1, 0, 0);
                case COUNTERCLOCKWISE_90 -> buildPos =
                        targetNW.offset(0, 0, rawW - 1);
                case CLOCKWISE_180 -> buildPos =
                        targetNW.offset(rawW - 1, 0, rawL - 1);
                default -> buildPos = targetNW;  // NONE
            }

            int typeIndex = typeCounters.merge(buildingType, 1, Integer::sum);
            String buildingName = villageName + "_"
                    + buildingType.name().toLowerCase() + "_" + typeIndex;

            // Variant-aware path: the matcher picks variant id + style
            // per slot during P0a-06; fall back to the type-default for
            // slots created on legacy code paths that haven't been
            // routed through the matcher yet.
            String variantId = slot.getVariantId() != null
                    ? slot.getVariantId()
                    : BuildingVariant.defaultVariantId(buildingType);
            Style variantStyle = slot.getStyle() != null
                    ? slot.getStyle() : Style.RURAL;
            CultureResolver.LegacyTypeLevel parsed = CultureResolver
                    .parseLegacyTypeLevel(slot.getStructurePath());
            int buildingLevel = parsed != null ? parsed.level() : 1;
            Identifier structId = CultureResolver.resolve(
                    typeData.getCulture(), variantStyle, buildingType,
                    variantId, buildingLevel, level);

            // P0a-10/11: build the TintPass.Plan.
            //   * Variant drives which colour slots are populated.
            //   * Palette comes from VillagePaletteResolver's resolution
            //     chain (village colorPalette → culture default → NONE).
            //   * Neighbour index supplies the soft-exclusion set so a
            //     row of houses doesn't all roll the same primary colour.
            BuildingVariant variant = VariantRegistry.INSTANCE
                    .find(typeData.getCulture(), variantStyle,
                            buildingType, variantId)
                    .or(() -> VariantRegistry.INSTANCE
                            .defaultVariant(buildingType,
                                    typeData.getCulture(), variantStyle))
                    .or(() -> VariantRegistry.INSTANCE
                            .defaultVariant(buildingType,
                                    "default", variantStyle))
                    .orElseGet(() -> VariantSelector.Fallback
                            .syntheticDefault(buildingType, variantStyle));
            java.util.Set<net.minecraft.world.item.DyeColor> neighborColors =
                    neighborIndex.colorsWithin(slotCentre,
                            VillagePaletteResolver.NEIGHBOUR_RADIUS);
            TintPass.Plan tintPlan = VillagePaletteResolver
                    .planFor(typeData, variant, rng, neighborColors);

            try {
                Optional<Building> placed = BuildingPlacer.placeAndRegister(
                        level, buildPos, structId, buildingName, buildingType,
                        rotation, variantId, tintPlan);
                if (placed.isEmpty()) continue;

                Building newBuilding = placed.get();
                village.addBuilding(newBuilding);
                placedBuildings.putIfAbsent(buildingType, newBuilding);
                placedBuildingsAll
                        .computeIfAbsent(buildingType, k -> new ArrayList<>())
                        .add(newBuilding);

                // P0a-11: feed the running neighbour index so the next
                // placement's primary sample sees this building.
                neighborIndex.add(slotCentre, newBuilding.getPrimaryColor());

                footprint.occupyBuilding(newBuilding, BuildingFootprint.DEFAULT_BUFFER);
                data.setDirty();

                System.out.println("VillageSpawner: placed " + buildingType
                        + " #" + typeIndex + " (centre=" + slotCentre
                        + " pivot=" + buildPos + " rot=" + rotation + ")");

            } catch (Exception e) {
                System.out.println("VillageSpawner: exception placing "
                        + buildingType + " — " + e.getMessage());
            }
        }

        if (placedBuildings.isEmpty()) {
            System.out.println("VillageSpawner: no buildings placed — aborting");
            return Optional.empty();
        }

        // ── Remaining phases ────────────────────────────────────────────────
        FarmPlotPlacer.placeAll(level, layout, village, data, rng);
        VillageInhabitantPopulator.populate(level, village, data, placedBuildingsAll, rng);
        setupMerchantStalls(level, village, data, placedBuildingsAll, rng);
        VillageDecorator.decorateVillage(level, village, data, layout, footprint);
        // Phase 0c doc 02 — AdjunctPlot realiser. Sits between the
        // legacy decorator (which stamps building foundations and
        // surface dressings) and the Phase 0b decoration pass
        // (which needs to skip plot footprints when emitting
        // slots). Plot bounds are persisted; no NBTs stamped at
        // this phase.
        tterrag1112.life_in_the_village.Village.Decoration.Adjunct
                .AdjunctPlotRealiser.run(level, village, data);
        // Phase 0b doc 01 — uniform decoration pass. Runs after
        // building placement, terrain, farm plots, the legacy
        // decorator pass, and the AdjunctPlot realiser; before
        // TradeRouteManager (which doesn't change the village
        // footprint). Phase 0d (Subbuilding) realisers will land
        // between the AdjunctPlot pass and this pass when they
        // ship.
        tterrag1112.life_in_the_village.Village.Decoration.Framework
                .DecorationPass.run(level, village, data, layout);
        TradeRouteManager.establishRoutes(level, village, data); // no-op for useGraphConnector villages
        if (village.useGraphConnector()) {
            WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
            ConnectorPlanner.ConnectorPlanResult result = ConnectorPlanner.planConnector(
                    level, roadData.getGraph(), data, village,
                    ConnectorPlanner.DEFAULT_SEARCH_RADIUS);
            System.out.println("[ConnectorPlanner] " + result.planSummary());
        }
        VillageSimEngine.buildBaseline(village, data, level.getGameTime());

        // Phase 4 doc 27: scan for implicit guild clusters, then promote
        // any matching cluster whose hall building was placed in this
        // pass. Adventurer guilds keep their legacy GuildData path —
        // the new abstract layer is additive.
        long now = level.getGameTime();
        tterrag1112.life_in_the_village.Guilds.Common.GuildBootstrap
                .scanAndCreateImplicit(level, village, data, now);
        for (var bid : village.getBuildingIds()) {
            data.getBuildingById(bid).ifPresent(b -> {
                if (tterrag1112.life_in_the_village.Guilds.Common.GuildHallTypes
                        .isGuildHall(b.getType())) {
                    tterrag1112.life_in_the_village.Guilds.Common.GuildBootstrap
                            .onHallConstructed(level, b, village, now);
                }
            });
        }

        // Phase 4 doc 30 — first entry on the brand-new village's
        // history log marks the founding (LEGENDARY tier).
        java.util.Map<String, String> foundingDetails = new java.util.LinkedHashMap<>();
        foundingDetails.put("village_name", village.getName());
        tterrag1112.life_in_the_village.Village.History.HistoryProducer.record(
                level, village,
                tterrag1112.life_in_the_village.Village.History.HistoryEventType.VILLAGE_FOUNDED,
                now, foundingDetails, java.util.List.of());

        // Phase 5 doc 31 — enact the village culture's initial laws.
        // CultureResolver.of walks village → kingdom → registry default
        // so a village created outside any kingdom gets the empty
        // default law set rather than throwing.
        var villageCulture = tterrag1112.life_in_the_village.Cultures.CultureResolver
                .of(level, village);
        for (String lawName : villageCulture.lawDefaults().initialLaws()) {
            try {
                var law = tterrag1112.life_in_the_village.Npc.Laws.VillageLaw.valueOf(lawName);
                tterrag1112.life_in_the_village.Npc.Laws.LawEnactment.enact(
                        village, law, /* params */ null, level, /* actor */ null);
            } catch (IllegalArgumentException e) {
                System.out.println("[CultureBoot] Unknown initial law: " + lawName);
            }
        }

        System.out.println("VillageSpawner: '" + villageName
                + "' spawned — buildings=" + village.getBuildingIds().size()
                + " farms=" + layout.farmPlots().size());

        return Optional.of(village);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Searches a grid of offsets within {@code radius} blocks of {@code origin}
     * for the position with the highest predicted terrain suitability (using
     * {@link DeepTerrainInspector} — noise-only, no chunk loading). Returns the
     * best candidate whose predicted type-aware suitability exceeds the threshold,
     * or {@code null} if none do.
     *
     * <p>Uses a 20-block inspection radius for speed — enough to rank candidates.
     * The type-aware formula is used so RIVERSIDE villages are scored on their
     * proximity to water rather than penalised for it.
     */
    private static BlockPos findBetterLocalSite(ServerLevel level,
                                                BlockPos origin,
                                                int radius,
                                                VillageTypeData typeData) {
        java.util.Set<tterrag1112.life_in_the_village.Village.VillageTag> tags = typeData.getTags();
        BlockPos best = null;
        float bestSuit = 0.05f;

        for (int dx = -radius; dx <= radius; dx += LOCAL_SEARCH_STEP) {
            for (int dz = -radius; dz <= radius; dz += LOCAL_SEARCH_STEP) {
                if (dx == 0 && dz == 0) continue; // origin already failed
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) continue;

                int bx = origin.getX() + dx;
                int bz = origin.getZ() + dz;
                int y  = AtlasSampler.sampleHeight(level, bx, bz);
                BlockPos candidate = new BlockPos(bx, y, bz);

                var result = DeepTerrainInspector.inspect(level, candidate, 20);
                float suit = tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile
                        .computeSuitability(result.flatRatio(), result.waterRatio(),
                                result.steepRatio(), tags);
                if (suit > bestSuit) {
                    bestSuit = suit;
                    best = candidate;
                }
            }
        }
        return best;
    }

    // =========================================================================
    // Unchanged helpers
    // =========================================================================



    private static void setupMerchantStalls(ServerLevel level, Village village,
                                            VillageSavedData data,
                                            Map<BuildingType, List<Building>> placedBuildingsAll,
                                            Random rng) {
        net.minecraft.world.phys.AABB villageBounds = village.getBounds(data)
                .map(b -> b.inflate(32)).orElse(null);
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
                            Long.MAX_VALUE, data)
                    .ifPresent(stall -> {
                        data.addMarketStall(stall);
                        MarketStallPlacer.assignGoalIfNpc(level, stall);
                    });
        }
    }

    public static boolean isFarEnoughFromExistingVillages(ServerLevel level,
                                                          BlockPos candidate) {
        VillageSavedData data = VillageSavedData.get(level);
        for (Village v : data.getAllVillages()) {
            BlockPos anchor = v.getAnchorPos();
            if (anchor == null) continue;
            if (anchor.distSqr(candidate) < MIN_VILLAGE_DISTANCE) return false;
        }
        return true;
    }

    private static int deriveVillageLevel(VillageTypeData typeData) {
        int count = typeData.getStarterBuildings().size();
        if (count <= 3) return Math.max(1, count);
        if (count <= 6) return 3 + (count - 4);
        if (count <= 9) return 6 + (count - 7);
        return Math.min(10, 9 + (count - 10));
    }

    static BlockPos findSurface(ServerLevel level, BlockPos pos) {
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
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