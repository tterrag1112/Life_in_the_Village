package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Curvature;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;

import java.util.List;

/**
 * V2 Layer 4 spine. A single primary road through the anchor along
 * {@code primarySpineDirection}, length = {@code 2 * villageRadius}.
 *
 * <p>Per the design-doc resolution to "spine through TOWN_HALL":
 * the spine is offset perpendicular to {@code primarySpineDirection}
 * by {@code (townHall.fp.maxSide / 2) + 2} blocks so it grazes
 * TOWN_HALL but doesn't cross it. Side: {@code +perp} deterministically
 * in V1; if TOWN_HALL is absent, no offset.
 *
 * <p>Curvature: STRAIGHT → {@link RoadPrimitive.StraightRoad};
 * CURVED → {@link RoadPrimitive.CurvedRoad} with curvature 0.15;
 * NATURAL → CurvedRoad with curvature 0.10 and seed-deterministic
 * perpendicular side (the bow direction baked into the primitive
 * via the sign of the curvature isn't supported, so we flip the
 * endpoints when seed parity demands).
 */
public final class SpineGenerator {

    /** Drift on the spine's StraightRoad fallback. 0 keeps it crisp. */
    private static final double SPINE_DRIFT = 0.0;
    /** Curvature for {@link Curvature#CURVED}. */
    private static final double CURVATURE_GENTLE = 0.15;
    /** Curvature for {@link Curvature#NATURAL}. */
    private static final double CURVATURE_NATURAL = 0.10;
    /** Buffer between the spine and TOWN_HALL after the offset. */
    private static final int TOWN_HALL_BUFFER = 2;

    private SpineGenerator() {}

    public static Road generate(SiteContext ctx, int villageRadius,
                                List<PlacedBuilding> placed) {
        BlockPos anchor = ctx.anchor();
        Vec3 dir = unit(ctx.primarySpineDirection());
        // Perpendicular in XZ.
        double perpX = -dir.z;
        double perpZ = dir.x;

        int offset = computeTownHallOffset(placed);
        int offsetX = (int) Math.round(perpX * offset);
        int offsetZ = (int) Math.round(perpZ * offset);

        int half = villageRadius;
        int dx = (int) Math.round(dir.x * half);
        int dz = (int) Math.round(dir.z * half);

        BlockPos start = new BlockPos(
                anchor.getX() - dx + offsetX,
                anchor.getY(),
                anchor.getZ() - dz + offsetZ);
        BlockPos end = new BlockPos(
                anchor.getX() + dx + offsetX,
                anchor.getY(),
                anchor.getZ() + dz + offsetZ);

        Culture culture = ctx.culture();
        RoadPrimitive primitive = buildPrimitive(culture, start, end, ctx.seed());
        return new Road(RoadTier.VILLAGE_ROAD, primitive, culture.roadMaterial());
    }

    private static RoadPrimitive buildPrimitive(Culture culture, BlockPos start, BlockPos end,
                                                long seed) {
        RoadShape.RoadTier v1Tier = RoadTier.VILLAGE_ROAD.v1Tier();
        return switch (culture.preferredCurvature()) {
            case STRAIGHT -> new RoadPrimitive.StraightRoad(start, end, SPINE_DRIFT, v1Tier);
            case CURVED -> new RoadPrimitive.CurvedRoad(start, end,
                    CURVATURE_GENTLE, SPINE_DRIFT, v1Tier);
            case NATURAL -> {
                // CurvedRoad's curvature is unsigned; the bow side is
                // determined by the implementation's perpendicular
                // computation. To get a seed-deterministic side, flip
                // start/end on odd seed parity — the resulting curve
                // bows in the opposite direction.
                boolean flip = (seed & 1L) == 1L;
                BlockPos a = flip ? end : start;
                BlockPos b = flip ? start : end;
                yield new RoadPrimitive.CurvedRoad(a, b,
                        CURVATURE_NATURAL, SPINE_DRIFT, v1Tier);
            }
        };
    }

    private static int computeTownHallOffset(List<PlacedBuilding> placed) {
        for (PlacedBuilding pb : placed) {
            if (pb.type() == BuildingType.TOWN_HALL) {
                int max = Math.max(pb.footprint().width(), pb.footprint().length());
                return max / 2 + TOWN_HALL_BUFFER;
            }
        }
        return 0;
    }

    private static Vec3 unit(Vec3 v) {
        double len = Math.sqrt(v.x * v.x + v.z * v.z);
        if (len < 1e-9) return new Vec3(1, 0, 0);
        return new Vec3(v.x / len, 0, v.z / len);
    }
}
