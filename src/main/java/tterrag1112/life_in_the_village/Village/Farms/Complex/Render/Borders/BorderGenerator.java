package tterrag1112.life_in_the_village.Village.Farms.Complex.Render.Borders;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

/**
 * Detour A Prompt B — paints a single border column at a pre-
 * resolved ground Y.
 *
 * <p>Concrete implementations are the four style classes
 * ({@code HedgeBorder}, {@code StoneWallBorder},
 * {@code PostAndRailBorder}, {@code DrystoneBorder}); the
 * orchestrator picks one per cell via {@link
 * BorderGeneratorRegistry#get}.
 *
 * <p>Per-column safety (clear head space, soft-replace check)
 * lives in {@code AbstractBorderGenerator} so the four style
 * classes only declare their per-column block recipe.
 *
 * @see AbstractBorderGenerator
 */
public interface BorderGenerator {

    /**
     * Paint a single border column at a pre-resolved ground Y.
     *
     * <p>The orchestrator drives per-cell dispatch (rasterize all
     * plot edges, dedupe XZ across the complex, snapshot ground Y
     * before any placement, paint each cell once). This entry
     * point is the per-cell call.
     *
     * @param level          target server level
     * @param x              world X of the column
     * @param z              world Z of the column
     * @param groundY        snapshot ground Y from BEFORE any
     *                       border placement — passed in so per-
     *                       column placements don't stack on
     *                       earlier border blocks they'd otherwise
     *                       see as "ground" via a live heightmap
     *                       read.
     * @param outwardNormal  unit cardinal pointing AWAY from the
     *                       plot interior; available to styles that
     *                       want a side-aware placement.
     * @param rng            deterministic stream for material
     *                       variation per column.
     */
    void paintColumnAt(ServerLevel level, int x, int z, int groundY,
                       Direction outwardNormal, Random rng);
}
