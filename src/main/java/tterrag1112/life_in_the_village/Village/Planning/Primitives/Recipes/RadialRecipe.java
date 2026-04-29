package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.LayoutPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.Zoning.SlotTag;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * RADIAL layout. Slice-5: emits slots only; no claimByZone calls.
 * The matcher, running after compose() returns, handles all placement.
 *
 * <p>Geometry is identical to the pre-slice version — same main road,
 * same spur angles, same arc roads. What changed: primitives no longer
 * claim buildings; they stamp the map with slot metadata and let the
 * matcher do the assignment.
 */
public final class RadialRecipe implements ShapeRecipe {

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
    private static final Set<SlotTag> TAGS_OUTER_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_OUTER_DEFENSE =
            EnumSet.of(SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND,
                    SlotTag.RESIDENTIAL_OUTER);

    @Override
    public void compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();

        // Phase 18 doc 04 — polygon plaza is now the single source of
        // truth. installPlaza generates the polygon, emits PRIME_CIVIC
        // / SECONDARY_CIVIC slots along the polygon edge for the
        // matcher's prepass, registers gathering points, and sets
        // townSquarePos / Radius / civicRingRadius for downstream
        // consumers. The legacy LayoutPrimitive.TownSquare primitive
        // is gone.
        RecipeHelpers.installPlaza(pctx, centre,
                tterrag1112.life_in_the_village.Village.Decoration
                        .Plaza.PlazaShape.CIRCLE);
        BlockPos squarePos = pctx.layout.getTownSquarePos();

        // Civic-count drives the gap-angle search below (where the main
        // road exits the plaza). The legacy LayoutPrimitive.TownSquare
        // sized this from buildingCapacity; preserve the same formula
        // here so the road geometry is unchanged.
        int totalBuildings = pctx.remaining.size();
        int squareCapacity = Math.max(6, Math.min(10, totalBuildings / 4 + 3));

        // ── Main road (unchanged geometry) ─────────────────────────────────
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
        int civicRing = pctx.layout.getCivicRingRadius();
        int extendedRing = civicRing + 20;
        BlockPos mainStart = new BlockPos(
                squarePos.getX() + (int) Math.round(Math.cos(mainDirRad) * extendedRing),
                squarePos.getY(),
                squarePos.getZ() + (int) Math.round(Math.sin(mainDirRad) * extendedRing));
        mainStart = pctx.solidSurface(mainStart);
        int densityScale = Math.max(1, pctx.density.getRing2Radius());
        int outerR = civicRing + 40 + densityScale;  // ~65-85 for typical villages

        int mainLength = outerR * 2 + 48;
        BlockPos mainEnd = new BlockPos(
                mainStart.getX() + (int) Math.round(Math.cos(mainDirRad) * mainLength),
                mainStart.getY(),
                mainStart.getZ() + (int) Math.round(Math.sin(mainDirRad) * mainLength));
        mainEnd = pctx.solidSurface(mainEnd);

        RoadPrimitive.StraightRoad mainRoad = new RoadPrimitive.StraightRoad(
                mainStart, mainEnd, 8.0, RoadShape.RoadTier.VILLAGE_ROAD);
        List<BlockPos> mainCenterline = pctx.layout.addRoad(
                mainRoad, pctx.level, pctx.worldSeed);

        pctx.layout.setMainGateEndpoint(mainEnd);
        pctx.layout.addGatePosition(mainStart);
        pctx.layout.addGatePosition(mainEnd);

        // Stamp the main road with backfill slots for houses/wells
        pctx.offerRoadSlots(mainCenterline, 8, 8, TAGS_MAIN_ROAD, 35);

        // ── Spur count (unchanged) ─────────────────────────────────────────
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

