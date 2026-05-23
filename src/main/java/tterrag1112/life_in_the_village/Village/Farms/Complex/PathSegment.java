package tterrag1112.life_in_the_village.Village.Farms.Complex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * Detour A — persisted path segment within a {@link FarmComplex}.
 *
 * <p>Geometry only — start, end, width, spine-vs-branch flag.
 * Block-level rendering (surface material, lighting, slope
 * smoothing) is Prompt B's renderer's concern; the planner just
 * declares "there's a path here, this wide."
 *
 * <p>Mirrors the algorithm-layer {@code PathTopologyPlanner.Segment}
 * — that one is the in-memory output of the planner; this is the
 * codec'able persistent form. Stage 4's orchestrator converts.
 *
 * @param start    one end of the segment.
 * @param end      the other end.
 * @param width    block width of the path surface (default 3).
 * @param isSpine  true ⇒ trunk segment of the spine; false ⇒
 *                 per-plot branch. Distinguished so the renderer
 *                 can use different materials.
 */
public record PathSegment(BlockPos start, BlockPos end, int width, boolean isSpine) {

    public static final Codec<PathSegment> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("start").forGetter(PathSegment::start),
            BlockPos.CODEC.fieldOf("end").forGetter(PathSegment::end),
            Codec.INT.fieldOf("width").forGetter(PathSegment::width),
            Codec.BOOL.fieldOf("isSpine").forGetter(PathSegment::isSpine)
    ).apply(i, PathSegment::new));
}
