package tterrag1112.life_in_the_village.Village.Roster;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Farms.Complex.FarmComplex;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6.7.2.2 / 6.7.3.3 — eager animal-roster seeding at FarmComplex
 * generation time. Before this phase nothing called
 * {@link RosterSavedData#putRoster}, so the simulation cycle was
 * dormant in practice. SHEPHERD content (6.7.2) and BEEKEEPER content
 * (6.7.3) both rely on rosters existing as soon as the supporting
 * plot subtype is planted.
 *
 * <p>Per-subtype species mapping:</p>
 * <ul>
 *   <li>{@link FarmPlot.PlotSubtype#ANIMAL_PEN} →
 *       {@link AnimalRosterDefinitions#SHEEP}</li>
 *   <li>{@link FarmPlot.PlotSubtype#APIARY} →
 *       {@link AnimalRosterDefinitions#BEE}</li>
 * </ul>
 *
 * <p>Seeding contract (uniform across species):</p>
 * <ul>
 *   <li>Population: {@code defaultPopulation} from the species
 *       definition (3 sheep, 1 bee hive).</li>
 *   <li>All slots start {@code Simulated} and {@code isAdult=true} —
 *       fresh-spawn rosters skip the growth cycle so the first
 *       production tick fires without waiting a day.</li>
 *   <li>{@code boundPlotId = plot.getId()} so
 *       {@link BuildingRoster#realizeNear} spawns animals at the
 *       plot origin rather than the farmhouse center.</li>
 * </ul>
 *
 * <p>Idempotent: if a roster for the same (buildingId, definitionId)
 * pair already exists in {@link RosterSavedData}, this helper skips
 * the rebuild (just refreshes the plot binding). Safe to call
 * multiple times on a complex.</p>
 */
public final class AnimalRosterSeeder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AnimalRosterSeeder() {}

    /**
     * Seeds rosters for every ANIMAL_PEN and APIARY plot in
     * {@code complex}. One roster per plot; multi-pen / multi-apiary
     * farms get one roster per plot (pasture rotation chooses the
     * active pen separately via {@link PastureRotation}; future
     * apiary rotation will mirror that).
     */
    public static void seedFor(ServerLevel level,
                               FarmComplex complex,
                               List<FarmPlot> newPlots) {
        if (level == null || complex == null || newPlots == null) return;
        RosterSavedData rdata = RosterSavedData.get(level);
        long now = level.getGameTime();
        UUID buildingId = complex.farmhouseId();

        for (FarmPlot plot : newPlots) {
            switch (plot.getSubtype()) {
                case ANIMAL_PEN ->
                        seedRoster(rdata, buildingId, plot.getId(),
                                AnimalRosterDefinitions.SHEEP, now);
                case APIARY     ->
                        seedRoster(rdata, buildingId, plot.getId(),
                                AnimalRosterDefinitions.BEE, now);
                default         -> { /* CROP_FIELD — no roster */ }
            }
        }
    }

    /**
     * Idempotent roster seed for a given (building, species) pair.
     * If the roster already exists, refreshes the plot binding (the
     * roster may have been seeded against a previous APIARY/PEN whose
     * plot got replaced by regeneration). Otherwise constructs a
     * fresh roster with {@code defaultPopulation} adult Simulated
     * slots and registers it.
     */
    private static void seedRoster(RosterSavedData rdata, UUID buildingId,
                                   UUID plotId, Identifier species, long now) {
        var existing = rdata.getRoster(buildingId, species);
        if (existing.isPresent()) {
            existing.get().bindToPlot(plotId);
            rdata.markDirty();
            return;
        }

        var def = RosterRegistry.get(species).orElse(null);
        if (def == null) {
            LOGGER.warn("[AnimalRosterSeeder] {} definition unregistered; "
                    + "cannot seed roster for {}", species, buildingId);
            return;
        }
        BuildingRoster roster = new BuildingRoster(buildingId, species);
        for (int i = 0; i < def.defaultPopulation(); i++) {
            roster.addSimulated(true, now);
        }
        roster.bindToPlot(plotId);
        rdata.putRoster(roster);
        LOGGER.info("[AnimalRosterSeeder] Seeded {} roster ({} adults) at "
                + "building {} bound to plot {}",
                species, def.defaultPopulation(), buildingId, plotId);
    }
}