        // ── Spurs: emit slots at the tip AND along the length ──────────────
        for (int i = 0; i < spurCount; i++) {
            BlockPos branchHint;
            double spurAngle;
            if (i < squareRooted) {
                spurAngle = mainDirRad + Math.PI + (i - squareRooted / 2.0) * spurArc;
                branchHint = new BlockPos(
                        squarePos.getX() + (int) Math.round(Math.cos(spurAngle) * extendedRing),
                        squarePos.getY(),
                        squarePos.getZ() + (int) Math.round(Math.sin(spurAngle) * extendedRing));
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
            List<BlockPos> spurCenterline = pctx.layout.addRoad(
                    spur, pctx.level, pctx.worldSeed);
            allRoadsForSnap.add(spurCenterline);

            // Stamp along the spur for infill
            pctx.offerRoadSlots(spurCenterline, 6, 7, TAGS_SPUR_ROAD, 40);

            // Cluster at the spur tip — PRODUCTION_SPUR_END slots. Use a
            // placeholder buildings list (sized to remaining/spurCount)
            // so BuildingCircle's math has something to size against.
            // The matcher, not the circle, will assign real buildings.
            BlockPos focal = spurCenterline.get(spurCenterline.size() - 1);
            int placeholderCount = Math.max(3, remaining / spurCount);
            List<tterrag1112.life_in_the_village.Village.VillageTypeData
                    .StarterBuilding> placeholder = new ArrayList<>();
            // Copy a slice of remaining just for sizing purposes — does
            // NOT consume from remaining. This is a quirk of the current
            // BuildingCircle signature; slice 6 will clean it up.
            int copy = Math.min(placeholderCount, pctx.remaining.size());
            for (int k = 0; k < copy; k++) {
                placeholder.add(pctx.remaining.get(k));
            }

            LayoutPrimitive.BuildingCircle circle =
                    new LayoutPrimitive.BuildingCircle(
                            focal,
                            LayoutPrimitive.BuildingCircle.Mode.SCATTER,
                            placeholder, spurCenterline);
            circle.emitSlotsWithTags(pctx, TAGS_SPUR_CLUSTER, 65);
        }

        // ── Arc roads (RADIAL's signature concentric half-arcs) ────────────
        if (spurCount >= 3) {
            System.out.println("RADIAL arcs: spurCount=" + spurCount
                    + " civicRing=" + civicRing + " outerR=" + pctx.density.getRing2Radius());

            int[] arcRadii = {
                    civicRing + (outerR - civicRing) / 2,
                    civicRing + (outerR - civicRing) * 3 / 4
            };
            double arcCentreAngle = mainDirRad + Math.PI;
            double arcSpan = Math.toRadians(210);
            double arcStartAngle = arcCentreAngle - arcSpan / 2;

            for (int ai = 0; ai < arcRadii.length; ai++) {
                int r = arcRadii[ai];
                if (r < civicRing + 6) {
                    System.out.println("RADIAL arc " + ai + " skipped: r=" + r);
                    continue;}
                System.out.println("RADIAL arc " + ai + " requested: r=" + r);

                double drift = ai == 0 ? 4.0 : 5.5;
                RoadPrimitive.Arc arc = new RoadPrimitive.Arc(
                        squarePos, r, arcStartAngle, arcSpan, drift,
                        RoadShape.RoadTier.VILLAGE_PATH);
                List<BlockPos> arcCenterline = pctx.layout.addRoad(
                        arc, pctx.level, pctx.worldSeed);
                allRoadsForSnap.add(arcCenterline);
                pctx.offerRoadSlots(arcCenterline, 10, 7, TAGS_ARC, 30);
            }
        }

        // ── Outer agri RingBand: emit slots only ───────────────────────────
        LayoutPrimitive.RingBand agriBand = new LayoutPrimitive.RingBand(
                centre,
                outerR + 4,
                outerR + 20,
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.AGRICULTURAL,
                new ArrayList<>(),   // no building list — matcher assigns
                allRoadsForSnap);
        // RingBand.emitSlots reads `buildings.size()` for the count but
        // falls back to max(_, 8). Empty list is fine.
        agriBand.emitSlots(pctx);

        // ── Outer defensive RingBand: same pattern ─────────────────────────
        LayoutPrimitive.RingBand defenseBand = new LayoutPrimitive.RingBand(
                centre,
                outerR,
                outerR + 10,
                tterrag1112.life_in_the_village.Village.Planning.BuildingZone.DEFENSIVE,
                new ArrayList<>(),
                allRoadsForSnap);
        defenseBand.emitSlots(pctx);
        // No explicit residential band — RESIDENTIAL_INFILL and BACKFILL
        // tags on the spur/main/arc road slots absorb houses. If you
        // observe houses failing to place in testing, add an explicit
        // residential RingBand here.
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