package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.StructureSizeCache;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;

import java.util.List;

/**
 * V2 Phased Planner — Phase 2 (spine planning).
 *
 * <p>Produces a {@link Spine} through (offset from) the anchor along
 * {@code SiteContext.primarySpineDirection}. Length is derived from
 * the total estimated frontage of the selected building list:
 * <pre>
 *     spine_length = max(MIN_SPINE_LENGTH, total_frontage * 0.5)
 * </pre>
 * Each building's contribution is the X-axis dimension of its
 * default-variant footprint (rotation-naïve estimate; HOUSE picks
 * its variant per position later in Phase 4 but the largest is a
 * reasonable upper bound).
 *
 * <p>Perpendicular offset:
 * {@code (townHall.fp.maxSide / 2 + roadWidth / 2 + 1)} blocks so
 * TOWN_HALL can sit adjacent to the spine, not on it. Side: +perp
 * deterministically in V1.
 */
public final class SpinePlanner {

    /** All V1 placement happens at level 1. */
    private static final int LEVEL = 1;
    /** V1 spine width (logical, for frontage math). Distinct from
     *  the painted width V1 RoadShape applies at decoration time. */
    public static final int SPINE_WIDTH = 3;
    /** Lower bound on spine length even for tiny villages. */
    private static final int MIN_SPINE_LENGTH = 40;
    /** Buffer between TOWN_HALL footprint and spine corridor. */
    private static final int TOWN_HALL_BUFFER = 1;

    private SpinePlanner() {}

    public static Spine plan(SiteContext ctx, List<BuildingType> selected,
                             ServerLevel level) {
        StructureSizeCache sizes = new StructureSizeCache(level);
        String culture = ctx.culture().id();

        int totalFrontage = 0;
        int townHallMaxSide = 0;
        for (BuildingType type : selected) {
            String variantId = BuildingVariant.defaultVariantId(type);
            StructureSizeCache.FootprintInfo info = sizes.get(culture, Style.RURAL,
                    type, variantId, LEVEL, Rotation.NONE);
            // Use width as the rotation-naive frontage estimate (the
            // building may rotate at placement; this is an estimate).
            totalFrontage += info.width();
            if (type == BuildingType.TOWN_HALL) {
                townHallMaxSide = Math.max(info.width(), info.length());
            }
        }
        int spineLength = Math.max(MIN_SPINE_LENGTH,
                (int) Math.round(totalFrontage * 0.5));

        BlockPos anchor = ctx.anchor();
        Vec3 dir = unit(ctx.primarySpineDirection());
        double perpX = -dir.z;
        double perpZ = dir.x;

        int offset = townHallMaxSide / 2 + SPINE_WIDTH / 2 + TOWN_HALL_BUFFER;
        int offsetX = (int) Math.round(perpX * offset);
        int offsetZ = (int) Math.round(perpZ * offset);

        int half = spineLength / 2;
        int dx = (int) Math.round(dir.x * half);
        int dz = (int) Math.round(dir.z * half);

        BlockPos start = new BlockPos(
                anchor.getX() - dx + offsetX, anchor.getY(),
                anchor.getZ() - dz + offsetZ);
        BlockPos end = new BlockPos(
                anchor.getX() + dx + offsetX, anchor.getY(),
                anchor.getZ() + dz + offsetZ);
        return new Spine(start, end, SPINE_WIDTH);
    }

    private static Vec3 unit(Vec3 v) {
        double len = Math.sqrt(v.x * v.x + v.z * v.z);
        if (len < 1e-9) return new Vec3(1, 0, 0);
        return new Vec3(v.x / len, 0, v.z / len);
    }
}
