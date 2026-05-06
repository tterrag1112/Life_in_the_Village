package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaPurpose;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaShape;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.AnchorKind;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.Anchor;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.EdgeRef;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.LayoutBlueprint;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.PlazaDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.RoadDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SectorDeclaration;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SectorRef;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.SlotIntention;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadResult;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase C: declarative port of RADIAL.
 *
 * <h3>Structure preserved from pre-Phase-A version</h3>
 * <ul>
 *   <li>1 main road (StraightRoad, SPINE, terrain-biased direction
 *       + cascade rotation accumulator)</li>
 *   <li>N spurs (Spur primitives, SPUR role) where N depends on
 *       remaining building count. Some branch from the plaza
 *       ("squareRooted"), others from points along the main road
 *       ("mainRooted") at fractions {0.5}, {0.35,0.7},
 *       {0.3,0.55,0.8}, or {0.25,0.45,0.65,0.85}.</li>
 *   <li>N cluster arcs (Arc primitives, one per spur, 60° centered
 *       on plaza, drift 3.0)</li>
 *   <li>0 or 2 inner arcs (if spurCount &ge; 3, 210° span at
 *       intermediate radii, drift 4.0/5.5)</li>
 *   <li>1 outer ring (Ring, OUTER_RING role, drift 2.0)</li>
 *   <li>1 plaza (CIRCLE) at village centre</li>
 *   <li>5+ sectors: main_road, spur_i (per spur), arc_i (per inner
 *       arc, optional), outer_agri, outer_defense</li>
 * </ul>
 *
 * <h3>What's different from the imperative version</h3>
 * Slot emission is fully declarative — five {@link SlotIntention}s
 * replace ~10 hand-rolled slot loops. The SlotEmitter's resolvers
 * (Phase B) produce positions; recipe authors stop computing them.
 *
 * <p>Position differences from the post-Phase-20 baseline are
 * expected and catalogued in the Phase C smoke run:
 * <ul>
 *   <li>{@link Anchor.RoadAlong} uses the validator-cap-correct
 *       perpOffset formula
 *       {@code tier.reservedHalfWidth + max(halfW, halfL) + 1}
 *       (~21 for fp=35) vs the old recipe's hand-tuned 8. New
 *       slots are ~13 blocks farther from the road; still under
 *       the validator's cap.</li>
 *   <li>Cluster arc slots use road-tangent perpendicular (the
 *       resolver) instead of the old recipe's radial-from-plaza
 *       perpendicular. For 60° arcs centered on the plaza the
 *       difference is small.</li>
 *   <li>{@link Anchor.RingValidated} samples angles around the
 *       ring; the old {@link tterrag1112.life_in_the_village
 *       .Village.Planning.Primitives.LayoutPrimitive.RingBand}
 *       walked candidate positions in the band's coordinate frame.
 *       Slot ordering may differ; positions should match within
 *       a block or two.</li>
 * </ul>
 *
 * <h3>Compose-time pre-realisation</h3>
 * Spurs branch from points along the main road; cluster arcs
 * derive radius and angle from each spur's tip. Both depend on
 * realised centerlines that the planner won't compute until
 * {@code realiseRoads} runs after compose. This recipe calls
 * {@link BaseRecipe#computeAndRecord} at compose time to get
 * each centerline; the planner will compute it again during
 * realisation. The double-compute is wasted work but correct
 * because {@code computeCenterline} is deterministic. Phase D
 * dedupes if measurement reveals the cost matters.
 *
 * <h3>Cascade</h3>
 * {@link #reEmit} on {@link ReEmitReason.SevereTruncation}: rotate
 * 90° once, then null (chain advance). Mirrors the pre-Phase-A
 * recipe's behavior. The {@code cascadeAxisRotation} accumulator
 * threads through compose so the second invocation produces a
 * rotated layout.
 */
public final class RadialRecipe extends BaseRecipe {

    private static final int SPUR_CAPACITY = 5;
    private static final int UNLIMITED_CAPACITY = 1024;

    // Tag bundles preserved verbatim from the pre-Phase-A recipe so
    // the matcher's per-slot scoring sees the same tag sets.
    private static final Set<SlotTag> TAGS_MAIN_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.CIVIC_ADJACENT, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_SPUR_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.PRODUCTION_INFILL,
                    SlotTag.RESIDENTIAL_INFILL, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_SPUR_CLUSTER =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.PRODUCTION_SPUR_END,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_ARC =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.ROAD_ADJACENT);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Main direction (terrain-biased + cascade rotation) ─────────
        // Same algorithm as pre-Phase-A: pick the civic-step angle
        // closest to terrain bias, then add the cumulative
        // cascadeAxisRotation accumulator. RETRY iterations bump the
        // accumulator by 90° via reEmit.
        int squareCapacity = Math.max(6, Math.min(10, totalBuildings / 4 + 3));
        double terrainBias = directionRadOf(terrain.primaryOrientationDir());
        int civicCount = Math.max(1, squareCapacity);
        double civicStep = 2 * Math.PI / civicCount;
        double mainDirRad = terrainBias;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < civicCount; i++) {
            double gapAngle = -Math.PI / 2 + civicStep * (i + 0.5);
            double diff = Math.abs(angleDelta(gapAngle, terrainBias));
            if (diff < best) { best = diff; mainDirRad = gapAngle; }
        }
        mainDirRad += pctx.cascadeAxisRotation();

        // ── Geometry estimates ─────────────────────────────────────────
        // civicRing comes from RecipeHelpers.installPlaza at realise
        // time. Recipe estimates via density.getRing1Radius(); the
        // few-block discrepancy is bounded and the slots still place.
        int approxCivicRing = pctx.density.getRing1Radius();
        int densityScale = Math.max(1, pctx.density.getRing2Radius());
        int extendedRing = approxCivicRing + 20;
        int outerR = approxCivicRing + 40 + densityScale;
        int mainLength = outerR * 2 + 48;

        // ── Main road ──────────────────────────────────────────────────
        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad) * extendedRing),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad) * extendedRing)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                mainStart.getX() + (int) Math.round(Math.cos(mainDirRad) * mainLength),
                mainStart.getY(),
                mainStart.getZ() + (int) Math.round(Math.sin(mainDirRad) * mainLength)));
        RoadPrimitive.StraightRoad mainPrimitive = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);

        // Pre-realise the main road's centerline so spurs can branch
        // from it. computeAndRecord doesn't mutate the road graph;
        // realiseRoads will compute the same centerline at realisation.
        RoadResult mainResult = computeAndRecord(mainPrimitive, pctx);
        List<BlockPos> mainCenterline = mainResult.centerline();

        EdgeRef mainRef = EdgeRef.of("radial_main");
        RoadDeclaration mainDecl = new RoadDeclaration(
                mainRef, EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_ROAD,
                mainPrimitive);

        // ── Spur structure (count + branch points) ─────────────────────
        int remaining = pctx.remaining.size();
        int spurCount = Math.max(2, (int) Math.ceil(remaining / 5.0));
        spurCount = Math.min(spurCount, Math.max(2, remaining / 2));

        int squareRooted = Math.min(spurCount, spurCount <= 3 ? 1 : 2);
        int mainRooted = spurCount - squareRooted;
        double[] mainFractions = mainFractions(mainRooted);
        double spurArc = 2 * Math.PI / Math.max(2, spurCount);

        List<RoadDeclaration> spurDecls = new ArrayList<>();
        List<RoadDeclaration> clusterArcDecls = new ArrayList<>();
        // Per-spur list of sectors so we can build SectorDeclarations
        // in lockstep with the cluster-arc generation.
        List<EdgeRef> spurRefs = new ArrayList<>();

        for (int i = 0; i < spurCount; i++) {
            BlockPos branchHint;
            double spurAngle;
            if (i < squareRooted) {
                spurAngle = mainDirRad + Math.PI + (i - squareRooted / 2.0) * spurArc;
                branchHint = new BlockPos(
                        centre.getX() + (int) Math.round(Math.cos(spurAngle) * extendedRing),
                        centre.getY(),
                        centre.getZ() + (int) Math.round(Math.sin(spurAngle) * extendedRing));
            } else {
                double frac = mainFractions[i - squareRooted];
                int idx = Math.min(mainCenterline.size() - 1,
                        Math.max(0, (int) (frac * mainCenterline.size())));
                branchHint = mainCenterline.get(idx);
                boolean leftSide = ((i - squareRooted) & 1) == 0;
                spurAngle = mainDirRad + (leftSide ? -Math.PI / 2 : Math.PI / 2);
                // RNG-perturbed angle. Determinism: same seed →
                // same call order → same result. The recipe must
                // consume RNG in the same order as the old recipe
                // for byte-equality.
                spurAngle += (pctx.rng.nextDouble() - 0.5) * 0.4;
            }

            int spurLength = pctx.density.getRing1Radius()
                    + pctx.rng.nextInt(Math.max(1,
                    pctx.density.getRing2Radius() - pctx.density.getRing1Radius()));

            RoadPrimitive.Spur spurPrimitive = new RoadPrimitive.Spur(
                    mainCenterline, branchHint, spurAngle,
                    spurLength, 5.0, RoadShape.RoadTier.VILLAGE_PATH);

            EdgeRef spurRef = EdgeRef.of("radial_spur_" + i);
            spurRefs.add(spurRef);
            spurDecls.add(new RoadDeclaration(
                    spurRef, EdgeRole.SPUR, RoadShape.RoadTier.VILLAGE_PATH,
                    spurPrimitive));

            // ── Cluster arc (60° at spur tip, centred on village) ──────
            // Pre-realise the spur to get the tip; cluster arc
            // radius/angle derive from it.
            RoadResult spurResult = computeAndRecord(spurPrimitive, pctx);
            List<BlockPos> spurCenterline = spurResult.centerline();
            BlockPos spurTip = spurCenterline.isEmpty() ? branchHint
                    : spurCenterline.get(spurCenterline.size() - 1);
            double tipDx = spurTip.getX() - centre.getX();
            double tipDz = spurTip.getZ() - centre.getZ();
            int clusterRadius = Math.max(8,
                    (int) Math.round(Math.sqrt(tipDx * tipDx + tipDz * tipDz)));
            double tipAngle = Math.atan2(tipDz, tipDx);

            RoadPrimitive.Arc clusterArc = new RoadPrimitive.Arc(
                    centre, clusterRadius,
                    tipAngle - Math.PI / 6, Math.PI / 3,
                    3.0, RoadShape.RoadTier.VILLAGE_PATH);
            clusterArcDecls.add(new RoadDeclaration(
                    EdgeRef.of("radial_spur_" + i + "_cluster"),
                    EdgeRole.SPUR, RoadShape.RoadTier.VILLAGE_PATH,
                    clusterArc));
        }

        // ── Optional inner arcs (210° at intermediate radii) ───────────
        List<RoadDeclaration> innerArcDecls = new ArrayList<>();
        if (spurCount >= 3) {
            int civicRingEstimate = approxCivicRing;
            int outerR2Estimate = outerR;
            int[] arcRadii = {
                    civicRingEstimate + (outerR2Estimate - civicRingEstimate) / 2,
                    civicRingEstimate + (outerR2Estimate - civicRingEstimate) * 3 / 4
            };
            double arcCentreAngle = mainDirRad + Math.PI;
            double arcSpan = Math.toRadians(210);
            double arcStartAngle = arcCentreAngle - arcSpan / 2;

            for (int ai = 0; ai < arcRadii.length; ai++) {
                int r = arcRadii[ai];
                if (r < civicRingEstimate + 6) continue;
                double drift = ai == 0 ? 4.0 : 5.5;
                RoadPrimitive.Arc arc = new RoadPrimitive.Arc(
                        centre, r, arcStartAngle, arcSpan, drift,
                        RoadShape.RoadTier.VILLAGE_PATH);
                innerArcDecls.add(new RoadDeclaration(
                        EdgeRef.of("radial_arc_" + ai),
                        EdgeRole.RING, RoadShape.RoadTier.VILLAGE_PATH,
                        arc));
            }
        }

        // ── Outer ring ─────────────────────────────────────────────────
        int outerRingR = outerR + 20;
        RoadPrimitive.Ring outerRingPrim = new RoadPrimitive.Ring(
                centre, outerRingR, 2.0, RoadShape.RoadTier.VILLAGE_PATH);
        EdgeRef outerRingRef = EdgeRef.of("radial_outer_ring");
        RoadDeclaration outerRingDecl = new RoadDeclaration(
                outerRingRef, EdgeRole.OUTER_RING,
                RoadShape.RoadTier.VILLAGE_PATH, outerRingPrim);

        // ── Assemble road list (main first — primary spine) ────────────
        List<RoadDeclaration> roads = new ArrayList<>();
        roads.add(mainDecl);
        roads.addAll(spurDecls);
        roads.addAll(clusterArcDecls);
        roads.addAll(innerArcDecls);
        roads.add(outerRingDecl);

        // ── Plaza ──────────────────────────────────────────────────────
        SectorRef civicSec = SectorRef.of("plaza_civic");
        // targetRadius is the visual radius the planner translates to
        // an area via π·r². Use the density's ring1 radius as a
        // proxy — RecipeHelpers.installPlaza picks the actual area
        // from sizeTier.
        PlazaDeclaration plazaDecl = new PlazaDeclaration(
                centre, approxCivicRing, PlazaShape.CIRCLE,
                PlazaPurpose.CIVIC, civicSec);

        // ── Sectors ────────────────────────────────────────────────────
        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration(
                "plaza_civic", SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                squareCapacity, /* maxFp */ 46, /* parentEdge */ null));
        sectors.add(new SectorDeclaration(
                "radial_main_road", SectorRole.RESIDENTIAL_INFILL,
                BuildingZone.RESIDENTIAL,
                UNLIMITED_CAPACITY, /* maxFp */ 16, mainRef));
        for (int i = 0; i < spurCount; i++) {
            sectors.add(new SectorDeclaration(
                    "radial_spur_" + i, SectorRole.SPUR_CLUSTER,
                    BuildingZone.PRODUCTION,
                    SPUR_CAPACITY, /* maxFp */ 16, spurRefs.get(i)));
        }
        for (int ai = 0; ai < innerArcDecls.size(); ai++) {
            sectors.add(new SectorDeclaration(
                    "radial_arc_" + ai, SectorRole.RESIDENTIAL_INFILL,
                    BuildingZone.RESIDENTIAL,
                    UNLIMITED_CAPACITY, /* maxFp */ 16,
                    innerArcDecls.get(ai).ref()));
        }
        sectors.add(new SectorDeclaration(
                "radial_outer_agri", SectorRole.AGRICULTURAL_FRINGE,
                BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, /* maxFp */ 18, outerRingRef));
        sectors.add(new SectorDeclaration(
                "radial_outer_defense", SectorRole.DEFENSIVE_FRINGE,
                BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, /* maxFp */ 18, outerRingRef));

        // ── Slot intentions ────────────────────────────────────────────
        List<SlotIntention> intentions = new ArrayList<>();

        // Civic slots on the plaza polygon perimeter.
        // PlazaPerimeter resolver enforces the validator-cap invariant
        // and routes PRIME/SECONDARY_CIVIC tagged slots to
        // plaza.civicSlots() so the matcher's civic-first claim path
        // consumes them. avoidSpurExitChebDist=6 — vertices within 6
        // chebyshev of a spur exit are skipped (avoids spur-overlap
        // failure pattern).
        intentions.add(new SlotIntention(
                civicSec, TAGS_PRIME_CIVIC, 1, /* maxFp */ 46,
                new Anchor.PlazaPerimeter(civicSec, 6)));
        intentions.add(new SlotIntention(
                civicSec, TAGS_SECONDARY_CIVIC,
                Math.max(0, squareCapacity - 1), /* maxFp */ 46,
                new Anchor.PlazaPerimeter(civicSec, 6)));

        // Main road residential slots. Old recipe used perpOffset=8
        // (hand-tuned); RoadAlong uses
        // tier.reservedHalfWidth + max(halfW, halfL) + 1 — the
        // validator-cap-correct distance. New slots are ~13 blocks
        // farther from the road; still under the validator cap.
        intentions.add(new SlotIntention(
                SectorRef.of("radial_main_road"), TAGS_MAIN_ROAD,
                /* desiredCount */ 8, /* maxFp */ 35,
                new Anchor.RoadAlong(mainRef, /* spacing */ 8, true)));

        // Per-spur residential + cluster slots. Two intentions per
        // spur: one for slots along the spur road, one for slots
        // along the cluster arc at the spur tip.
        for (int i = 0; i < spurCount; i++) {
            EdgeRef spurRef = spurRefs.get(i);
            intentions.add(new SlotIntention(
                    SectorRef.of("radial_spur_" + i), TAGS_SPUR_ROAD,
                    /* desiredCount */ 4, /* maxFp */ 40,
                    new Anchor.RoadAlong(spurRef, /* spacing */ 7, true)));
            intentions.add(new SlotIntention(
                    SectorRef.of("radial_spur_" + i), TAGS_SPUR_CLUSTER,
                    /* desiredCount */ 4, /* maxFp */ 16,
                    new Anchor.RoadAlong(
                            EdgeRef.of("radial_spur_" + i + "_cluster"),
                            /* spacing */ 10, true)));
        }

        // Inner arc residential.
        for (int ai = 0; ai < innerArcDecls.size(); ai++) {
            intentions.add(new SlotIntention(
                    SectorRef.of("radial_arc_" + ai), TAGS_ARC,
                    /* desiredCount */ 6, /* maxFp */ 30,
                    new Anchor.RoadAlong(
                            innerArcDecls.get(ai).ref(),
                            /* spacing */ 7, true)));
        }

        // Outer rings. The old recipe used LayoutPrimitive.RingBand
        // walking the band's coordinate frame. RingValidated samples
        // angles around a ring at fixed radius and skips angles
        // farther than maxRoadDistance from any feeding road. The
        // validator-cap invariant is enforced by the resolver.
        // - AGRI band was [outerR2 + 4, outerR2 + 20] in the old
        //   recipe; pick the band's midpoint.
        // - DEFENSE band was [outerR2, outerR2 + 10]; pick its
        //   midpoint.
        intentions.add(new SlotIntention(
                SectorRef.of("radial_outer_agri"), TAGS_AGRI,
                /* desiredCount */ 8, /* maxFp */ 18,
                new Anchor.RingValidated(outerR + 12, /* jitter */ 0.0)));
        intentions.add(new SlotIntention(
                SectorRef.of("radial_outer_defense"), TAGS_DEFENSE,
                /* desiredCount */ 6, /* maxFp */ 18,
                new Anchor.RingValidated(outerR + 5, /* jitter */ 0.0)));

        // ── Named anchors ──────────────────────────────────────────────
        // TOWN_SQUARE = village centre. MAIN_GATE is the main road's
        // far endpoint (the planner has the realised centerline post-
        // realiseRoads, so this maps to mainEnd).
        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        namedAnchors.put(AnchorKind.MAIN_GATE, mainEnd);

        return new LayoutBlueprint(
                ShapeType.RADIAL,
                roads,
                List.of(plazaDecl),
                sectors,
                intentions,
                namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            case ReEmitReason.SevereTruncation t -> {
                pctx.recordTruncation();
                if (pctx.cascadeRetryCount() < 1) {
                    pctx.recordCascadeRetry();
                    pctx.setCascadeAxisRotation(
                            pctx.cascadeAxisRotation() + Math.PI / 2);
                    System.out.println("[RADIAL-REEMIT] rotating axis 90° "
                            + "(retry=" + pctx.cascadeRetryCount() + ")");
                    // Re-run compose with the rotated accumulator.
                    // The accumulator is read at the start of compose,
                    // so the new blueprint's main direction is shifted.
                    yield compose(pctx);
                }
                System.out.println("[RADIAL-REEMIT] rotation budget "
                        + "exhausted — chain advance");
                yield null;
            }
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static double[] mainFractions(int mainRooted) {
        return switch (mainRooted) {
            case 0 -> new double[0];
            case 1 -> new double[]{0.5};
            case 2 -> new double[]{0.35, 0.7};
            case 3 -> new double[]{0.3, 0.55, 0.8};
            default -> new double[]{0.25, 0.45, 0.65, 0.85};
        };
    }

    private static double angleDelta(double a, double b) {
        double d = (a - b) % (2 * Math.PI);
        if (d > Math.PI) d -= 2 * Math.PI;
        if (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    private static double directionRadOf(TerrainAnalyzer.FlatDirection dir) {
        return switch (dir) {
            case EAST  -> 0;
            case SOUTH -> Math.PI / 2;
            case WEST  -> Math.PI;
            case NORTH -> -Math.PI / 2;
        };
    }
}
