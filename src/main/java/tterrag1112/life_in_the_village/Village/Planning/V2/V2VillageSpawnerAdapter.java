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
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.Gateways;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkNode;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NodeKind;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.AutoDumpConfig;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.LayoutDumpSerializer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Debug.RealizationLog;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.OverlapAuditor;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.PadBuilder;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.VillageRoadRealizer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.TerrainAdapter;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.VegetationClearer;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer5.ViabilityValidator;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageSpawner;

import java.util.*;

/**
 * Track A1a — V2 branch of {@link VillageSpawner#spawnVillage}. Runs
 * the full V2 planner stack (Layers 1-4), executes the V2 Layer-5
 * sub-components inline so building references can be captured, builds
 * a V2-native {@link RealizedLayout} for the post-placement consumers,
 * and runs the post-placement pipeline (inhabitant population,
 * decoration, trade routes, sim baseline, guild bootstrap, history,
 * initial laws).
 *
 * <p>The {@link RealizedLayout} carries exactly the live consumer
 * surface (center / town square / gate positions / ring radii / road
 * network). Each downstream call is wrapped so a single failure does
 * not abort the whole spawn.
 *
 * <p>The {@code villageType} argument from the V1 entry point is
 * recorded on the resulting {@link Village} but is otherwise ignored
 * — V2 derives layout, building set, and viability from terrain.
 */
