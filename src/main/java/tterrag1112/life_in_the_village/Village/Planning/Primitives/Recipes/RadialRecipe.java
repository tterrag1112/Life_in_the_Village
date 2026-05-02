package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.BuildingZone;
import tterrag1112.life_in_the_village.Village.Planning.Graph.EdgeRole;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadResult;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddRing;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.AddSpur;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.FixedGrowth;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.Sector;
import tterrag1112.life_in_the_village.Village.Planning.Sectors.SectorRole;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.PlacementSlot;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * RADIAL layout. Phase 16b: cascade-aware via {@link #runWithCascade}.
 *
 * <p>Geometry unchanged from prior phases — same plaza, main road,
 * spurs, arc roads, outer ring. What changed:
 *
 * <ul>
 *   <li>{@link #compose} routes through the cascade engine rather than
 *       calling {@link #composeSectors} directly.</li>
 *   <li>{@link #composeOnce} is structured probe → decide → commit.
 *       The primary spine's viability is checked before any layout
 *       mutation, so RETRY can re-run with a rotated axis without
 *       producing duplicate plazas / roads.</li>
 *   <li>OUTER_RING / cluster-arc slot emissions clamp via
 *       {@link #clampToRoadReach} so truncated roads no longer
 *       advertise unreachable slots.</li>
 *   <li>{@link #reEmit} handles {@code SevereTruncation}: one 90°
 *       rotation, then FALLBACK to LINEAR.</li>
 *   <li>{@link #fallbackShape} returns LINEAR.</li>
 * </ul>
 */
public final class RadialRecipe extends BaseRecipe {

    private static final Set<SlotTag> TAGS_SPUR_CLUSTER =
            EnumSet.of(SlotTag.PRODUCTION_CLUSTER, SlotTag.PRODUCTION_SPUR_END,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_MAIN_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.CIVIC_ADJACENT, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_SPUR_ROAD =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.PRODUCTION_INFILL,
                    SlotTag.RESIDENTIAL_INFILL, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_ARC =
            EnumSet.of(SlotTag.ROAD_ADJACENT, SlotTag.RESIDENTIAL_INFILL,
                    SlotTag.BACKFILL);

    private static final int SPUR_CAPACITY = 5;
    private static final int UNLIMITED_CAPACITY = 1024;
    /** Per-slot slack added to {@code footprintHalf + roadHalfWidth} for clamp. */
    private static final int CLAMP_SLACK = 6;

    /** Set true by {@link #prepareFeatures} when terrain is unusable. */
    private boolean terrainTooRough;

    @Override
    public void compose(PlanContext pctx) {
        runWithCascade(pctx, 3);
    }

    @Override
    protected void prepareFeatures(PlanContext pctx) {
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = pctx.layout.getTerrain().largestFlatPatchAvailable(2);
        terrainTooRough = available < requiredFlatSide;
        if (terrainTooRough) {
            System.out.println("RadialRecipe: terrain pre-check failed — "
                    + "largest building " + largest + "x" + largest
                    + " needs ≥" + requiredFlatSide + " flat side, "
                    + "available=" + available);
        }
    }

    @Override
    protected ReEmitReason composeOnce(PlanContext pctx) {
        if (terrainTooRough) {
            // No fallback shape will fit either — abort cleanly.
            tterrag1112.life_in_the_village.Kingdom.Placement
                    .PlacementFailureRecorder.record(
                    tterrag1112.life_in_the_village.Kingdom.Placement
                            .PlacementFailureRecorder.Reason.TERRAIN_UNSUITABLE,
                    "RADIAL: no flat patch large enough for largest building",
                    pctx.layout.getCenter(),
                    ShapeType.RADIAL.name());
            pctx.layout.markUnplannable(
                    "RADIAL terrain too rough for largest building");
            return null;
        }

        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();

        // ── Phase A: probe spine (no layout mutation) ─────────────────────
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
        // Apply cascade rotation accumulator (RETRY adds 90°, etc.)
        mainDirRad += pctx.cascadeAxisRotation();

        // Probe geometry uses density.getRing1Radius() as a proxy for the
        // civic ring (the real value is set by installPlaza, which we
        // can't run yet without committing). The few-block discrepancy
        // is well within the 30% viability threshold.
        int approxCivicRing = pctx.density.getRing1Radius();
        int extendedRing = approxCivicRing + 20;
        int densityScale = Math.max(1, pctx.density.getRing2Radius());
        int outerR = approxCivicRing + 40 + densityScale;
        int mainLength = outerR * 2 + 48;

        BlockPos probeStart = pctx.solidSurface(new BlockPos(
                centre.getX() + (int) Math.round(Math.cos(mainDirRad) * extendedRing),
                centre.getY(),
                centre.getZ() + (int) Math.round(Math.sin(mainDirRad) * extendedRing)));
        BlockPos probeEnd = pctx.solidSurface(new BlockPos(
                probeStart.getX() + (int) Math.round(Math.cos(mainDirRad) * mainLength),
                probeStart.getY(),
                probeStart.getZ() + (int) Math.round(Math.sin(mainDirRad) * mainLength)));
        RoadPrimitive.StraightRoad probeRoad = new RoadPrimitive.StraightRoad(
                probeStart, probeEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);
        RoadResult spineProbe = computeAndRecord(probeRoad, pctx);
        pctx.recordPrimarySpine(spineProbe);
        RecipeStatus status = checkPrimarySpine(spineProbe);
        if (status != RecipeStatus.OK) {
            return new ReEmitReason.SevereTruncation(spineProbe);
        }

        // ── Phase B: commit (probe passed) ────────────────────────────────
        int civicSnapshot = pctx.slotPoolSize();
        RecipeHelpers.installPlaza(pctx, centre,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaShape.CIRCLE);
        BlockPos squarePos = pctx.layout.getTownSquarePos();
        int civicRing = pctx.layout.getCivicRingRadius();
        // Re-derive geometry from the actual squarePos (may differ from
        // centre by a few blocks). outerR uses the real civicRing.
        int extendedRing2 = civicRing + 20;
        int outerR2 = civicRing + 40 + densityScale;
        int mainLength2 = outerR2 * 2 + 48;

        BlockPos mainStart = pctx.solidSurface(new BlockPos(
                squarePos.getX() + (int) Math.round(Math.cos(mainDirRad) * extendedRing2),
                squarePos.getY(),
                squarePos.getZ() + (int) Math.round(Math.sin(mainDirRad) * extendedRing2)));
        BlockPos mainEnd = pctx.solidSurface(new BlockPos(
                mainStart.getX() + (int) Math.round(Math.cos(mainDirRad) * mainLength2),
                mainStart.getY(),
                mainStart.getZ() + (int) Math.round(Math.sin(mainDirRad) * mainLength2)));
        RoadPrimitive.StraightRoad mainRoad = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);

        List<PlacementSlot> civicSlots = pctx.drainSlotsSince(civicSnapshot);
        if (!civicSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "radial_civic_ring", SectorRole.CIVIC_RING,
                    BuildingZone.CIVIC, civicSlots, squareCapacity, false,
                    FixedGrowth.INSTANCE, -1, null, 32));
        }

        int beforeMainEdges = pctx.layout.getRoadGraph().edgeCount();
        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                mainRoad, pctx.level, pctx.worldSeed);
        int mainEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeMainEdges
                ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;
        radialEdgeLog("MAIN_ROAD", "StraightRoad", 0, mainDirRad,
                mainEdgeId, mainCenterline, centre);

        pctx.layout.setMainGateEndpoint(mainEnd);
        pctx.layout.addGatePosition(mainStart);
        pctx.layout.addGatePosition(mainEnd);

        pctx.offerRoadSlots(mainCenterline, 8, 8, TAGS_MAIN_ROAD, 35);

        // ── Spurs (each becomes a SPUR_CLUSTER sector) ────────────────────
        int remaining = pctx.remaining.size();
        int spurCount = Math.max(2, (int) Math.ceil(remaining / 5.0));
        spurCount = Math.min(spurCount, Math.max(2, remaining / 2));

        int squareRooted = Math.min(spurCount, spurCount <= 3 ? 1 : 2);
        int mainRooted = spurCount - squareRooted;
        double[] mainFractions = mainRooted == 0 ? new double[0]
                : mainRooted == 1 ? new double[]{0.5}
                : mainRooted == 2 ? new double[]{0.35, 0.7}
                : mainRooted == 3 ? new double[]{0.3, 0.55, 0.8}
                : new double[]{0.25, 0.45, 0.65, 0.85};

        List<List<BlockPos>> allRoadsForSnap = new ArrayList<>();
        allRoadsForSnap.add(mainCenterline);

        double spurArc = 2 * Math.PI / Math.max(2, spurCount);

        for (int i = 0; i < spurCount; i++) {
            BlockPos branchHint;
            double spurAngle;
            if (i < squareRooted) {
                spurAngle = mainDirRad + Math.PI + (i - squareRooted / 2.0) * spurArc;
                branchHint = new BlockPos(
                        squarePos.getX() + (int) Math.round(Math.cos(spurAngle) * extendedRing2),
                        squarePos.getY(),
                        squarePos.getZ() + (int) Math.round(Math.sin(spurAngle) * extendedRing2));
            } else {
                double frac = mainFractions[i - squareRooted];
                int idx = Math.min(mainCenterline.size() - 1,
                        Math.max(0, (int)(frac * mainCenterline.size())));
                branchHint = mainCenterline.get(idx);
                boolean leftSide = ((i - squareRooted) & 1) == 0;
                spurAngle = mainDirRad + (leftSide ? -Math.PI / 2 : Math.PI / 2);
                spurAngle += (pctx.rng.nextDouble() - 0.5) * 0.4;
            }

            int spurLength = pctx.density.getRing1Radius()
                    + pctx.rng.nextInt(Math.max(1,
                    pctx.density.getRing2Radius() - pctx.density.getRing1Radius()));

            RoadPrimitive.Spur spur = new RoadPrimitive.Spur(
                    mainCenterline, branchHint, spurAngle,
                    spurLength, 5.0, RoadShape.RoadTier.VILLAGE_PATH);

            int beforeEdges = pctx.layout.getRoadGraph().edgeCount();
            List<BlockPos> spurCenterline = pctx.layout.addRoad(
                    spur, pctx.level, pctx.worldSeed);
            int spurEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeEdges
                    ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;
            radialEdgeLog("SPUR_" + i, "Spur", spurLength, spurAngle,
                    spurEdgeId, spurCenterline, centre);
            allRoadsForSnap.add(spurCenterline);

            // ── Cluster arc (60° arc at spur tip) ──────────────────────────
            BlockPos spurTip = spurCenterline.isEmpty() ? branchHint
                    : spurCenterline.get(spurCenterline.size() - 1);
            double tipDx = spurTip.getX() - squarePos.getX();
            double tipDz = spurTip.getZ() - squarePos.getZ();
            int clusterRadius = Math.max(8,
                    (int) Math.round(Math.sqrt(tipDx * tipDx + tipDz * tipDz)));
            double tipAngle = Math.atan2(tipDz, tipDx);

            RoadPrimitive.Arc clusterArc = new RoadPrimitive.Arc(
                    squarePos, clusterRadius,
                    tipAngle - Math.PI / 6, Math.PI / 3,
                    3.0, RoadShape.RoadTier.VILLAGE_PATH);
            // Probe-record so we can clamp cluster slots to the arc's reach.
            RoadResult clusterArcResult = computeAndRecord(clusterArc, pctx);
            int beforeClusterEdges = pctx.layout.getRoadGraph().edgeCount();
            List<BlockPos> clusterArcCenterline = pctx.layout.addRoad(
                    clusterArc, pctx.level, pctx.worldSeed);
            int clusterArcEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeClusterEdges
                    ? pctx.layout.getRoadGraph().edgeCount() - 1 : spurEdgeId;
            radialEdgeLog("SPUR_" + i + "_CLUSTER_ARC", "Arc", clusterRadius, tipAngle,
                    clusterArcEdgeId, clusterArcCenterline, centre);
            allRoadsForSnap.add(clusterArcCenterline);

            int spurSnapshot = pctx.slotPoolSize();
            pctx.offerRoadSlots(spurCenterline, 6, 7, TAGS_SPUR_ROAD, 40);

            // Emit cluster slots into a local list, then clamp + offer.
            int clusterFp = 16;
            int clusterPerpOffset = 3 + clusterFp / 2 + 2;
            int clusterStride = 10;
            List<PlacementSlot> clusterCandidates = new ArrayList<>();
            for (int j = 0; j < clusterArcCenterline.size(); j += clusterStride) {
                BlockPos arcPt = clusterArcCenterline.get(j);
                double outDx = arcPt.getX() - squarePos.getX();
                double outDz = arcPt.getZ() - squarePos.getZ();
                double outLen = Math.sqrt(outDx * outDx + outDz * outDz);
                if (outLen < 1) continue;
                int slotX = arcPt.getX()
                        + (int) Math.round(outDx / outLen * clusterPerpOffset);
                int slotZ = arcPt.getZ()
                        + (int) Math.round(outDz / outLen * clusterPerpOffset);
                BlockPos slotPos = pctx.solidSurface(
                        new BlockPos(slotX, arcPt.getY(), slotZ));
                if (pctx.features.isOnWater(slotPos)) continue;
                if (pctx.features.isOnCliff(slotPos)) continue;
                clusterCandidates.add(new PlacementSlot(
                        slotPos, clusterArcCenterline, clusterArcEdgeId,
                        TAGS_SPUR_CLUSTER, clusterFp, clusterFp,
                        null, 65, 0));
            }
            for (PlacementSlot s :
                    clampToRoadReach(clusterCandidates, clusterArcResult, CLAMP_SLACK)) {
                pctx.offerSlot(s);
            }

            List<PlacementSlot> spurSlots = pctx.drainSlotsSince(spurSnapshot);
            if (!spurSlots.isEmpty()) {
                pctx.offerSector(new Sector(
                        "radial_spur_" + i, SectorRole.SPUR_CLUSTER,
                        BuildingZone.PRODUCTION, spurSlots, SPUR_CAPACITY,
                        true, new AddSpur(48, 4), spurEdgeId, null, 16));
            }
        }

        // ── Inner arc roads (slots stay flat; not clamped — feed off spine) ──
        if (spurCount >= 3) {
            System.out.println("RADIAL arcs: spurCount=" + spurCount
                    + " civicRing=" + civicRing + " outerR=" + pctx.density.getRing2Radius());

            int[] arcRadii = {
                    civicRing + (outerR2 - civicRing) / 2,
                    civicRing + (outerR2 - civicRing) * 3 / 4
            };
            double arcCentreAngle = mainDirRad + Math.PI;
            double arcSpan = Math.toRadians(210);
            double arcStartAngle = arcCentreAngle - arcSpan / 2;

            for (int ai = 0; ai < arcRadii.length; ai++) {
                int r = arcRadii[ai];
                if (r < civicRing + 6) {
                    System.out.println("RADIAL arc " + ai + " skipped: r=" + r);
                    continue;
                }
                double drift = ai == 0 ? 4.0 : 5.5;
                RoadPrimitive.Arc arc = new RoadPrimitive.Arc(
                        squarePos, r, arcStartAngle, arcSpan, drift,
                        RoadShape.RoadTier.VILLAGE_PATH);
                int beforeArcEdges = pctx.layout.getRoadGraph().edgeCount();
                List<BlockPos> arcCenterline = pctx.layout.addRoad(
                        arc, pctx.level, pctx.worldSeed);
                int arcEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeArcEdges
                        ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;
                radialEdgeLog("ARC_" + ai, "Arc", r, arcCentreAngle,
                        arcEdgeId, arcCenterline, centre);
                allRoadsForSnap.add(arcCenterline);
                pctx.offerRoadSlots(arcCenterline, 10, 7, TAGS_ARC, 30);
            }
        }

        // ── Shared outer-ring road (consumed by AGRI + DEFENSE bands) ─────
        int ringR = outerR2 + 20;
        RoadPrimitive.Ring outerRing = new RoadPrimitive.Ring(
                centre, ringR, 2.0, RoadShape.RoadTier.VILLAGE_PATH);
        // Probe-record so the bands can clamp to the ring's actual reach.
        RoadResult outerRingResult = computeAndRecord(outerRing, pctx);
        int beforeRingEdges = pctx.layout.getRoadGraph().edgeCount();
        List<BlockPos> outerRingCenterline = pctx.layout.addRoad(
                outerRing, pctx.level, pctx.worldSeed, EdgeRole.OUTER_RING);
        int outerRingEdgeId = pctx.layout.getRoadGraph().edgeCount() > beforeRingEdges
                ? pctx.layout.getRoadGraph().edgeCount() - 1 : -1;
        radialEdgeLog("OUTER_RING", "Ring", ringR, 0.0,
                outerRingEdgeId, outerRingCenterline, centre);
        allRoadsForSnap.add(outerRingCenterline);
        if (outerRingEdgeId >= 0) {
            pctx.setOuterRing(outerRingEdgeId, outerRingCenterline, ringR);
        }

        // ── Outer agri RingBand → AGRICULTURAL_FRINGE sector ──────────────
        int agriSnapshot = pctx.slotPoolSize();
        LayoutPrimitive.RingBand agriBand = new LayoutPrimitive.RingBand(
                centre, outerR2 + 4, outerR2 + 20,
                BuildingZone.AGRICULTURAL,
                new ArrayList<>(), allRoadsForSnap);
        agriBand.emitSlots(pctx);
        List<PlacementSlot> agriRaw = pctx.drainSlotsSince(agriSnapshot);
        List<PlacementSlot> agriSlots =
                clampToRoadReach(agriRaw, outerRingResult, CLAMP_SLACK);
        radialRingLog("AGRI", outerR2 + 4, outerR2 + 20, agriSlots, centre);
        if (!agriSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "radial_outer_agri", SectorRole.AGRICULTURAL_FRINGE,
                    BuildingZone.AGRICULTURAL, agriSlots,
                    UNLIMITED_CAPACITY, true, new AddRing(16, 8),
                    outerRingEdgeId, null, 18));
        }

        // ── Outer defensive RingBand → DEFENSIVE_FRINGE sector ────────────
        int defenseSnapshot = pctx.slotPoolSize();
        LayoutPrimitive.RingBand defenseBand = new LayoutPrimitive.RingBand(
                centre, outerR2, outerR2 + 10,
                BuildingZone.DEFENSIVE,
                new ArrayList<>(), allRoadsForSnap);
        defenseBand.emitSlots(pctx);
        List<PlacementSlot> defenseRaw = pctx.drainSlotsSince(defenseSnapshot);
        List<PlacementSlot> defenseSlots =
                clampToRoadReach(defenseRaw, outerRingResult, CLAMP_SLACK);
        radialRingLog("DEFENSE", outerR2, outerR2 + 10, defenseSlots, centre);
        if (!defenseSlots.isEmpty()) {
            pctx.offerSector(new Sector(
                    "radial_outer_defense", SectorRole.DEFENSIVE_FRINGE,
                    BuildingZone.DEFENSIVE, defenseSlots,
                    UNLIMITED_CAPACITY, true, new AddRing(12, 6),
                    outerRingEdgeId, null, 18));
        }

        return null;  // OK — no re-emit
    }

    @Override
    protected RecipeStatus reEmit(ReEmitReason reason, PlanContext pctx) {
        if (reason instanceof ReEmitReason.SevereTruncation) {
            pctx.recordTruncation();
            // One rotation attempt; if rotation also fails the engine
            // will call us again and we'll fall back.
            if (pctx.cascadeRetryCount() < 1) {
                pctx.recordCascadeRetry();
                pctx.setCascadeAxisRotation(
                        pctx.cascadeAxisRotation() + Math.PI / 2);
                return RecipeStatus.RETRY;
            }
            return RecipeStatus.FALLBACK;
        }
        return super.reEmit(reason, pctx);
    }

    @Override
    protected ShapeType fallbackShape() {
        return ShapeType.LINEAR;
    }

    @Override
    protected void composeSectors(PlanContext pctx) {
        // RadialRecipe is cascade-aware: the body lives in composeOnce.
        // composeSectors is required by BaseRecipe but never invoked when
        // compose() routes through runWithCascade.
        throw new UnsupportedOperationException(
                "RadialRecipe goes through composeOnce; composeSectors not used");
    }

    @Override
    protected void registerAnchors(PlanContext pctx) {
        super.registerAnchors(pctx);
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

    // ── Diagnostic helpers (no behavior change) ──────────────────────────

    private static int chebR(BlockPos p, BlockPos centre) {
        return Math.max(Math.abs(p.getX() - centre.getX()),
                        Math.abs(p.getZ() - centre.getZ()));
    }

    private static void radialEdgeLog(String role, String primitive,
            int requestedR, double requestedAngle,
            int edgeId, List<BlockPos> cl, BlockPos centre) {
        if (cl.isEmpty()) {
            System.out.println("[RADIAL-RADIUS] edge#" + edgeId
                    + " role=" + role + " primitive=" + primitive
                    + " requestedR=" + requestedR
                    + " requestedAngle=" + String.format("%.1f", Math.toDegrees(requestedAngle)) + "deg"
                    + " pts=0 (empty)");
            return;
        }
        BlockPos first = cl.get(0);
        BlockPos last  = cl.get(cl.size() - 1);
        BlockPos mid   = cl.get(cl.size() / 2);
        int firstR = chebR(first, centre);
        int lastR  = chebR(last,  centre);
        int midR   = chebR(mid,   centre);
        int minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
        long sumR = 0; int count = 0;
        for (int i = 0; i < cl.size(); i += 8) {
            int r = chebR(cl.get(i), centre);
            if (r < minR) minR = r;
            if (r > maxR) maxR = r;
            sumR += r; count++;
        }
        int meanR  = count > 0 ? (int)(sumR / count) : 0;
        int rRange = maxR == Integer.MIN_VALUE ? 0 : maxR - minR;
        System.out.println("[RADIAL-RADIUS] edge#" + edgeId
                + " role=" + role + " primitive=" + primitive
                + " requestedR=" + requestedR
                + " requestedAngle=" + String.format("%.1f", Math.toDegrees(requestedAngle)) + "deg"
                + " pts=" + cl.size()
                + " firstPt=" + first.toShortString() + " lastPt=" + last.toShortString()
                + " firstR=" + firstR + " lastR=" + lastR + " midR=" + midR
                + " meanR=" + meanR + " rRange=" + rRange);
    }

    private static void radialRingLog(String name, int inner, int outer,
            List<PlacementSlot> slots, BlockPos centre) {
        if (slots.isEmpty()) {
            System.out.println("[RADIAL-RADIUS] RingBand " + name
                    + " inner=" + inner + " outer=" + outer + " slotCount=0");
            return;
        }
        StringBuilder sb = new StringBuilder();
        long sumR = 0;
        for (PlacementSlot s : slots) {
            int r = chebR(s.pos(), centre);
            if (sb.length() > 0) sb.append(',');
            sb.append(r);
            sumR += r;
        }
        int meanSlotR = (int)(sumR / slots.size());
        System.out.println("[RADIAL-RADIUS] RingBand " + name
                + " inner=" + inner + " outer=" + outer
                + " slotCount=" + slots.size()
                + " slotR=[" + sb + "]"
                + " meanSlotR=" + meanSlotR);
    }
}
