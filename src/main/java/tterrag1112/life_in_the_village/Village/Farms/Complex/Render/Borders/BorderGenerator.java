package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

/**
 * Detour A Prompt B — paints a single border edge between two
 * grid corners.
 *
 * <p>One {@code renderEdge} call corresponds to one polygon edge.
 * Concrete implementations are the four style classes
 * ({@code HedgeBorder}, {@code StoneWallBorder},
 * {@code PostAndRailBorder}, {@code DrystoneBorder}); the
 * orchestrator picks one per edge via
 * {@link BorderGeneratorRegistry#get}.
 *
 * <p>Per-column safety (skip water/lava, ground-Y lookup, stop
 * above max-build-height) lives in {@code AbstractBorderGenerator}
 * so the four style classes only need to declare their per-
 * column block recipe.
 *
 * @see AbstractBorderGenerator
 */
public interface BorderGenerator {

    /**
     * Paint the border from {@code start} to {@code end}.
     *
     * @param start          one polygon-vertex world position
     * @param end            the other polygon-vertex world position
     * @param outwardNormal  unit cardinal pointing AWAY from the
     *                       plot interior (toward the world outside
     *                       the border). Used by gate-aware styles.
     * @param level          target server level
     * @param rng            deterministic stream for jitter; same
     *                       seed ⇒ same output
     */
    void renderEdge(BlockPos start, BlockPos end,
                    Direction outwardNormal,
                    ServerLevel level,
                    Random rng);
}
