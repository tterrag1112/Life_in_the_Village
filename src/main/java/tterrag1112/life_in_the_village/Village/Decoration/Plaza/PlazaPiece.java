package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import net.minecraft.core.BlockPos;

/**
 * Civic-plaza decoration: one typed sub-piece of a {@link CivicPlazaComplex},
 * the plaza analogue of the farm complex's {@code FarmPlot.PlotSubtype}. A
 * planner assigns pieces over the paved CIVIC {@link PlazaRegion}; the {@link
 * PlazaPieceRenderer} dispatches per {@link Kind}.
 *
 * @param kind   the piece type
 * @param pos    the piece centre (plaza-floor Y)
 * @param radius rough half-extent in blocks (gardens) / unused for the fixed
 *               NBT fountain
 */
public record PlazaPiece(Kind kind, BlockPos pos, int radius) {

    /**
     * Piece kinds. v1 populates {@link #FOUNTAIN}, {@link #GARDEN}, {@link
     * #OPEN}; the rest are staged framework slots the renderer accepts but the
     * planner doesn't emit yet (a later decoration pass fills them).
     */
    public enum Kind {
        /** Central stone-well fountain (NBT) + a FOUNTAIN gathering point. */
        FOUNTAIN,
        /** Perimeter greenery (flowers / hedge / planter) on the paving. */
        GARDEN,
        /** Open cobbled paving — left as the paver laid it (no-op render). */
        OPEN,
        // ── Staged for a later pass (framework accepts; planner doesn't emit) ──
        WELL,
        MARKET_STALL,
        NOTICEBOARD,
        BENCH_CLUSTER
    }
}
