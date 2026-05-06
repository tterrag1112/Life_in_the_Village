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
 * Phase D: declarative port of PLAZA — urban village around a large
 * central plaza with 4 diagonal spurs and outer rings.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>CIRCLE plaza at centre.</li>
 *   <li>4 spurs at NE/NW/SE/SW (Spur primitives), main spur is the
 *       one closest to {@code terrain.bestFlatDir()} (longer + wider
 *       VILLAGE_ROAD tier); the other 3 are VILLAGE_PATH.</li>
 *   <li>Spur length = {@code ring2*2 + 24} (main) or
 *       {@code ring1 + ring2/2} (others).</li>
 *   <li>Agri ring at {@code [ring2+10, ring2+28]} (midpoint
 *       {@code ring2+19}).</li>
 *   <li>Defense ring at {@code [ring2+4, ring2+14]} (midpoint
 *       {@code ring2+9}).</li>
 *   <li>Straggler residential band at {@code [ring1, ring2]}
 *       (midpoint {@code (ring1+ring2)/2}).</li>
 * </ul>
 *
 * <p>Quirk-bundle: terrain-too-rough fallback marks unplannable
 * (was inline RADIAL fallback pre-Phase-A).
 *
 * <p>Spurs use a synthetic 2-point parent centerline
 * {@code [squarePos, branchHint]} (matches pre-Phase-A) since there's
 * no actual ring road for spurs to branch from.
 */
public final class PlazaRecipe extends BaseRecipe {

