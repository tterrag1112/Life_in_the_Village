package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Doc 02 §"Integration points" — orchestrates the AdjunctPlot
 * realiser pass. Sits between {@code BuildingFoundation} (which
 * stamps each building's NBT) and {@code DecorationPass} (which
 * emits decoration slots that need to skip plot footprints).
 *
 * <p>Per-building flow:</p>
 * <ol>
 *   <li>Look up the registered {@link AdjunctPlotType} list via
 *       {@link AdjunctPlotRegistry#getPlotsForBuilding}.</li>
 *   <li>For each plot type in priority order, call
 *       {@link AdjunctPlotPlacer#tryPlace}.</li>
 *   <li>Persist successful placements via
 *       {@link VillageSavedData#addAdjunctPlot}; skip-and-log
 *       silent drops (the placer already logs INFO on failure).</li>
 * </ol>
 *
 * <p>Phase 0c materialises plot bounds only — no NBT stamping or
 * piece-kit assembly happens here. Subsystems 07 / 08 / 11 will
 * walk the persisted plot list and stamp content from their own
 * realisers in later phases.</p>
 */
public final class AdjunctPlotRealiser {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdjunctPlotRealiser.class);

    private AdjunctPlotRealiser() {}

    /**
     * Runs the realiser pass for {@code village}. Iterates the
     * village's buildings, attempts every registered plot type in
     * order, and persists successes onto {@code data}.
     *
     * <p>Idempotent for re-runs: clears the village's prior plots
     * before placing new ones. Real re-running isn't yet exposed
     * via a debug command (deferred to Phase 1 alongside content),
     * but the safeguard is in place.</p>
     */
    public static int run(ServerLevel level, Village village, VillageSavedData data) {
        if (level == null || village == null || data == null) return 0;

        long started = System.nanoTime();
        int placed = 0;
        int dropped = 0;
        int considered = 0;

        for (UUID buildingId : village.getBuildingIds()) {
            Optional<Building> bOpt = data.getBuildingById(buildingId);
            if (bOpt.isEmpty()) continue;
            Building building = bOpt.get();
            var spec = AdjunctPlotRegistry.getPlotsForBuilding(building.getType());
            if (spec.isEmpty()) continue;

            // Per-building: walk plot types in priority order. Each
            // tryPlace consults already-placed plots in this pass
            // (added to VillageSavedData immediately on success), so
            // secondary plots see and avoid the primary's footprint.
            for (AdjunctPlotType type : spec) {
                considered++;
                Optional<AdjunctPlot> plotOpt = AdjunctPlotPlacer
                        .tryPlace(building, type, level, data);
                if (plotOpt.isEmpty()) {
                    dropped++;
                    continue;
                }
                data.addAdjunctPlot(plotOpt.get());
                placed++;
            }
        }

        long nanos = System.nanoTime() - started;
        LOGGER.info("AdjunctPlotRealiser: village {} — considered {} "
                + "(plot-type, building) pairs, placed {}, dropped {} "
                + "({}ms)",
                village.getName(), considered, placed, dropped,
                nanos / 1_000_000);
        return placed;
    }
}
