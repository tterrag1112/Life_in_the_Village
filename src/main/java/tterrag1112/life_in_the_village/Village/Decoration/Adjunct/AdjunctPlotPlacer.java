package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

/**
 * B2.1 — renderer for an already-reserved {@link AdjunctPlot}.
 *
 * <p>Doc 02 §"Placement algorithm" once described a probe-and-place
 * flow: the placer searched the parent's faces for a fit, computed
 * an AABB, and emitted the {@link AdjunctPlot} record. B2.1 moves
 * every probe (parent overlap, sibling overlap, road corridors,
 * front-face rule, slope tolerance) up into V2 Layer 4's
 * {@code PhasedPlanner} so the reservation happens during
 * planning, before NBTs are stamped. By the time this class runs,
 * the plot's rectangle is final and persisted on
 * {@code VillageSavedData}.</p>
 *
 * <p>Phase 0c materialises plot bounds only — no NBT stamping or
 * piece-kit assembly happens here. Subsystems 07 (industry), 08
 * (gardens), and 11 (homesteading) will dispatch on
 * {@link AdjunctPlotType} to per-type renderers in B2.3+ and call
 * this class (or replace its body) at that time.</p>
 *
 * <p>For B2.1 the {@link #render} method is a logging stub: it
 * prints a one-line confirmation per plot so the smoke test can
 * verify reservations made it through V2 → realiser without
 * surfacing as an in-world content change.</p>
 */
public final class AdjunctPlotPlacer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdjunctPlotPlacer.class);

    private AdjunctPlotPlacer() {}

    /**
     * Renders {@code plot} into the world.
     *
     * <p>B2.1 implementation: log the plot's identity, type, origin,
     * and dimensions. No blocks are placed. Subsystems 07/08/11
     * will replace the body with per-type content rendering when
     * they ship.</p>
     */
    public static void render(AdjunctPlot plot, ServerLevel level,
                              VillageSavedData data) {
        if (plot == null || level == null) return;
        LOGGER.info("[adjunct] B2.1 placeholder render for {} plot {} parent={} "
                + "origin=({}, {}, {}) size={}x{}",
                plot.type(), plot.plotId(), plot.parentBuildingId(),
                plot.origin().getX(), plot.origin().getY(), plot.origin().getZ(),
                plot.width(), plot.length());
    }
}
