package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * B2.1 — orchestrates the AdjunctPlot rendering pass.
 *
 * <p>Pre-B2.1 the realiser ran the probe-and-place flow: it walked
 * the {@link AdjunctPlotRegistry} per building, asked the placer to
 * search for a free face, and persisted successes. B2.1 moves
 * reservation up into V2 Layer 4: by the time this realiser runs,
 * every {@link AdjunctPlot} record has been planned, validated, and
 * persisted to {@link VillageSavedData} during the V2 spawn adapter's
 * post-{@code BuildingPlacer.placeAndRegister} loop. The realiser's
 * remaining job is to render content into each reserved
 * rectangle.</p>
 *
 * <p>Phase 0c content is intentionally absent — subsystems 07
 * (industry adjuncts), 08 (gardens), and 11 (homesteading) supply
 * their own renderers in B2.3 and beyond. Until then,
 * {@link AdjunctPlotPlacer#render} is a logging stub and this pass
 * is observable only via log lines.</p>
 */
public final class AdjunctPlotRealiser {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdjunctPlotRealiser.class);

    private AdjunctPlotRealiser() {}

    /**
     * Iterates every {@link AdjunctPlot} reserved for {@code village}
     * during V2 Layer 4 planning and dispatches the per-plot renderer.
     *
     * @return number of plots rendered (currently equals the count
     *         passed to the placeholder renderer; once content phases
     *         land, the count tracks plots whose render produced
     *         actual blocks).
     */
    public static int run(ServerLevel level, Village village, VillageSavedData data) {
        if (level == null || village == null || data == null) return 0;

        long started = System.nanoTime();
        int rendered = 0;
        for (UUID buildingId : village.getBuildingIds()) {
            for (AdjunctPlot plot : data.getAdjunctPlotsForBuilding(buildingId)) {
                AdjunctPlotPlacer.render(plot, level, data);
                rendered++;
            }
        }

        long nanos = System.nanoTime() - started;
        LOGGER.info("AdjunctPlotRealiser: village {} — rendered {} reserved "
                + "plots ({}ms)", village.getName(), rendered, nanos / 1_000_000);
        return rendered;
    }
}
