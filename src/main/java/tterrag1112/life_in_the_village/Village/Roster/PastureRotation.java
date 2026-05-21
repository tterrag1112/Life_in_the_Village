package tterrag1112.life_in_the_village.Village.Roster;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.3.3.h.6 — pasture rotation helper. Picks the freshest
 * ANIMAL_PEN plot for a given farmhouse: the one with the highest
 * soilQuality (treated as grassQuality for pens) and oldest
 * lastGrazedTick. Skill-gated rotation is the caller's responsibility;
 * this helper just answers "which pen should the herd be on right now?".
 *
 * <p>Storm-retreat is handled at the call site (FarmerBehavior
 * consults {@code WeatherContext.isStorm}); when a storm is active,
 * callers should ignore the chosen pen and route to the farmhouse
 * anchor instead.
 */
public final class PastureRotation {

    private PastureRotation() {}

    /**
     * Picks the best pen for active grazing. Returns empty when the
     * farm has no ANIMAL_PEN plots (single-pen farms have nothing to
     * rotate — they fall through to the farmhouse anchor like before).
     *
     * <p>Scoring: prefer higher soilQuality (less depleted grass);
     * break ties by older lastGrazedTick (longer-rested pen).
     */
    public static Optional<FarmPlot> chooseActivePen(ServerLevel level, UUID farmhouseId) {
        if (level == null || farmhouseId == null) return Optional.empty();
        VillageSavedData data = VillageSavedData.get(level);
        FarmPlot best = null;
        for (FarmPlot plot : data.getFarmPlotsForFarmhouse(farmhouseId)) {
            if (plot.getSubtype() != FarmPlot.PlotSubtype.ANIMAL_PEN) continue;
            // Recover pens that haven't been grazed in a while.
            plot.tickPenRecovery(level.getGameTime());
            if (best == null
                    || plot.getSoilQuality() > best.getSoilQuality()
                    || (plot.getSoilQuality() == best.getSoilQuality()
                        && plot.getLastGrazedTick() < best.getLastGrazedTick())) {
                best = plot;
            }
        }
        return Optional.ofNullable(best);
    }
}
