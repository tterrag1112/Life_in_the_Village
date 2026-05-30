package tterrag1112.life_in_the_village.Village.Planning.V2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.CultureResolver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.NeighborColorIndex;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.TintPass;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VariantResolver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.VillagePaletteResolver;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import net.minecraft.world.item.DyeColor;
import tterrag1112.life_in_the_village.Village.Planning.BuildingFootprint;
import tterrag1112.life_in_the_village.Village.Planning.LayoutDensityProfile;
import tterrag1112.life_in_the_village.Village.Planning.LayoutSlot;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingSelector;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DependencyResolver;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DroppedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.InclinationProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.ReconciliationEngine;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.StructureAvailabilityRegistry;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.UnavailableBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.AutoDumpConfig;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.LayoutDumpSerializer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.RealizationLog;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.OverlapAuditor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.PadBuilder;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.RoadPainter;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.TerrainAdapter;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.VegetationClearer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.ViabilityValidator;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageSpawner;

import java.util.*;

/**
 * Track A1a — V2 branch of {@link VillageSpawner#spawnVillage}. Runs
 * the full V2 planner stack (Layers 1-4), executes the V2 Layer-5
 * sub-components inline so building references can be captured,
 * synthesizes a minimal {@link VillageLayout} compatible with the V1
 * downstream chain, and runs the V1 post-placement pipeline
 * (inhabitant population, decoration, trade routes, sim baseline,
 * guild bootstrap, history, initial laws).
 *
 * <p>The synthesized {@link VillageLayout} is intentionally sparse:
 * V2 doesn't produce plazas, sectors, farm plots, road graphs, or
 * gate positions, and downstream consumers must tolerate the absence.
 * Each downstream call is wrapped so a single failure does not abort
 * the whole spawn.
 *
 * <p>The {@code villageType} argument from the V1 entry point is
 * recorded on the resulting {@link Village} but is otherwise ignored
 * — V2 derives layout, building set, and viability from terrain.
 */
