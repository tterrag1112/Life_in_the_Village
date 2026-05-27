package tterrag1112.life_in_the_village.Village.Roster;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Farms.Complex.FarmComplex;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6.7.2.2 — eager animal-roster seeding at FarmComplex
 * generation time. Before this phase nothing called
 * {@link RosterSavedData#putRoster}, so the simulation cycle was
 * dormant in practice. SHEPHERD content (6.7.2) needs sheep rosters
 * to exist as soon as a pasture is planted; BEEKEEPER (6.7.3) will
 * mirror this for APIARY plots once that subtype's realiser lands.
 *
 * <p>Seeding contract per ANIMAL_PEN plot:</p>
 * <ul>
 *   <li>Default species: {@link AnimalRosterDefinitions#SHEEP}
 *       (until pasture-type planning differentiates pen species).</li>
 *   <li>Population: {@code defaultPopulation} from the species
 *       definition (3 sheep).</li>
 *   <li>All slots start {@code Simulated} and {@code isAdult=true} —
 *       fresh-spawn rosters skip the growth cycle so the first
 *       production tick fires without waiting a day.</li>
 *   <li>{@code boundPlotId = pen.getId()} so
 *       {@link BuildingRoster#realizeNear} spawns sheep at the
 *       pasture origin rather than the farmhouse center.</li>
 * </ul>
 *
 * <p>Idempotent: if a roster for the same (buildingId, definitionId)
 * pair already exists in {@link RosterSavedData}, this helper skips
 * the rebuild. Safe to call multiple times on a complex (e.g.,
 * regenerated via the debug command).</p>
 */
public final class AnimalRosterSeeder {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AnimalRosterSeeder() {}

    /**
     * Seeds rosters for every ANIMAL_PEN plot in {@code complex}.
     * Each pasture binds to its own roster; multi-pasture farms get
     * one roster per pen (pasture rotation chooses the active pen
     * separately via {@link PastureRotation}).
     */
    public static void seedFor(ServerLevel level,
                               FarmComplex complex,
                               List<FarmPlot> newPlots) {
        if (level == null || complex == null || newPlots == null) return;
        RosterSavedData rdata = RosterSavedData.get(level);
        long now = level.getGameTime();

        for (FarmPlot plot : newPlots) {
            if (plot.getSubtype() != FarmPlot.PlotSubtype.ANIMAL_PEN) continue;
            seedSheepRoster(rdata, complex.farmhouseId(), plot.getId(), now);
        }
    }

    private static void seedSheepRoster(RosterSavedData rdata, UUID buildingId,
                                        UUID penPlotId, long now) {
        var existing = rdata.getRoster(buildingId, AnimalRosterDefinitions.SHEEP);
        if (existing.isPresent()) {
            existing.get().bindToPlot(penPlotId);
            rdata.markDirty();
            return;
        }

        var def = RosterRegistry.get(AnimalRosterDefinitions.SHEEP).orElse(null);
        if (def == null) {
            LOGGER.warn("[AnimalRosterSeeder] SHEEP definition unregistered; "
                    + "cannot seed roster for {}", buildingId);
            return;
        }
        BuildingRoster roster = new BuildingRoster(buildingId,
                AnimalRosterDefinitions.SHEEP);
        for (int i = 0; i < def.defaultPopulation(); i++) {
            roster.addSimulated(true, now);
        }
        roster.bindToPlot(penPlotId);
        rdata.putRoster(roster);
        LOGGER.info("[AnimalRosterSeeder] Seeded SHEEP roster ({} adults) at "
                + "building {} bound to pen {}",
                def.defaultPopulation(), buildingId, penPlotId);
    }
}
