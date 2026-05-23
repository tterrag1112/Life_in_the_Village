package tterrag1112.life_in_the_village.Village.Farms.Complex.Render;

import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Per-culture path-surface palette.
 *
 * <p>Carries the weighted block mix the {@link PathRenderer} samples
 * per cell + the {@code headClearance} blocks of air the renderer
 * keeps above the path.
 *
 * <p>Today every palette has a single entry — the culture's
 * {@code planningBias.roadMaterial} at weight 1.0 — so output is
 * identical to the pre-palette renderer (a single
 * {@code level.setBlock} per cell with the same blockstate every
 * time). When Track E ships palette mixing
 * ({@code dirt_path / coarse_dirt / gravel} per a richer palette
 * system), this is the data shape that consumes it; the renderer
 * itself stays unchanged.
 *
 * <p>{@code blockWeights} entries are normalised at construction:
 * weights must be positive and the sum becomes the cumulative-
 * weight total used by {@link #selectBlock(Random)}.
 * {@link LinkedHashMap} preserves insertion order so the per-cell
 * RNG walk is deterministic across registrations.
 *
 * @param blockWeights insertion-ordered weights per blockstate.
 *                     Must be non-empty; all values strictly positive.
 * @param headClearance number of blocks above the path surface to
 *                      keep clear (matches V2 RoadPainter's
 *                      ROAD_HEAD_CLEARANCE = 3).
 */
public record PathPalette(Map<BlockState, Float> blockWeights,
                          int headClearance) {

    public PathPalette {
        if (blockWeights == null || blockWeights.isEmpty()) {
            throw new IllegalArgumentException(
                    "PathPalette requires at least one blockWeight entry");
        }
        LinkedHashMap<BlockState, Float> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockState, Float> e : blockWeights.entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0f) continue;
            copy.put(e.getKey(), e.getValue());
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "PathPalette: no positive-weight entries");
        }
        blockWeights = java.util.Collections.unmodifiableMap(copy);
        if (headClearance < 0) headClearance = 0;
    }

    /** Single-entry palette — the common case today.
     *  Equivalent to the pre-palette renderer when used. */
    public static PathPalette single(BlockState material, int headClearance) {
        LinkedHashMap<BlockState, Float> one = new LinkedHashMap<>();
        one.put(material, 1.0f);
        return new PathPalette(one, headClearance);
    }

    /** Weighted-random sample. With a single-entry palette this
     *  always returns the sole entry (no RNG advance — we still
     *  call {@link Random#nextFloat} for determinism stability
     *  between single-entry and multi-entry palettes). */
    public BlockState selectBlock(Random rng) {
        float total = 0f;
        for (Float w : blockWeights.values()) total += w;
        float pick = rng.nextFloat() * total;
        float cum = 0f;
        BlockState last = null;
        for (Map.Entry<BlockState, Float> e : blockWeights.entrySet()) {
            last = e.getKey();
            cum += e.getValue();
            if (pick < cum) return last;
        }
        return last;
    }
}