public final class V2VillageSpawnerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(V2VillageSpawnerAdapter.class);
    private static final int FEATURE_MAP_RADIUS = 100;
    private static final int BUILDING_LEVEL = 1;
    private static final int BUILDING_VEGETATION_BUFFER = 2;
    private static final int ROAD_VEGETATION_BUFFER = 1;
    private static final int FALLBACK_TOWN_SQUARE_RADIUS = 8;

    private V2VillageSpawnerAdapter() {}

    public static Optional<Village> spawn(ServerLevel level,
                                          BlockPos origin,
                                          String villageType,
                                          String villageName) {
        return spawn(level, origin, villageType, villageName, null, null);
    }

    /**
     * B2.8 — overload that accepts inclination + tier overrides for
     * /building village spawn's test-spawn affordances. Terrain
     * analysis still runs (so anchor / spine / primary axis stay
     * derived); only the classification fields are replaced.
     *
     * <p>Pass null for either override to keep the SiteAnalyzer
     * value. Common case: an explicit inclination + tier from the
     * command, anchored at the player's position.
     */
    public static Optional<Village> spawn(
            ServerLevel level,
            BlockPos origin,
            String villageType,
            String villageName,
            tterrag1112.life_in_the_village.Village.Planning.V2.Inclination inclinationOverride,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier tierOverride) {
        long t0 = System.currentTimeMillis();
        VillageSavedData data = VillageSavedData.get(level);

        // Track E1 follow-up — auto-dump accumulator. Every Layer 5
        // step appends; the helper at the bottom writes the JSON.
        RealizationLog realizationLog = new RealizationLog();

        if (!VillageSpawner.isFarEnoughFromExistingVillages(level, origin)) {
            LOGGER.info("V2: too close to existing village at {}", origin);
            tryAutoDumpAbort(level, origin, villageName,
                    "proximity check failed");
            return Optional.empty();
        }

        long seed = level.getSeed() ^ ((long) origin.hashCode() * 31L
                + villageName.hashCode());
        Random rng = new Random(seed);

        // ── V2 Layers 1-4 ───────────────────────────────────────────────
        V2FeatureMap fmap = V2FeatureMap.scan(level, origin, FEATURE_MAP_RADIUS);
        Culture culture = CultureRegistry.getOrDefault(CultureRegistry.DEFAULT_ID);
        // Track E1 prompt 3 fix-up — pass spawn-command overrides INTO
        // SiteAnalyzer so they apply pre-strategy-selection. Pre-
        // fix-up overrides were applied post-analysis via
        // siteCtx.withOverrides, which produced strategy ↔ network ↔
        // selection mismatches for explicit /spawn AGRICULTURAL CITY
        // (analyzer sampled CIVIC from terrain, picked civic_angerdorf,
        // planned an ANGERDORF network, THEN got the inclination
        // mutated to AGRICULTURAL — building selection ran on a
        // civic-shaped network with farm-village content).
        SiteContext siteCtx = SiteAnalyzer.analyze(fmap, culture, seed, level,
                inclinationOverride, tierOverride);
        if (siteCtx.tier() == ViabilityTier.UNVIABLE) {
            LOGGER.info("V2: site UNVIABLE at {}; aborting", origin);
            realizationLog.markAborted("tier=UNVIABLE; layers 3-4 skipped");
            // Track E1A — pass fmap too; the serializer doesn't consume
            // it directly today but doing so keeps the call symmetric
            // with later abort branches.
            tryAutoDump(level, origin, villageName, null, culture,
                    fmap, siteCtx, null, null, null, null, null,
                    java.util.Map.of(), java.util.Set.of(), realizationLog);
            return Optional.empty();
        }

        InclinationProfile profile =
                InclinationProfile.forInclination(siteCtx.inclination());
        BuildingSelector.SelectionResult sel =
                BuildingSelector.select(siteCtx, fmap, profile);
        ReconciliationEngine.ReconciliationResult recon =
                ReconciliationEngine.reconcile(sel.selected(), siteCtx.tier(),
                        culture.id(), StructureAvailabilityRegistry.INSTANCE);
        List<BuildingType> sorted =
                DependencyResolver.topoSort(recon.finalSelection(), seed);
        List<UnavailableBuilding> unavailable = sel.unavailable();
        // B2.8 — surface trade-fulfilled types so PhasedPlanner can
        // skip DEPENDENCY_MISSING drops for buildings whose supply
        // chain runs over trade routes (BLACKSMITH<-MINE,
        // BAKERY<-MILLER, etc.).
        java.util.Set<BuildingType> tradeFulfilled = new java.util.HashSet<>();
        for (var tf : recon.tradeFulfilled()) tradeFulfilled.add(tf.requiringType());

        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level, tradeFulfilled);
        PlacementResult placement = phased.placement();
        RoadNetwork roads = phased.network();

        if (!placement.villageViable() || placement.placed().isEmpty()) {
            LOGGER.info("V2: planner produced no viable village at {}"
                    + " (placed={} viable={})", origin,
                    placement.placed().size(), placement.villageViable());
            realizationLog.markAborted("planner produced no viable village ("
                    + "placed=" + placement.placed().size()
                    + " viable=" + placement.villageViable() + ")");
            tryAutoDump(level, origin, villageName, null, culture,
                    fmap, siteCtx, sel, recon, placement, roads, phased.events(),
                    phased.nucleusContexts(), phased.droppedBindings(), realizationLog);
            return Optional.empty();
        }

        // ── V2 Layer 5 sub-components, inlined to capture refs ──────────
        OverlapAuditor.OverlapReport audit =
                OverlapAuditor.audit(placement.placed(), roads);
        realizationLog.recordOverlap(audit);
        if (audit.fatal()) {
            LOGGER.info("V2: overlap audit fatal — aborting");
            realizationLog.markAborted("overlap audit fatal");
            tryAutoDump(level, origin, villageName, null, culture,
                    fmap, siteCtx, sel, recon, placement, roads, phased.events(),
                    phased.nucleusContexts(), phased.droppedBindings(), realizationLog);
            return Optional.empty();
        }

        List<TerrainAdapter.AdaptationDecision> decisions =
                TerrainAdapter.decide(placement.placed(), level);
        realizationLog.recordTerrainDecisions(decisions);
        List<PlacedBuilding> survivors = new ArrayList<>();
        List<TerrainAdapter.AdaptationDecision> survivorDecisions = new ArrayList<>();
        List<DroppedBuilding> additionalDrops = new ArrayList<>();
        for (TerrainAdapter.AdaptationDecision d : decisions) {
            switch (d.mode()) {
                case LEVEL, PLATFORM -> {
                    survivors.add(d.building());
                    survivorDecisions.add(d);
                }
                case DROP -> additionalDrops.add(new DroppedBuilding(
                        d.building().type(), DropReason.TERRAIN_UNADAPTABLE,
                        d.reason().orElse("terrain too steep")));
            }
        }

        PlacementResult postTerrain =
                rebuildPlacement(placement, survivors, additionalDrops);
        ViabilityValidator.ViabilityCheck check =
                ViabilityValidator.validate(postTerrain, siteCtx.tier());
        if (!check.viable()) {
            LOGGER.info("V2: post-terrain not viable: {}", check.failureReasons());
            realizationLog.recordViabilityFailure(check.failureReasons());
            realizationLog.markAborted("post-terrain not viable: "
                    + String.join("; ", check.failureReasons()));
            tryAutoDump(level, origin, villageName, null, culture,
                    fmap, siteCtx, sel, recon, postTerrain, roads, phased.events(),
                    phased.nucleusContexts(), phased.droppedBindings(), realizationLog);
            return Optional.empty();
        }

        // Vegetation + pads (must run before NBT placement so trees
        // don't intersect building footprints).
        for (PlacedBuilding b : survivors) {
            int cleared = VegetationClearer.clearForBuilding(level, b,
                    BUILDING_VEGETATION_BUFFER);
            realizationLog.addBuildingVegetation(cleared);
        }
        for (RoadSegment seg : roads.skeleton().allSegments()) {
            int cleared = VegetationClearer.clearForRoadSegment(level, seg,
                    ROAD_VEGETATION_BUFFER);
            realizationLog.addRoadVegetation(cleared);
        }
        for (TerrainAdapter.AdaptationDecision d : survivorDecisions) {
            PadBuilder.buildPad(level, d);
        }

        // ── Build synth VillageLayout + Village + register ──────────────
        VillageLayout synth = buildSynthLayout(level, origin, siteCtx, roads);
        Village village = new Village(villageName, villageType);
        village.applyLayout(synth, BUILDING_LEVEL);
        village.setDebugRoadGraph(synth.getRoadGraph());
        village.setMainGateEndpoint(synth.getMainGateEndpoint());
        // B2.8 — persist the V2-derived inclination so post-spawn
        // commands and reload-time reads can branch on it without
        // re-running SiteAnalyzer.
        village.setInclination(siteCtx.inclination());
        // B2.9 — persist the tier override (if the caller supplied
        // one). Without this, getSizeTier recomputes from building
        // count and reports HAMLET right after spawn even if the
        // user asked for CITY.
        if (tierOverride != null) {
            village.setSizeTierOverride(
                    tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier
                            .fromViabilityTier(tierOverride));
        }
        data.addVillage(village);
        VillageRoadsSavedData.get(level).getOrCreate(village.getId());

        // Track C2: register multi-gateway dock nodes. Reads gate
        // positions populated above by buildSynthLayout (spine
        // endpoints + cross-street outer endpoints) and creates a
        // GATEWAY node + linked TERMINUS in the world graph for each.
        // Existing single-dock saves and pre-C2 villages stay
        // single-gateway because their gatePositions are empty.
        guard("GatewayPopulator", () ->
                tterrag1112.life_in_the_village.Village.Roads.Planning
                        .GatewayPopulator.populate(level, village, synth));
        guard("InternalRoadCommitter", () ->
                tterrag1112.life_in_the_village.Village.Roads.Planning
                        .InternalRoadCommitter.commitFromV2(
                                level, village, roads));

        // B2.4 — reserve garden plots from the FeatureMap before the
        // building loop runs. Buildings can't overlap parks because
        // they were already planned in PhasedPlanner; parks fit into
        // the leftover space the planner didn't claim. Registering
        // the plots here gives the renderer (post-decoration) a
        // VillageSavedData entry to walk.
        try {
            tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier
                    sizeTier = village.getSizeTier();
            java.util.List<tterrag1112.life_in_the_village.Village.Decoration
                    .Parks.GardenPlot> plots = tterrag1112.life_in_the_village
                            .Village.Decoration.Parks.ParkCandidateFinder.find(
                            fmap,
                            placement.placed(),
                            culture,
                            siteCtx.inclination(),
                            sizeTier,
                            village.getId(),
                            seed,
                            level.getGameTime());
            for (var plot : plots) data.addGardenPlot(plot);
        } catch (Exception e) {
            LOGGER.warn("V2: ParkCandidateFinder failed for {}: {}",
                    village.getName(), e.getMessage());
        }

        // ── Place buildings, capture refs ───────────────────────────────
        Map<BuildingType, Building> placedBuildings = new LinkedHashMap<>();
        Map<BuildingType, List<Building>> placedBuildingsAll = new LinkedHashMap<>();
        EnumMap<BuildingType, Integer> typeCounters = new EnumMap<>(BuildingType.class);
        BuildingFootprint footprint = new BuildingFootprint();
        // Detour A — pair each placed farmhouse with its
        // PlacedBuilding so the post-loop FarmComplexPlanner can
        // read footprint dims + frontage direction. Captured inline
        // because the post-loop iteration over Building alone
        // doesn't retain planning-layer data.
        List<PlacedFarmhouse> placedFarmhouses = new ArrayList<>();
        // Merchant arc Phase 2a — pair each placed MARKET with its
        // PlacedBuilding so the post-loop MarketComplexPlanner can read
        // footprint dims + pad Y, mirroring the farmhouse capture.
        List<PlacedMarket> placedMarkets = new ArrayList<>();
        // A3 — neighbour-soft-exclude colour planning. Mirrors V1
        // VillageSpawner's per-village NeighborColorIndex usage.
        NeighborColorIndex neighborIndex = new NeighborColorIndex();
        VillageTypeData typeData =
                VillageTypeRegistry.INSTANCE.getType(villageType);
        int placedOk = 0, placedFail = 0;

        for (PlacedBuilding b : survivors) {
            Identifier structureId = CultureResolver.resolve(culture.id(),
                    Style.RURAL, b.type(), b.variantId(), BUILDING_LEVEL, level);
            BlockPos buildPos = centreToPivot(b);
            int idx = typeCounters.merge(b.type(), 1, Integer::sum);
            String name = villageName + "_" + b.type().name().toLowerCase()
                    + "_" + idx;

            // A3 — resolve full BuildingVariant (for tint colour-slots)
            // and plan the per-building tint via VariantResolver.
            BuildingVariant variant = VariantResolver.findById(
                    culture.id(), Style.RURAL, b.type(), b.variantId());
            Set<DyeColor> neighborColors = neighborIndex.colorsWithin(
                    b.centre(), VillagePaletteResolver.NEIGHBOUR_RADIUS);
            // B1 (P0a-12) — guild-hall identity tint. The actual
            // AbstractGuild for this village doesn't exist yet
            // (GuildBootstrap runs in runDownstream below), so the
            // type's default palette stands in. Per-instance overrides
            // would only diverge from this default for rebuilt halls,
            // which the V2 spawn path doesn't generate.
            tterrag1112.life_in_the_village.Guilds.Common.GuildPalette guildPalette =
                    tterrag1112.life_in_the_village.Guilds.Common.GuildHallTypes
                                    .isGuildHall(b.type())
                            ? tterrag1112.life_in_the_village.Guilds.Common
                                    .GuildPalettes.forType(
                                            tterrag1112.life_in_the_village.Guilds.Common
                                                    .GuildHallTypes.guildTypeForHall(b.type()))
                            : tterrag1112.life_in_the_village.Guilds.Common
                                    .GuildPalette.NONE;
            TintPass.Plan tintPlan = VariantResolver.planTint(
                    typeData, variant, rng, neighborColors, guildPalette);

            try {
                Optional<Building> placedOpt = BuildingPlacer.placeAndRegister(
                        level, buildPos, structureId, name, b.type(),
                        b.rotation(), b.variantId(), tintPlan);
                if (placedOpt.isEmpty()) {
                    placedFail++;
                    realizationLog.recordPlacementError(b.type(), b.variantId(),
                            "BuildingPlacer returned empty");
                    continue;
                }

                Building placedBuilding = placedOpt.get();
                village.addBuilding(placedBuilding);
                placedBuildings.putIfAbsent(b.type(), placedBuilding);
                placedBuildingsAll.computeIfAbsent(b.type(),
                        k -> new ArrayList<>()).add(placedBuilding);
                if (b.type() == BuildingType.FARMHOUSE) {
                    placedFarmhouses.add(new PlacedFarmhouse(placedBuilding, b));
                }
                if (b.type() == BuildingType.MARKET) {
                    placedMarkets.add(new PlacedMarket(placedBuilding, b));
                }
                footprint.occupyBuilding(placedBuilding,
                        BuildingFootprint.DEFAULT_BUFFER);
                neighborIndex.add(b.centre(), placedBuilding.getPrimaryColor());

                // B2.1 — materialise the planned adjunct (if any) into
                // a persisted AdjunctPlot now that the parent's UUID
                // exists. Pre-B2.1 this happened in
                // AdjunctPlotPlacer.tryPlace as a post-spawn probe; the
                // probe is gone — V2 Layer 4 already validated the
                // rectangle and the realiser runs as a pure renderer.
                if (b.adjunct() != null) {
                    var planned = b.adjunct();
                    var plot = new tterrag1112.life_in_the_village.Village.Decoration
                            .Adjunct.AdjunctPlot(
                                    java.util.UUID.randomUUID(),
                                    placedBuilding.getId(),
                                    planned.type(),
                                    planned.origin(),
                                    planned.halfWidthX(),
                                    planned.halfLengthZ(),
                                    planned.facingFromParent(),
                                    placedBuilding.getRotation());
                    data.addAdjunctPlot(plot);
                }

                LayoutSlot slot = synthSlot(b, structureId);
                synth.addForced(slot);

                placedOk++;
            } catch (Exception e) {
                LOGGER.warn("V2: place failed for {} at {}: {}",
                        b.type(), buildPos, e.getMessage());
                placedFail++;
                realizationLog.recordPlacementError(b.type(), b.variantId(),
                        e.getClass().getSimpleName() + ": "
                                + (e.getMessage() == null ? "?" : e.getMessage()));
            }
        }

        if (placedBuildings.isEmpty()) {
            LOGGER.info("V2: no buildings placed at {}; aborting", origin);
            realizationLog.markAborted("no buildings placed (NBT placement failed for all "
                    + placedFail + " survivors)");
            tryAutoDump(level, origin, villageName,
                    village != null && village.getId() != null
                            ? village.getId().toString() : null,
                    culture, fmap, siteCtx, sel, recon, placement, roads,
                    phased.events(), phased.nucleusContexts(),
                    phased.droppedBindings(), realizationLog);
            return Optional.empty();
        }
        data.setDirty();

        // Roads.
        try {
            RoadPainter.paintAll(level, roads, culture);
        } catch (Exception e) {
            LOGGER.warn("V2: RoadPainter failed: {}", e.getMessage());
        }

        // Detour A — one FarmComplex per placed farmhouse. Runs
        // post-loop so each farmhouse's Building UUID exists.
        // Prompt B Stage A: park-polygon exclusion. The parks
        // reserved by ParkCandidateFinder earlier in this spawn
        // get converted to Polygons here and fed to every per-
        // farmhouse FarmComplexPlanner call.
        java.util.List<tterrag1112.life_in_the_village.Utilities.Geometry.Polygon>
                parkExclusionPolygons = collectParkExclusions(data, village.getId());
        for (PlacedFarmhouse fh : placedFarmhouses) {
            try {
                Direction extendsToward =
                        toDirection(fh.placed().frontage()).getOpposite();
                int halfX = Math.max(1, fh.placed().footprint().width() / 2);
                int halfZ = Math.max(1, fh.placed().footprint().length() / 2);
                long perFhSeed = seed
                        + fh.building().getId().getMostSignificantBits()
                        ^ fh.building().getId().getLeastSignificantBits();
                // TEST SCAFFOLD (PERIMETER_OFFSET_COMPLEXES): move the
                // complex seed out past every building so it lands clear of
                // park/building reservations instead of starving in place.
                BlockPos farmSeed = PERIMETER_OFFSET_COMPLEXES
                        ? perimeterAnchor(origin, fh.placed().centre(), placedBuildingsAll)
                        : fh.placed().centre();
                var planInput = new tterrag1112.life_in_the_village.Village.Farms
                        .Complex.FarmComplexPlanner.Input(
                        farmSeed,
                        extendsToward,
                        halfX,
                        halfZ,
                        village.getId(),
                        fh.building().getId(),
                        culture.id(),
                        BuildingType.FARMHOUSE,
                        fmap,
                        /* biomeCheck */ null,
                        perFhSeed,
                        parkExclusionPolygons,
                        /* verbose */ false,
                        /* namePrefix */ village.getName() + "_complex");
                var result = tterrag1112.life_in_the_village.Village.Farms
                        .Complex.FarmComplexPlanner.planAndPersist(planInput, data);
                if (!result.success()) {
                    LOGGER.info("V2: farm complex skipped for {} ({}): {}",
                            fh.building().getName(), result.status(), result.detail());
                    continue;
                }
                // Phase 6.7.2.2 — seed sheep rosters for ANIMAL_PEN
                // plots produced by this complex generation.
                tterrag1112.life_in_the_village.Village.Roster.AnimalRosterSeeder
                        .seedFor(level, result.complex(), result.newPlots());
                // Prompt B Stage G — render the just-planned complex.
                // Plots are returned in result.newPlots(); we also
                // query the persisted store for safety (handles a
                // future case where addFarmPlot might enrich the
                // record). render() is fully defensive against null
                // / partial data.
                var rendered = result.complex();
                var plots = data.getFarmPlotsForFarmhouse(fh.building().getId());
                tterrag1112.life_in_the_village.Village.Farms.Complex.Render
                        .FarmComplexRenderer.render(rendered, plots,
                                culture.id(), level);
            } catch (Exception e) {
                LOGGER.warn("V2: FarmComplex plan/render failed for {}: {}",
                        fh.building().getName(), e.getMessage());
            }
        }

        // Merchant arc Phase 2a — one market complex (bounded prepared
        // pad) per placed MARKET. Runs after the farm loop so farm
        // complex regions exist and can be fed as collision obstacles.
        // Each market is guarded; a failure logs and continues so one bad
        // market never aborts the spawn. No stalls yet (2b).
        for (PlacedMarket mk : placedMarkets) {
            try {
                int padY = mk.placed().centre().getY();
                // TEST SCAFFOLD (PERIMETER_OFFSET_COMPLEXES): move the pad
                // centre out past every building so a full-margin pad fits
                // in open terrain instead of failing NO_REGION in the dense
                // core. perimeterAnchor keeps the building's Y (= padY).
                BlockPos marketCentre = PERIMETER_OFFSET_COMPLEXES
                        ? perimeterAnchor(origin, mk.placed().centre(), placedBuildingsAll)
                        : mk.placed().centre();
                // Obstacles: every other placed building's footprint AABB,
                // plus existing farm complex regions. The market never
                // grades over a neighbour — it shrinks or skips.
                java.util.List<tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB>
                        obstacles = new ArrayList<>();
                for (var entry : placedBuildingsAll.entrySet()) {
                    for (Building other : entry.getValue()) {
                        if (other.getId().equals(mk.building().getId())) continue;
                        obstacles.add(toRect(other.getShape().toAABB()));
                    }
                }
                for (var fc : data.getFarmComplexesForVillage(village.getId())) {
                    if (fc.region() != null) {
                        obstacles.add(tterrag1112.life_in_the_village.Utilities.Geometry
                                .Polygon.boundingBox(fc.region()));
                    }
                }
                var planInput = new tterrag1112.life_in_the_village.Village.Markets
                        .Complex.MarketComplexPlanner.Input(
                        marketCentre,
                        mk.placed().footprint().width(),
                        mk.placed().footprint().length(),
                        padY,
                        culture.id(),
                        BuildingType.MARKET,
                        obstacles);
                var result = tterrag1112.life_in_the_village.Village.Markets
                        .Complex.MarketComplexPlanner.plan(planInput);
                if (!result.success()) {
                    LOGGER.info("V2: market pad skipped for {} ({}): {}",
                            mk.building().getName(), result.status(), result.detail());
                    continue;
                }
                tterrag1112.life_in_the_village.Village.Markets.Complex.Render
                        .MarketComplexRenderer.render(result, culture.id(), level);
                LOGGER.info("V2: market pad rendered for {} (margin={})",
                        mk.building().getName(), result.chosenMargin());
                // Phase 2b — seed vacant stalls onto the just-graded pad
                // via the allocator (region geometry is in hand here).
                tterrag1112.life_in_the_village.Village.Buildings.Complex
                        .MarketComplexRegistry.get(culture.id(), BuildingType.MARKET)
                        .ifPresent(spec ->
                                tterrag1112.life_in_the_village.Village.Markets.Complex
                                        .MarketStallSeeder.seed(level, data, mk.building(),
                                                result.region(), result.buildingBounds(),
                                                result.padY(), spec));
            } catch (Exception e) {
                LOGGER.warn("V2: MarketComplex plan/render failed for {}: {}",
                        mk.building().getName(), e.getMessage());
            }
        }

        // ── V1 downstream, each section guarded ─────────────────────────
        runDownstream(level, village, data, synth, footprint,
                placedBuildingsAll, rng);

        long elapsed = System.currentTimeMillis() - t0;
        LOGGER.info("V2: spawned '{}' tier={} placed={} fail={} drops={} elapsed={}ms",
                villageName, siteCtx.tier(), placedOk, placedFail,
                additionalDrops.size(), elapsed);
        // Track E1 follow-up — auto-dump on successful spawn.
        tryAutoDump(level, origin, villageName,
                village.getId() != null ? village.getId().toString() : null,
                culture, fmap, siteCtx, sel, recon, placement, roads,
                phased.events(), phased.nucleusContexts(),
                phased.droppedBindings(), realizationLog);
        return Optional.of(village);
    }

    // =========================================================================
    // Track E1 follow-up — auto-dump helpers. Never throw upward.
    // =========================================================================

    private static void tryAutoDump(
            ServerLevel level, BlockPos origin, String villageName, String villageId,
            Culture culture, V2FeatureMap fmap, SiteContext siteCtx,
            BuildingSelector.SelectionResult sel,
            ReconciliationEngine.ReconciliationResult recon,
            PlacementResult placement, RoadNetwork roads,
            java.util.List<PhasedPlanner.PhaseEvent> events,
            java.util.Map<PlacedBuilding, PhasedPlanner.NucleusContext> nucleusContexts,
            java.util.Set<tterrag1112.life_in_the_village.Village.Buildings.BuildingType>
                    droppedBindings,
            RealizationLog log) {
        if (!AutoDumpConfig.isEnabled()) return;
        try {
            long tick = level.getGameTime();
            var json = LayoutDumpSerializer.serializeAuto(level, origin, tick,
                    villageName, villageId, culture, fmap, siteCtx, sel, recon,
                    placement, roads, events, nucleusContexts, droppedBindings, log);
            String slug = villageName != null ? villageName
                    : ("auto_" + origin.getX() + "_" + origin.getZ());
            // Track E1 anchor detection — INFO summary of anchor counts.
            if (siteCtx != null) {
                LOGGER.info("V2: {}",
                        LayoutDumpSerializer.anchorSummary(siteCtx.anchors()));
                // Track E1B — strategy summary.
                if (siteCtx.strategy() != null) {
                    var s = siteCtx.strategy();
                    LOGGER.info(
                            "V2 strategy: {} ({}) score={} primary={}",
                            s.strategy().id(), s.strategy().topology(),
                            String.format(java.util.Locale.ROOT, "%.1f", s.score()),
                            s.primaryAnchor() != null
                                    ? s.primaryAnchor().id() : "none");
                }
            }
            // Track E1A — on aborts, surface which layers had
            // completed before the abort so dump consumers can read
            // the partial state correctly.
            if (log != null && log.aborted()) {
                LOGGER.info(
                        "V2: {} — layers complete: fmap={} siteCtx={} anchors={} "
                                + "selection={} placement={} roads={}",
                        log.abortReason().orElse("aborted"),
                        fmap != null,
                        siteCtx != null,
                        siteCtx != null ? siteCtx.anchors().size() : 0,
                        sel != null,
                        placement != null,
                        roads != null);
            }
            LayoutDumpSerializer.writeDump(level, slug, tick, json)
                    .ifPresent(path -> LOGGER.info(
                            "V2: auto-dumped layout to {}", path.toAbsolutePath()));
        } catch (Throwable t) {
            LOGGER.warn("V2: auto-dump failed: {}", t.getMessage());
        }
    }

    /** Minimal-state abort dump for the proximity-check branch. */
    private static void tryAutoDumpAbort(ServerLevel level, BlockPos origin,
                                          String villageName, String reason) {
        if (!AutoDumpConfig.isEnabled()) return;
        try {
            long tick = level.getGameTime();
            var json = LayoutDumpSerializer.serializeAbort(level, origin, tick,
                    villageName, reason);
            String slug = villageName != null ? villageName
                    : ("auto_" + origin.getX() + "_" + origin.getZ());
            LayoutDumpSerializer.writeDump(level, slug, tick, json)
                    .ifPresent(path -> LOGGER.info(
                            "V2: auto-dumped abort to {}", path.toAbsolutePath()));
        } catch (Throwable t) {
            LOGGER.warn("V2: auto-dump abort failed: {}", t.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static PlacementResult rebuildPlacement(PlacementResult original,
                                                    List<PlacedBuilding> survivors,
                                                    List<DroppedBuilding> additionalDrops) {
        EnumMap<BuildingType, Integer> counts = new EnumMap<>(BuildingType.class);
        for (PlacedBuilding pb : survivors) counts.merge(pb.type(), 1, Integer::sum);
        List<DroppedBuilding> mergedDropped =
                new ArrayList<>(original.dropped().size() + additionalDrops.size());
        mergedDropped.addAll(original.dropped());
        mergedDropped.addAll(additionalDrops);
        return new PlacementResult(List.copyOf(survivors), List.copyOf(mergedDropped),
                original.unavailable(), Map.copyOf(counts), original.villageViable());
    }

    /**
     * Builds the synthesized {@link VillageLayout} V1 downstream
     * consumers see. Center / town-square position is the V2 anchor;
     * main-gate endpoint is the V2 spine end. Plaza, sectors, farm
     * plots, gate positions, and the V1 RoadGraph stay at their
     * defaults (empty / null) — downstream calls are guarded
     * individually below.
     */
    private static VillageLayout buildSynthLayout(ServerLevel level,
                                                  BlockPos origin,
                                                  SiteContext siteCtx,
                                                  RoadNetwork roads) {
        TerrainProfile terrain = TerrainAnalyzer.analyze(level, origin);
        LayoutDensityProfile density = LayoutDensityProfile.forLevel(BUILDING_LEVEL);
        VillageLayout layout = new VillageLayout(terrain, density);
        layout.setCenter(siteCtx.anchor());
        layout.setTownSquarePos(siteCtx.anchor());
        layout.setTownSquareRadius(FALLBACK_TOWN_SQUARE_RADIUS);
        BlockPos gate = roads.skeleton().spineEnd();
        if (gate != null) layout.setMainGateEndpoint(gate);

        // Track C2: expose the V2 spine endpoints + cross-street outer
        // endpoints as gate positions so GatewayPopulator can register
        // multiple GATEWAY nodes (one per arm) per village. The PRIMARY
        // role goes to mainGateEndpoint (= spineEnd) per Slice 2's
        // convention; remaining gate positions become SIDE.
        // GatewayPopulator's deriveDescriptors only scans gatePositions
        // (it falls back to mainGateEndpoint only when that list is
        // empty), so spineEnd is added here too even though it's also
        // the mainGateEndpoint — equality with mainGate flags it
        // PRIMARY inside the loop.
        if (gate != null) layout.addGatePosition(gate);
        BlockPos spineStart = roads.skeleton().spineStart();
        if (spineStart != null && !spineStart.equals(gate)) {
            layout.addGatePosition(spineStart);
        }
        for (var cs : roads.skeleton().crossStreets()) {
            // Each cross street contributes its two outer endpoints
            // (the spine junction is interior, not a gateway).
            if (cs.start() != null) layout.addGatePosition(cs.start());
            if (cs.end()   != null) layout.addGatePosition(cs.end());
        }
        return layout;
    }

    /**
     * Synthesizes a {@link LayoutSlot} from a V2 {@link
     * PlacedBuilding}. Footprint dimensions come from the V2 record;
     * structure path is null because V2 doesn't produce one (V1
     * downstream tolerates null via {@code CultureResolver
     * .parseLegacyTypeLevel} returning null and defaulting to
     * level=1).
     */
    private static LayoutSlot synthSlot(PlacedBuilding b, Identifier structureId) {
        Footprint fp = b.footprint();
        int rawW = fp.width();
        int rawL = fp.length();
        int rotW = rawW, rotL = rawL;
        Rotation rot = b.rotation();
        if (rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90) {
            rotW = rawL;
            rotL = rawW;
        }
        int radius = Math.max(rotW, rotL) / 2;
        LayoutSlot slot = new LayoutSlot(b.centre(), b.type(),
                structureId != null ? structureId.toString() : null,
                radius, rot);
        slot.setFootprint(rotW, rotL);
        slot.setVariantId(b.variantId());
        slot.setStyle(Style.RURAL);
        slot.setPadY(b.centre().getY());
        return slot;
    }

    /**
     * Mirrors {@code MinimalSpawner.centreToPlaceArg}: convert V2's
     * centre-based placement to the pivot {@link
     * BuildingPlacer#placeAndRegister} expects, with +1 Y so the
     * structure sits on the ground rather than replacing it.
     */
    private static BlockPos centreToPivot(PlacedBuilding pb) {
        Footprint fp = pb.footprint();
        int rawW = fp.width();
        int rawL = fp.length();
        Rotation rot = pb.rotation();
        boolean swap = rot == Rotation.CLOCKWISE_90
                || rot == Rotation.COUNTERCLOCKWISE_90;
        int rotW = swap ? rawL : rawW;
        int rotL = swap ? rawW : rawL;
        int halfW = rotW / 2;
        int halfL = rotL / 2;
        BlockPos centre = pb.centre();
        int placementY = centre.getY() + 1;
        BlockPos targetNW = new BlockPos(centre.getX() - halfW, placementY,
                centre.getZ() - halfL);
        return switch (rot) {
            case CLOCKWISE_90 -> targetNW.offset(rawL - 1, 0, 0);
            case COUNTERCLOCKWISE_90 -> targetNW.offset(0, 0, rawW - 1);
            case CLOCKWISE_180 -> targetNW.offset(rawW - 1, 0, rawL - 1);
            default -> targetNW;
        };
    }

    /**
     * Runs the V1 post-placement pipeline. Each section is wrapped
     * because the synth {@link VillageLayout} omits state (plazas,
     * V1 road graph, sectors, farm plots, features) that some
     * consumers expect. Failure of one section logs and continues;
     * the goal is "downstream runs without aborting the spawn,"
     * not feature parity.
     */
    private static void runDownstream(ServerLevel level,
                                      Village village,
                                      VillageSavedData data,
                                      VillageLayout synth,
                                      BuildingFootprint footprint,
                                      Map<BuildingType, List<Building>> placedBuildingsAll,
                                      Random rng) {
        // Detour A — block-level rendering of FarmComplex (paths,
        // borders, farmland stamps, tool shed) is Prompt B's
        // concern. Between Stage 5 and Prompt B's ship there is no
        // block-level rendering pass; planned complexes persist to
        // SavedData and surface via /litv farms but don't draw.
        guard("VillageInhabitantPopulator", () ->
                tterrag1112.life_in_the_village.Village.Buildings.Inhabitants
                        .VillageInhabitantPopulator.populate(level, village, data,
                                placedBuildingsAll, rng));
        guard("VillageDecorator", () ->
                tterrag1112.life_in_the_village.Village.Decoration
                        .VillageDecorator.decorateVillage(level, village, data,
                                synth, footprint));
        guard("AdjunctPlotRealiser", () ->
                tterrag1112.life_in_the_village.Village.Decoration.Adjunct
                        .AdjunctPlotRealiser.run(level, village, data));
        guard("DecorationPass", () ->
                tterrag1112.life_in_the_village.Village.Decoration.Framework
                        .DecorationPass.run(level, village, data, synth));
        guard("ParkRenderer", () ->
                tterrag1112.life_in_the_village.Village.Decoration.Parks
                        .ParkRenderer.run(level, village, data));
        guard("TradeRouteManager", () ->
                tterrag1112.life_in_the_village.Village.Economy.Trade
                        .TradeRouteManager.establishRoutes(level, village, data));
        if (village.useGraphConnector()) {
            guard("ConnectorPlanner", () -> {
                WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
                tterrag1112.life_in_the_village.Village.Roads.Planning
                        .ConnectorPlanner.planConnector(level,
                                roadData.getGraph(), data, village,
                                tterrag1112.life_in_the_village.Village.Roads.Planning
                                        .ConnectorPlanner.DEFAULT_SEARCH_RADIUS);
            });
        }
        guard("VillageSimEngine", () ->
                tterrag1112.life_in_the_village.Village.Simulation
                        .VillageSimEngine.buildBaseline(village, data,
                                level.getGameTime()));

        long now = level.getGameTime();
        guard("GuildBootstrap.scanAndCreateImplicit", () ->
                tterrag1112.life_in_the_village.Guilds.Common
                        .GuildBootstrap.scanAndCreateImplicit(level, village, data, now));
        guard("GuildBootstrap.onHallConstructed", () -> {
            for (var bid : village.getBuildingIds()) {
                data.getBuildingById(bid).ifPresent(b -> {
                    if (tterrag1112.life_in_the_village.Guilds.Common
                            .GuildHallTypes.isGuildHall(b.getType())) {
                        tterrag1112.life_in_the_village.Guilds.Common
                                .GuildBootstrap.onHallConstructed(level, b, village, now);
                    }
                });
            }
        });
        guard("HistoryProducer.VILLAGE_FOUNDED", () -> {
            Map<String, String> founding = new LinkedHashMap<>();
            founding.put("village_name", village.getName());
            tterrag1112.life_in_the_village.Village.History.HistoryProducer.record(
                    level, village,
                    tterrag1112.life_in_the_village.Village.History.HistoryEventType
                            .VILLAGE_FOUNDED,
                    now, founding, List.of());
        });
        guard("InitialLaws", () -> {
            var villageCulture = tterrag1112.life_in_the_village.Cultures
                    .CultureResolver.of(level, village);
            for (String lawName : villageCulture.lawDefaults().initialLaws()) {
                try {
                    var law = tterrag1112.life_in_the_village.Npc.Laws.VillageLaw
                            .valueOf(lawName);
                    tterrag1112.life_in_the_village.Npc.Laws.LawEnactment.enact(
                            village, law, null, level, null);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("V2: unknown initial law '{}'", lawName);
                }
            }
        });
    }

    /** Detour A — pairs a placed farmhouse Building (UUID-bearing,
     *  persisted) with its planning-layer PlacedBuilding (footprint
     *  dims + frontage). Both are needed downstream by
     *  FarmComplexPlanner: the Building for ids, the PlacedBuilding
     *  for geometry. */
    private record PlacedFarmhouse(Building building, PlacedBuilding placed) {}

    /** Merchant arc Phase 2a — placed MARKET paired with its planning
     *  PlacedBuilding (footprint + centre), mirroring PlacedFarmhouse. */
    private record PlacedMarket(Building building, PlacedBuilding placed) {}

    /**
     * TEST SCAFFOLD (PERIMETER_OFFSET_COMPLEXES) — compute an outward
     * anchor for a complex in open terrain. Direction is village centre →
     * building; distance is the furthest placed building from the village
     * centre, plus {@link #PERIMETER_OFFSET_CLEARANCE}, so the anchor sits
     * clear of every building (and the park reservations, which hug the
     * buildings). Y is the building's own Y (superflat-safe). Falls back
     * to the building centre if the building sits exactly on the village
     * centre (degenerate direction). Remove with the flag when the
     * ComplexRegion plan-time reservation lands (layout rework).
     */
    private static BlockPos perimeterAnchor(BlockPos villageCentre,
                                            BlockPos buildingCentre,
                                            Map<BuildingType, List<Building>> placedBuildingsAll) {
        double dx = buildingCentre.getX() - villageCentre.getX();
        double dz = buildingCentre.getZ() - villageCentre.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-3) return buildingCentre; // degenerate: no outward dir
        double ux = dx / len;
        double uz = dz / len;

        // Furthest placed building from the village centre (XZ).
        double furthest = 0.0;
        for (List<Building> list : placedBuildingsAll.values()) {
            for (Building b : list) {
                BlockPos o = b.getShape().getOrigin();
                double bx = o.getX() - villageCentre.getX();
                double bz = o.getZ() - villageCentre.getZ();
                furthest = Math.max(furthest, Math.sqrt(bx * bx + bz * bz));
            }
        }
        double dist = furthest + PERIMETER_OFFSET_CLEARANCE;
        int ax = villageCentre.getX() + (int) Math.round(ux * dist);
        int az = villageCentre.getZ() + (int) Math.round(uz * dist);
        return new BlockPos(ax, buildingCentre.getY(), az);
    }

    /** Merchant arc Phase 2a — convert a building's world AABB to the
     *  XZ rectangle the MarketComplexPlanner collision-checks against.
     *  maxX/maxZ are exclusive in {@code net.minecraft...AABB}; floor/
     *  ceil keeps the integer rect inclusive of every occupied block. */
    private static tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB toRect(
            net.minecraft.world.phys.AABB box) {
        return new tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB(
                (int) Math.floor(box.minX), (int) Math.floor(box.minZ),
                (int) Math.ceil(box.maxX), (int) Math.ceil(box.maxZ));
    }

    /** Detour A — Prompt B Stage A. Collect every reserved park in
     *  this village as a polygon for FarmComplexPlanner's exclusion
     *  set. GardenPlot persists rectangular Bounds; the conversion
     *  is a 4-vertex rectangular Polygon. Y is fixed at the bounds'
     *  approximate ground level (the polygon CONTAINS test is XZ-
     *  only, so Y is decorative). */
    private static java.util.List<tterrag1112.life_in_the_village.Utilities.Geometry.Polygon>
            collectParkExclusions(VillageSavedData data, java.util.UUID villageId) {
        var parks = data.getGardenPlotsForVillage(villageId);
        if (parks == null || parks.isEmpty()) return java.util.List.of();
        java.util.List<tterrag1112.life_in_the_village.Utilities.Geometry.Polygon> out =
                new ArrayList<>(parks.size());
        for (var park : parks) {
            var b = park.bounds();
            // Inflate by 1 block per side so a complex doesn't
            // claim cells flush against the park boundary — small
            // breathing room reads better visually.
            int minX = b.minX() - 1, maxX = b.maxX() + 1;
            int minZ = b.minZ() - 1, maxZ = b.maxZ() + 1;
            var verts = java.util.List.of(
                    new BlockPos(minX, 0, minZ),
                    new BlockPos(maxX, 0, minZ),
                    new BlockPos(maxX, 0, maxZ),
                    new BlockPos(minX, 0, maxZ));
            out.add(new tterrag1112.life_in_the_village.Utilities.Geometry.Polygon(verts));
        }
        return out;
    }

    /** Snap a {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.FrontageStrip}'s
     *  outward unit vector to a cardinal {@link Direction}. The
     *  frontage's {@code frontDirection} is already cardinal-aligned
     *  per the record's contract; this is just the type conversion. */
    private static Direction toDirection(
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.FrontageStrip f) {
        if (f == null) return Direction.SOUTH;
        var v = f.frontDirection();
        // 1.21 dropped the (double,double,double) overload; use the
        // int-coord variant with an explicit fallback. The frontage
        // direction is already cardinal-snapped so casts are lossless.
        return Direction.getNearest(
                (int) Math.signum(v.x), 0, (int) Math.signum(v.z),
                Direction.SOUTH);
    }

    private static void guard(String label, Runnable r) {
        try {
            r.run();
        } catch (Exception | LinkageError e) {
            LOGGER.warn("V2 downstream '{}' failed: {}", label, e.toString());
        }
    }
}