public final class V2VillageSpawnerAdapter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(V2VillageSpawnerAdapter.class);
    // 4c-a fix-up — raised 100 → 150 to cover the relaxed CITY extent (120) ×
    // the rural zone factor (~127) plus a building/footprint margin, so buildings
    // + farms never plan onto un-scanned terrain at the larger radius. Scan cost
    // is ~quadratic (≈2.25× the old grid → ~2× spawn time at CITY; acceptable).
    private static final int FEATURE_MAP_RADIUS = 150;
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
        return spawn(level, origin, villageType, villageName,
                inclinationOverride, tierOverride, null);
    }

    /**
     * District-tooling seam — overload that accepts a FORCED selection
     * (building type → count), bypassing {@link BuildingSelector} +
     * {@link ReconciliationEngine} so a caller can drive the REAL spawn
     * pipeline (planner → placer → downstream render) with an exact roster.
     * Used by {@code /litv district} to render a residential district at a
     * chosen house count. Pass {@code null} for the production path —
     * behaviour is then byte-identical to before this seam (the only change is
     * the {@code if (selectionOverride != null)} branch around selection).
     *
     * <p>The override trusts the caller's types are NBT-available (no
     * availability/trade reconciliation runs); the DISTRICT_ONLY filter in
     * {@code PhasedPlanner.run} still applies, and dependency topo-sort still
     * runs over the forced list.
     */
    public static Optional<Village> spawn(
            ServerLevel level,
            BlockPos origin,
            String villageType,
            String villageName,
            tterrag1112.life_in_the_village.Village.Planning.V2.Inclination inclinationOverride,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier tierOverride,
            Map<BuildingType, Integer> selectionOverride) {
        return spawn(level, origin, villageType, villageName, inclinationOverride,
                tierOverride, selectionOverride, null);
    }

    /**
     * Residential-variant tooling — overload carrying an optional FORCED
     * residential variant (from {@code /litv district}); threaded to
     * {@link PhasedPlanner#run}. Null → auto-select (production passes null).
     */
    public static Optional<Village> spawn(
            ServerLevel level,
            BlockPos origin,
            String villageType,
            String villageName,
            tterrag1112.life_in_the_village.Village.Planning.V2.Inclination inclinationOverride,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier tierOverride,
            Map<BuildingType, Integer> selectionOverride,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.ResidentialVariant
                    forcedResidentialVariant) {
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

        // Selection — production path runs BuildingSelector + reconcile; the
        // district-tooling override forces an exact roster (sel/recon stay null,
        // which tryAutoDump tolerates — see the UNVIABLE abort above).
        BuildingSelector.SelectionResult sel;
        ReconciliationEngine.ReconciliationResult recon;
        List<BuildingType> sorted;
        List<UnavailableBuilding> unavailable;
        // B2.8 — trade-fulfilled types so PhasedPlanner skips DEPENDENCY_MISSING
        // for buildings whose supply chain runs over trade routes.
        java.util.Set<BuildingType> tradeFulfilled = new java.util.HashSet<>();
        if (selectionOverride != null && !selectionOverride.isEmpty()) {
            sel = null;
            recon = null;
            List<BuildingType> forced = new ArrayList<>();
            for (var e : selectionOverride.entrySet()) {
                for (int i = 0; i < Math.max(0, e.getValue()); i++) forced.add(e.getKey());
            }
            sorted = DependencyResolver.topoSort(forced, seed);
            unavailable = List.of();
            LOGGER.info("V2: selection OVERRIDE ({}) — BuildingSelector + reconcile bypassed",
                    selectionOverride);
        } else {
            InclinationProfile profile =
                    InclinationProfile.forInclination(siteCtx.inclination());
            sel = BuildingSelector.select(siteCtx, fmap, profile);
            recon = ReconciliationEngine.reconcile(sel.selected(), siteCtx.tier(),
                    culture.id(), StructureAvailabilityRegistry.INSTANCE);
            sorted = DependencyResolver.topoSort(recon.finalSelection(), seed);
            unavailable = sel.unavailable();
            for (var tf : recon.tradeFulfilled()) tradeFulfilled.add(tf.requiringType());
        }

        PhasedPlanner.Result phased =
                PhasedPlanner.run(siteCtx, fmap, sorted, unavailable, level,
                        tradeFulfilled, forcedResidentialVariant);
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
            // Stage 4b fix-up — district-only dev mode legitimately produces a
            // "partial" village (e.g. CITY can't reach the 6-type diversity
            // minimum with only the 5 district types). Don't abort while the
            // flag is on; log and proceed so the districted work is visible.
            if (PhasedPlanner.DISTRICT_ONLY_MODE) {
                LOGGER.info("V2: post-terrain not viable {} — proceeding"
                        + " (DISTRICT_ONLY_MODE: partial village expected)",
                        check.failureReasons());
            } else {
                LOGGER.info("V2: post-terrain not viable: {}", check.failureReasons());
                realizationLog.recordViabilityFailure(check.failureReasons());
                realizationLog.markAborted("post-terrain not viable: "
                        + String.join("; ", check.failureReasons()));
                tryAutoDump(level, origin, villageName, null, culture,
                        fmap, siteCtx, sel, recon, postTerrain, roads, phased.events(),
                        phased.nucleusContexts(), phased.droppedBindings(), realizationLog);
                return Optional.empty();
            }
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

        // ── Build RealizedLayout + Village + register ───────────────────
        RealizedLayout realized = buildRealizedLayout(siteCtx, roads,
                phased.civicSquare(), phased.marketSquare());
        Village village = new Village(villageName, villageType);
        // applyLayout sets center / town square / ring radii / gate
        // positions and the main-gate endpoint from the realised layout.
        village.applyLayout(realized, BUILDING_LEVEL);
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

        // Track C2: register multi-gateway dock nodes. Reads the gate
        // positions built into the RealizedLayout (spine endpoints +
        // cross-street outer endpoints) and creates a GATEWAY node +
        // linked TERMINUS in the world graph for each. Existing
        // single-dock saves and pre-C2 villages stay single-gateway
        // because their gatePositions are empty.
        guard("GatewayPopulator", () ->
                tterrag1112.life_in_the_village.Village.Roads.Planning
                        .GatewayPopulator.populate(level, village, realized));
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
            // Part 2a — the residential band fills its own leftover with
            // green-commons (below), so SKIP village-wide parks inside the band
            // (de-dup); parks elsewhere (rural fringe, etc.) still register.
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.ResidentialBand
                    band = phased.residentialBand();
            for (var plot : plots) {
                if (band != null) {
                    int px = (plot.bounds().minX() + plot.bounds().maxX()) / 2;
                    int pz = (plot.bounds().minZ() + plot.bounds().maxZ()) / 2;
                    if (band.withinOuter(px, pz)) continue;
                }
                data.addGardenPlot(plot);
            }
            // Green-commons subdistricts: render each band fill block as a
            // COTTAGE_GREEN GardenPlot (feature-independent; ParkRenderer composes
            // lawn + flowers/hedges/benches from the style), so the band reads
            // finished, never bald. They join the same saved-data → ParkRenderer
            // path as the post-pass parks, and connect via their district nodes.
            if (band != null) {
                for (var g : band.greens()) {
                    var gb = new tterrag1112.life_in_the_village.Village.Decoration.Parks
                            .GardenPlot.Bounds(g.minX(), g.minZ(), g.maxX(), g.maxZ());
                    // Part 2b — feature-scored style per block (park where the
                    // terrain scores, else COTTAGE_GREEN green-commons).
                    tterrag1112.life_in_the_village.Village.Decoration.Parks.GardenStyle
                            gstyle = tterrag1112.life_in_the_village.Village.Decoration
                                    .Parks.ParkCandidateFinder.styleForRegion(
                                    fmap, gb, culture, siteCtx.inclination());
                    data.addGardenPlot(new tterrag1112.life_in_the_village.Village
                            .Decoration.Parks.GardenPlot(
                            java.util.UUID.randomUUID(), village.getId(), gb, gstyle,
                            gstyle.preserveBias(), java.util.List.of(),
                            java.util.List.of(), level.getGameTime()));
                }
            }
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

        // Roads — realize the routed in-village network through the unified
        // great-road pipeline (UnifiedRoadPlacer + culture palette + tiering),
        // replacing the retired RoadPainter (the checkerboard renderer).
        try {
            VillageRoadRealizer.realize(level, roads, culture);
            // Layout Rework — residential variant internal lanes (street-row
            // footpath now; courtyard entry path later) render through the SAME
            // unified placer, one tier down (FOOTPATH).
            VillageRoadRealizer.realizePaths(level, phased.internalLanes(), culture);
        } catch (Exception e) {
            LOGGER.warn("V2: VillageRoadRealizer failed: {}", e.getMessage());
        }

        // Layout Rework — COURTYARD decoration: well centerpiece (reuses the
        // plaza well stamp) + border enclosure (reuses the farm border
        // generators). The entry path is already rendered above via realizePaths.
        for (tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.CourtyardDecor cd
                : phased.courtyardDecor()) {
            try {
                tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPieceRenderer
                        .stampWell(level, cd.wellCentre());
                village.addGatheringPoint(
                        new tterrag1112.life_in_the_village.Village.Decoration.TownSquare
                                .GatheringPoint(java.util.UUID.randomUUID(), cd.wellCentre(),
                                tterrag1112.life_in_the_village.Village.Decoration.TownSquare
                                        .GatheringPointKind.FOUNTAIN, 6));
                tterrag1112.life_in_the_village.Village.Decoration.Residential
                        .CourtyardBorderPainter.paint(level, cd.block(),
                                cd.houseFootprints(), cd.pathLines(), cd.seed(), culture.id());
            } catch (Exception e) {
                LOGGER.warn("V2: courtyard decoration failed: {}", e.getMessage());
            }
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
                int halfX = Math.max(1, fh.placed().footprint().width() / 2);
                int halfZ = Math.max(1, fh.placed().footprint().length() / 2);
                long perFhSeed = seed
                        + fh.building().getId().getMostSignificantBits()
                        ^ fh.building().getId().getLeastSignificantBits();
                // Stage 2b / 3 fix-up #4 — the FarmComplexPlanner seeds the
                // flood-fill from the centre of the reserved interior parcel
                // (bounded to the parcel budget box so the claim stays inside
                // the village). farmhouseOrigin stays the farmhouse centre so
                // the planner's apron/footprint exclusions are placed right.
                // Graceful fallback when no parcel was reserved: an in-place
                // complex behind the farmhouse (NO perimeter offset).
                var parcel = fh.placed().parcel();
                BlockPos farmOrigin = parcel != null
                        ? parcel.anchor() : fh.placed().centre();
                Direction extendsToward = parcel != null
                        ? parcel.growthDirection()
                        : toDirection(fh.placed().frontage()).getOpposite();
                tterrag1112.life_in_the_village.Utilities.Geometry.Polygon parcelBoundary =
                        parcel != null ? parcel.budget() : null;
                var planInput = new tterrag1112.life_in_the_village.Village.Farms
                        .Complex.FarmComplexPlanner.Input(
                        farmOrigin,
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
                        /* namePrefix */ village.getName() + "_complex",
                        parcelBoundary);
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
                // Stage 4a — seed the market pad from the reserved MARKET
                // plaza square (centre = its centroid). The square was
                // reserved clear AND ringed by the router, so the pad finally
                // has guaranteed space (closes the NO_REGION gap). Falls back
                // to the Stage-2b parcel centroid, then the building centre.
                var parcel = mk.placed().parcel();
                BlockPos marketPlazaCentre = marketPlazaCentroid(village);
                BlockPos marketCentre = marketPlazaCentre != null
                        ? marketPlazaCentre
                        : parcel != null
                                ? tterrag1112.life_in_the_village.Utilities.Geometry.Polygon
                                        .centroid(parcel.budget())
                                : mk.placed().centre();
                // Obstacles backstop (kept even with the parcel clearance):
                // every other placed building's footprint AABB,
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
                // Fix-up #7 — pass the ROTATED footprint dims. The market hall
                // is now bound facing the anchor across the perpendicular axis,
                // so it reliably lands rotated 90°/270°; the pad rectangle
                // (concentric footprint + margin) must match the hall's actual
                // XZ extent or the stall band would seat on the hall. 90/270
                // swap width and length.
                var rot = mk.placed().rotation();
                boolean swap = rot == net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                        || rot == net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
                int padFpWidth = swap
                        ? mk.placed().footprint().length()
                        : mk.placed().footprint().width();
                int padFpLength = swap
                        ? mk.placed().footprint().width()
                        : mk.placed().footprint().length();
                var planInput = new tterrag1112.life_in_the_village.Village.Markets
                        .Complex.MarketComplexPlanner.Input(
                        marketCentre,
                        padFpWidth,
                        padFpLength,
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

        // ── Post-placement downstream, each section guarded ─────────────
        runDownstream(level, village, data, realized, footprint,
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
     * Builds the V2-native {@link RealizedLayout} the post-placement
     * consumers see. Center / town-square position is the V2 anchor;
     * main-gate endpoint is the V2 spine end (nullable); gate positions
     * are the spine endpoints + cross-street outer endpoints; ring
     * radii come from {@link LayoutDensityProfile}; the road network is
     * carried through for the future road-side decoration source.
     *
     * <p>Field sources mirror the former synth {@code VillageLayout}
     * exactly, so a spawned village renders identically.</p>
     */
    private static RealizedLayout buildRealizedLayout(
            SiteContext siteCtx, RoadNetwork roads,
            tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB civicSquare,
            tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB marketSquare) {
        LayoutDensityProfile density = LayoutDensityProfile.forLevel(BUILDING_LEVEL);
        BlockPos anchor = siteCtx.anchor();

        // Layout Rework Stage 4 redesign — turn the planner's reserved
        // squares into CIVIC / MARKET PlazaRegions. Village.applyLayout
        // registers them via addPlazaRegion, and the VillageDecorator's
        // PlazaPaver paves them. Degenerate (zero-area) AABBs are skipped:
        // PlazaPaver's ray-cast point-in-polygon test paves 0 blocks for a
        // collapsed polygon (the paves-0 regression), so a degenerate void
        // should not be emitted at all. (The planner now floors the void at
        // MIN_PLAZA_HALF, so this is belt-and-suspenders.)
        List<tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion>
                plazas = new ArrayList<>();
        // Plaza fix-up — floorY is the WALKING/air level, NOT the ground-block
        // Y. anchor.getY() is the ground solid block (buildings place at
        // centre.getY()+1 to sit ON it — see toPivot), and PlazaPaver paves at
        // floorY-1 (= the ground block) so players walk on floorY. Passing the
        // raw anchor.getY() buried the pavement one block under the grass AND
        // sank every decoration; +1 puts floorY at the surface for both.
        int plazaFloorY = anchor.getY() + 1;
        if (nonDegenerate(civicSquare)) {
            plazas.add(squarePlaza(civicSquare, plazaFloorY,
                    tterrag1112.life_in_the_village.Village.Decoration.Plaza
                            .PlazaPurpose.CIVIC));
        }
        if (nonDegenerate(marketSquare)) {
            plazas.add(squarePlaza(marketSquare, plazaFloorY,
                    tterrag1112.life_in_the_village.Village.Decoration.Plaza
                            .PlazaPurpose.MARKET));
        }

        // Layout Rework Stage 3d — gates come from the routed network's
        // GATEWAY nodes (the gateways-first derivation from 3b, snapped
        // to buildable cells by BlockServingRouter). On the CLUSTER routed
        // network the legacy spine is a fiction, so spineEnd/spineStart +
        // cross-streets are meaningless. Reading the GATEWAY node positions
        // here — rather than the raw ctx.gateways() — is deliberate: it
        // guarantees GatewayPopulator creates the gateway graph nodes at
        // the EXACT positions InternalRoadCommitter.commitFromV2 matches
        // against, keeping the village nav graph connected (a position
        // mismatch would leave the gateways unreachable and silently break
        // caravan routing through the village).
        //
        // mainGateEndpoint = the PRIMARY gateway node; gatePositions = both
        // gateway nodes (the intended CLUSTER shape: exactly two). Falls
        // back to the raw ctx.gateways(), then the anchor, on a degenerate
        // site with no GATEWAY nodes (GatewayPopulator tolerates an empty
        // list).
        List<BlockPos> gates = new ArrayList<>();
        BlockPos mainGate = null;
        NetworkSpec spec = roads.skeleton().network();
        if (spec != null) {
            for (NetworkNode n : spec.nodes()) {
                if (n.kind() != NodeKind.GATEWAY) continue;
                gates.add(n.pos());
                if (mainGate == null || "gateway:PRIMARY".equals(n.id())) {
                    mainGate = n.pos();
                }
            }
        }
        if (gates.isEmpty()) {
            Gateways gw = siteCtx.gateways();
            if (gw != null) {
                gates.add(gw.primary());
                gates.add(gw.secondary());
                mainGate = gw.primary();
            }
        }
        if (mainGate == null) mainGate = anchor;

        return new RealizedLayout(
                anchor,                      // center
                anchor,                      // townSquarePos
                FALLBACK_TOWN_SQUARE_RADIUS, // townSquareRadius
                mainGate,                    // mainGateEndpoint (nullable)
                gates,                       // gatePositions
                density.getRing1Radius(),
                density.getRing2Radius(),
                roads,                       // roadNetwork (D1(b))
                plazas);                     // Stage 4a — civic + market plazas
    }

    /** Stage 4 redesign — a void is paveable only if it has real area; a
     *  collapsed AABB makes PlazaPaver pave 0 blocks. */
    private static boolean nonDegenerate(
            tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB a) {
        return a != null && a.maxX() > a.minX() && a.maxZ() > a.minZ();
    }

    /** Stage 4a — a square {@link tterrag1112.life_in_the_village.Village
     *  .Decoration.Plaza.PlazaRegion} from a reserved void AABB. */
    private static tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion
            squarePlaza(tterrag1112.life_in_the_village.Utilities.Geometry.Polygon.AABB a,
                        int floorY,
                        tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose
                                purpose) {
        var poly = new tterrag1112.life_in_the_village.Utilities.Geometry.Polygon(List.of(
                new BlockPos(a.minX(), floorY, a.minZ()),
                new BlockPos(a.maxX(), floorY, a.minZ()),
                new BlockPos(a.maxX(), floorY, a.maxZ()),
                new BlockPos(a.minX(), floorY, a.maxZ())));
        BlockPos centroid = new BlockPos((a.minX() + a.maxX()) / 2, floorY,
                (a.minZ() + a.maxZ()) / 2);
        return new tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion(
                java.util.UUID.randomUUID(), purpose,
                tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape.SQUARE,
                poly, centroid, floorY, java.util.Set.of(), 0f);
    }

    /** Stage 4a / fix-up #6 — centroid of the market's square: the MARKET
     *  plaza when one exists (CITY+), else the CIVIC plaza (the merged
     *  civic+market square at TOWN and smaller — the market shares it).
     *  Null only when no plaza was produced. */
    private static BlockPos marketPlazaCentroid(Village village) {
        BlockPos civic = null;
        for (var r : village.getPlazaRegions()) {
            var p = r.purpose();
            if (p == tterrag1112.life_in_the_village.Village.Decoration
                    .Plaza.PlazaPurpose.MARKET) {
                return r.centroid();
            }
            if (p == tterrag1112.life_in_the_village.Village.Decoration
                    .Plaza.PlazaPurpose.CIVIC) {
                civic = r.centroid();
            }
        }
        return civic;
    }

    /**
     * Convert V2's centre-based placement to the pivot {@link
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
     * Runs the post-placement pipeline. Each section is wrapped
     * because the {@link RealizedLayout} omits state (plazas, sectors,
     * farm plots, features) that some consumers expect. Failure of one
     * section logs and continues; the goal is "downstream runs without
     * aborting the spawn," not feature parity.
     */
    private static void runDownstream(ServerLevel level,
                                      Village village,
                                      VillageSavedData data,
                                      RealizedLayout realized,
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
                                realized, footprint));
        guard("DecorationPass", () ->
                tterrag1112.life_in_the_village.Village.Decoration.Framework
                        .DecorationPass.run(level, village, data, realized));
        guard("ParkRenderer", () ->
                tterrag1112.life_in_the_village.Village.Decoration.Parks
                        .ParkRenderer.run(level, village, data));
        // Civic-plaza decoration complex — designed central square (well
        // fountain + perimeter gardens + open paving). After plaza paving so
        // the floor doesn't overwrite the pieces. Replaces the code-gen well.
        guard("CivicPlazaComplex", () ->
                tterrag1112.life_in_the_village.Village.Decoration.Plaza
                        .CivicPlazaComplex.decorate(level, village));
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
