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
 * Phase D: declarative port of TERRACED — village built on a moderate
 * slope with stepped rows. {@value #TERRACE_COUNT} parallel terrace
 * roads run along contour lines (perpendicular to slope direction)
 * at decreasing Y, connected by Stairway ramps in a zig-zag pattern.
 *
 * <p>Pre-Phase-A pattern preserved:
 * <ul>
 *   <li>Slope detection via {@code terrain.hasSlope()} +
 *       {@code terrain.slopeDir()} (recipe-internal feature query).</li>
 *   <li>Falls back to {@link LinearRecipe} when no slope.</li>
 *   <li>4 terrace roads (StraightRoad VILLAGE_PATH, drift 3.0)
 *       perpendicular to slope, stepped by TERRACE_XZ_STEP=14 along
 *       the slope vector.</li>
 *   <li>Zig-zag Stairway ramps connect adjacent terraces (right side
 *       for even-i, left side for odd-i).</li>
 *   <li>Plaza on the middle terrace.</li>
 *   <li>Per-terrace sectors with tiered tags
 *       (UPPER/MID/LOWER_TERRACE).</li>
 *   <li>Base agri ring downhill of the bottom terrace.</li>
 * </ul>
 *
 * <p>Quirk resolution: {@code pctx.allowRidgePlacement = true} flag
 * is recipe-internal and survives — set in compose at the start.
 * SlotEmitter's FeatureMap check still gates terrain validity per
 * candidate.
 */
public final class TerracedRecipe extends BaseRecipe {

    private static final int TERRACE_COUNT = 4;
    private static final int TERRACE_XZ_STEP = 14;
    private static final int UNLIMITED_CAPACITY = 1024;

    private static final Set<SlotTag> TAGS_PRIME_CIVIC =
            EnumSet.of(SlotTag.PRIME_CIVIC, SlotTag.PLAZA_ADJACENT,
                    SlotTag.ROAD_ADJACENT);
    private static final Set<SlotTag> TAGS_UPPER_TERRACE =
            EnumSet.of(SlotTag.SECONDARY_CIVIC, SlotTag.PRODUCTION_CLUSTER,
                    SlotTag.HIGH_GROUND, SlotTag.ROAD_ADJACENT,
                    SlotTag.TERRACE_EDGE);
    private static final Set<SlotTag> TAGS_MID_TERRACE =
            EnumSet.of(SlotTag.RESIDENTIAL_INFILL, SlotTag.CIVIC_ADJACENT,
                    SlotTag.ROAD_ADJACENT, SlotTag.TERRACE_EDGE);
    private static final Set<SlotTag> TAGS_LOWER_TERRACE =
            EnumSet.of(SlotTag.RESIDENTIAL_INFILL, SlotTag.RESIDENTIAL_OUTER,
                    SlotTag.PRODUCTION_INFILL, SlotTag.ROAD_ADJACENT,
                    SlotTag.TERRACE_EDGE);
    private static final Set<SlotTag> TAGS_BASE_AGRI =
            EnumSet.of(SlotTag.FIELD_EDGE, SlotTag.PASTURE,
                    SlotTag.TERRACE_EDGE);

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        TerrainProfile terrain = pctx.layout.getTerrain();
        BlockPos centre = pctx.layout.getCenter();
        int totalBuildings = pctx.remaining.size();
        int ring1 = pctx.density.getRing1Radius();
        int ring2 = pctx.density.getRing2Radius();

        if (!terrain.hasSlope()) {
            // Inline delegation to LinearRecipe — pre-Phase-A behavior.
            return new LinearRecipe().compose(pctx);
        }

        // Pre-Phase-A flag — allow ridge-placement in slot
        // emission. SlotEmitter's FeatureMap check still gates per-
        // candidate terrain validity.
        pctx.allowRidgePlacement = true;

        TerrainAnalyzer.FlatDirection slopeDir = terrain.slopeDir();
        double slopeRad = directionRadOf(slopeDir);
        double terraceHeadingRad = slopeRad + Math.PI / 2;
        double tanX = Math.cos(terraceHeadingRad);
        double tanZ = Math.sin(terraceHeadingRad);
        double slopeUx = Math.cos(slopeRad);
        double slopeUz = Math.sin(slopeRad);

        int terraceLength = Math.max(48,
                totalBuildings * 4 + ring2 / 2);
        int middleIndex = TERRACE_COUNT / 2;

        // Build terrace endpoints + roads.
        List<RoadDeclaration> roads = new ArrayList<>();
        List<EdgeRef> terraceRefs = new ArrayList<>();
        List<BlockPos> terraceMids = new ArrayList<>();
        List<BlockPos> terraceStarts = new ArrayList<>();
        List<BlockPos> terraceEnds = new ArrayList<>();

        for (int i = 0; i < TERRACE_COUNT; i++) {
            int slopeOffset = (i - middleIndex) * TERRACE_XZ_STEP;
            BlockPos terraceMid = pctx.solidSurface(new BlockPos(
                    centre.getX() - (int) Math.round(slopeUx * slopeOffset),
                    centre.getY(),
                    centre.getZ() - (int) Math.round(slopeUz * slopeOffset)));
            BlockPos terraceStart = pctx.solidSurface(new BlockPos(
                    terraceMid.getX() - (int) Math.round(tanX * terraceLength / 2.0),
                    terraceMid.getY(),
                    terraceMid.getZ() - (int) Math.round(tanZ * terraceLength / 2.0)));
            BlockPos terraceEnd = pctx.solidSurface(new BlockPos(
                    terraceMid.getX() + (int) Math.round(tanX * terraceLength / 2.0),
                    terraceMid.getY(),
                    terraceMid.getZ() + (int) Math.round(tanZ * terraceLength / 2.0)));

            EdgeRef terraceRef = EdgeRef.of("terraced_terrace_" + i);
            terraceRefs.add(terraceRef);
            terraceMids.add(terraceMid);
            terraceStarts.add(terraceStart);
            terraceEnds.add(terraceEnd);

            roads.add(new RoadDeclaration(terraceRef,
                    EdgeRole.SPINE, RoadShape.RoadTier.VILLAGE_PATH,
                    new RoadPrimitive.StraightRoad(
                            terraceStart, terraceEnd, 3.0,
                            RoadShape.RoadTier.VILLAGE_PATH)));
        }

        // Stairway ramps connecting adjacent terraces.
        for (int i = 0; i < TERRACE_COUNT - 1; i++) {
            boolean rightSide = (i % 2 == 0);
            BlockPos upperPos = rightSide ? terraceEnds.get(i) : terraceStarts.get(i);
            BlockPos lowerPos = rightSide ? terraceEnds.get(i + 1) : terraceStarts.get(i + 1);
            EdgeRef rampRef = EdgeRef.of("terraced_ramp_" + i);
            roads.add(new RoadDeclaration(rampRef,
                    EdgeRole.SPUR, RoadShape.RoadTier.VILLAGE_PATH,
                    new RoadPrimitive.Stairway(
                            lowerPos, upperPos, RoadShape.RoadTier.VILLAGE_PATH)));
        }

        // Main gate at bottom terrace's approach end (opposite the
        // ramp's connection point).
        boolean lowestConnectsRight = ((TERRACE_COUNT - 2) % 2 == 0);
        BlockPos mainGate = lowestConnectsRight
                ? terraceStarts.get(TERRACE_COUNT - 1)
                : terraceEnds.get(TERRACE_COUNT - 1);

        // Plaza on middle terrace.
        BlockPos squareCentre = terraceMids.get(middleIndex);
        SectorRef civicSec = SectorRef.of("terraced_civic");
        var plaza = new PlazaDeclaration(squareCentre, ring1,
                PlazaShape.CIRCLE, PlazaPurpose.CIVIC, civicSec);

        int civicCap = Math.max(3, Math.min(6, totalBuildings / 6 + 2));

        List<SectorDeclaration> sectors = new ArrayList<>();
        sectors.add(new SectorDeclaration("terraced_civic",
                SectorRole.CIVIC_TIGHT, BuildingZone.CIVIC,
                civicCap, 32, null));
        for (int i = 0; i < TERRACE_COUNT; i++) {
            BuildingZone zoneHint;
            int capacity;
            if (i < middleIndex) {
                zoneHint = BuildingZone.CIVIC;
                capacity = 6;
            } else if (i == middleIndex) {
                zoneHint = BuildingZone.RESIDENTIAL;
                capacity = 8;
            } else {
                zoneHint = BuildingZone.RESIDENTIAL;
                capacity = 8;
            }
            sectors.add(new SectorDeclaration(
                    "terraced_terrace_" + i, SectorRole.RESIDENTIAL_INFILL,
                    zoneHint, capacity, 16, terraceRefs.get(i)));
        }
        sectors.add(new SectorDeclaration("terraced_base_agri",
                SectorRole.AGRICULTURAL_FRINGE, BuildingZone.AGRICULTURAL,
                UNLIMITED_CAPACITY, 18, null));

        List<SlotIntention> intentions = new ArrayList<>();
        intentions.add(new SlotIntention(civicSec, TAGS_PRIME_CIVIC,
                1, 32, new Anchor.PlazaPerimeter(civicSec, 4)));
        for (int i = 0; i < TERRACE_COUNT; i++) {
            Set<SlotTag> tags;
            if (i < middleIndex) {
                tags = TAGS_UPPER_TERRACE;
            } else if (i == middleIndex) {
                tags = TAGS_MID_TERRACE;
            } else {
                tags = TAGS_LOWER_TERRACE;
            }
            intentions.add(new SlotIntention(
                    SectorRef.of("terraced_terrace_" + i),
                    tags, 6, 16,
                    new Anchor.RoadAlong(terraceRefs.get(i), 8, true)));
        }
        // Base agri downhill of bottom terrace. Approximation: ring at
        // distance from village centre to (lowestMid + slope*ring1)
        // computed via radial-from-centre proxy.
        BlockPos lowestMid = terraceMids.get(TERRACE_COUNT - 1);
        int agriOffsetX = lowestMid.getX() + (int) Math.round(slopeUx * ring1)
                - centre.getX();
        int agriOffsetZ = lowestMid.getZ() + (int) Math.round(slopeUz * ring1)
                - centre.getZ();
        int agriRadius = (int) Math.round(Math.sqrt(
                agriOffsetX * agriOffsetX + agriOffsetZ * agriOffsetZ));
        intentions.add(new SlotIntention(
                SectorRef.of("terraced_base_agri"),
                TAGS_BASE_AGRI, 6, 18,
                new Anchor.RingValidated(agriRadius + ring1 / 2, 0.0)));

        Map<AnchorKind, BlockPos> namedAnchors = new LinkedHashMap<>();
        namedAnchors.put(AnchorKind.TOWN_SQUARE, squareCentre);
        namedAnchors.put(AnchorKind.MAIN_GATE, mainGate);

        return new LayoutBlueprint(
                ShapeType.TERRACED, roads,
                List.of(plaza), sectors, intentions, namedAnchors);
    }

    @Override
    public LayoutBlueprint reEmit(ReEmitReason reason, PlanContext pctx) {
        return switch (reason) {
            // No axis rotation — slopeDir is terrain-determined.
            case ReEmitReason.SevereTruncation t -> null;
            case ReEmitReason.SlotsDropped s -> null;
            case ReEmitReason.SectorStarved s -> null;
            case ReEmitReason.ValidationFailed v -> null;
        };
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