    private static final int SPUR_COUNT = 4;
    private static final int SPUR_CAPACITY = 6;
    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SECONDARY_CIVIC =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_SPUR_CLUSTER = EnumSet.of(
            SlotTag.PRODUCTION_CLUSTER, SlotTag.PRODUCTION_SPUR_END,
            SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_AGRI = EnumSet.of(
            SlotTag.FIELD_EDGE, SlotTag.PASTURE, SlotTag.BACKFILL);
    private static final Set<SlotTag> TAGS_DEFENSE = EnumSet.of(
            SlotTag.WALL_ADJACENT, SlotTag.HIGH_GROUND,
            SlotTag.RESIDENTIAL_OUTER);
    private static final Set<SlotTag> TAGS_STRAGGLER = EnumSet.of(
            SlotTag.RESIDENTIAL_INFILL, SlotTag.ROAD_ADJACENT,
            SlotTag.BACKFILL);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        BlockPos centre = pctx.layout.getCenter();
        TerrainProfile terrain = pctx.layout.getTerrain();
        int totalBuildings = pctx.remaining.size();
        int ring1 = pctx.density.getRing1Radius();
        int ring2 = pctx.density.getRing2Radius();

        // Terrain pre-check (pre-Phase-A behavior).
        int largest = RecipeHelpers.largestRotatedFootprint(pctx);
        int requiredFlatSide = largest + 4;
        int available = terrain.largestFlatPatchAvailable(2);
        if (available < requiredFlatSide) {
            pctx.layout.markUnplannable(
                    "PLAZA terrain too rough — needs >=" + requiredFlatSide
                    + " flat side, available=" + available);
            return LayoutBlueprint.empty(ShapeType.PLAZA);
        }

        // Choose main-spur direction nearest terrain.bestFlatDir.
        double[] spurAngles = {
                Math.PI / 4,           // SE
                3 * Math.PI / 4,       // SW
                -Math.PI / 4,          // NE
                -3 * Math.PI / 4       // NW
        };
        double terrainRad = directionRadOf(terrain.bestFlatDir())
                + pctx.cascadeAxisRotation();
        int mainSpurIdx = 0;
        double bestDelta = Double.MAX_VALUE;
        for (int i = 0; i < spurAngles.length; i++) {
            double delta = Math.abs(angleDelta(spurAngles[i], terrainRad));
            if (delta < bestDelta) { bestDelta = delta; mainSpurIdx = i; }
        }

        // Spurs branch from a synthetic 2-point parent (squarePos,
        // branchHint) — same as pre-Phase-A.
        int branchOffset = ring1 + 2; // civicRing estimate
        List<RoadDeclaration> roads = new ArrayList<>();
        List<EdgeRef> spurRefs = new ArrayList<>();
        BlockPos mainGate = null;
        for (int i = 0; i < SPUR_COUNT; i++) {
            double spurAngle = spurAngles[i];
            boolean isMain = (i == mainSpurIdx);
            int spurLength = isMain ? ring2 * 2 + 24 : ring1 + ring2 / 2;
            double drift = isMain ? 7.0 : 4.0;
            RoadShape.RoadTier tier = isMain
                    ? RoadShape.RoadTier.VILLAGE_ROAD
                    : RoadShape.RoadTier.VILLAGE_PATH;

            BlockPos branchHint = new BlockPos(
                    centre.getX() + (int) Math.round(Math.cos(spurAngle) * branchOffset),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(Math.sin(spurAngle) * branchOffset));
            List<BlockPos> synthParent = List.of(centre, branchHint);

            EdgeRef spurRef = EdgeRef.of("plaza_spur_" + i);
            spurRefs.add(spurRef);
            roads.add(new RoadDeclaration(spurRef,
                    EdgeRole.SPUR, tier,
                    new RoadPrimitive.Spur(synthParent, branchHint,
                            spurAngle, spurLength, drift, tier)));

            if (isMain) {
                mainGate = new BlockPos(
                        centre.getX() + (int) Math.round(Math.cos(spurAngle)
                                * (branchOffset + spurLength)),
                        centre.getY(),
                        centre.getZ() + (int) Math.round(Math.sin(spurAngle)
                                * (branchOffset + spurLength)));
            }
        }

        SectorRef civicSec = SectorRef.of("plaza_civic_ring");
        var plaza = new PlazaDeclaration(centre, ring1,
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(2, Math.min(7, totalBuildings / 4));

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("plaza_civic_ring",
                SectorRole.CIVIC_RING, BuildingZone.CIVIC,
                civicCap, 46, null));
        for (int i = 0; i < SPUR_COUNT; i++) {
            sectors.add(new SectorDeclaration(
                    "plaza_spur_" + i, SectorRole.SPUR_CLUSTER,
                    BuildingZone.PRODUCTION,
                    SPUR_CAPACITY, 16, spurRefs.get(i)));
        }
        sectors.add(new SectorDeclaration("plaza_outer_agri",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, null));
        sectors.add(new SectorDeclaration("plaza_outer_defense",
                SectorRole.DEFENSIVE_FRINGE, BuildingZone.DEFENSIVE,
                UNLIMITED_CAPACITY, 18, null));
        sectors.add(new SectorDeclaration("plaza_straggler_residential",
                SectorRole.RESIDENTIAL_INFILL, BuildingZone.RESIDENTIAL,
                UNLIMITED_CAPACITY, 16, null));

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 46, new Anchor.PlazaPerimeter(civicSec, 6)));
        intentions.add(new SlotIntention(civicSec, TAGS_SECONDARY_CIVIC,
                Math.max(0, civicCap - 1), 46,
                new Anchor.PlazaPerimeter(civicSec, 6)));
        for (int i = 0; i < SPUR_COUNT; i++) {
            intentions.add(new SlotIntention(
                    SectorRef.of("plaza_spur_" + i),
                    TAGS_SPUR_CLUSTER,
                    SPUR_CAPACITY, 16,
                    new Anchor.RoadAlong(spurRefs.get(i), 6, true)));
        }
        intentions.add(new SlotIntention(
                SectorRef.of("plaza_outer_agri"),
                TAGS_AGRI, 8, 18,
                new Anchor.RingValidated(ring2 + 19, 0.0)));
        intentions.add(new SlotIntention(
                SectorRef.of("plaza_outer_defense"),
                TAGS_DEFENSE, 6, 18,
                new Anchor.RingValidated(ring2 + 9, 0.0)));
        intentions.add(new SlotIntention(
                SectorRef.of("plaza_straggler_residential"),
                TAGS_STRAGGLER, 6, 16,
                new Anchor.RingValidated((ring1 + ring2) / 2, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, centre);
        if (mainGate != null) {
            namedAnchors.put(AnchorKind.MAIN_GATE, mainGate);
        }

        return new LayoutBlueprint(
                ShapeType.PLAZA, roads,
                List.of(plaza), sectors, intentions, namedAnchors);
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
                    yield compose(pctx);
                }
                yield null;
            }
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
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
