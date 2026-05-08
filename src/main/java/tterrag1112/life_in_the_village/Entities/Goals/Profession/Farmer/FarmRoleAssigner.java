package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Assigns {@link FarmRole}s to workers based on total farmhand count.
 * Storage is handled by {@link ProfessionRoleManager}.
 */
public final class FarmRoleAssigner {

    private static final Logger LOGGER = LoggerFactory.getLogger(FarmRoleAssigner.class);

    /** B2.9 — per-farmhouse signature of the last applied assignment.
     *  Skip the work + log when nothing changed. */
    private static final ConcurrentHashMap<UUID, String> LAST_ASSIGNMENT =
            new ConcurrentHashMap<>();

    private FarmRoleAssigner() {}

    public static void assignRoles(ServerLevel level, Building farmhouse) {
        VillageSavedData data = VillageSavedData.get(level);
        List<TownspersonMob> workers =
                ProfessionRoleManager.findWorkersForBuilding(level, farmhouse);

        int total = workers.size();
        if (total == 0) {
            LAST_ASSIGNMENT.remove(farmhouse.getId());
            return;
        }
        // Idempotence — sorted worker UUIDs as signature.
        StringBuilder sig = new StringBuilder().append(total).append(':');
        workers.stream()
                .map(w -> w.getUUID().toString())
                .sorted()
                .forEach(s -> sig.append(s).append(';'));
        if (sig.toString().equals(LAST_ASSIGNMENT.put(farmhouse.getId(), sig.toString()))) {
            return;
        }

        List<FarmPlot> all      = data.getFarmPlotsForFarmhouse(farmhouse.getId());
        List<FarmPlot> crops    = all.stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .collect(Collectors.toList());
        List<FarmPlot> pens     = all.stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.ANIMAL_PEN)
                .collect(Collectors.toList());

        if (total <= 2) {
            workers.forEach(w -> ProfessionRoleManager.setRole(w, FarmRole.GENERALIST));

        } else if (total <= 4) {
            int cropCount = (int)(total * 0.6);
            for (int i = 0; i < workers.size(); i++) {
                if (i < cropCount) {
                    ProfessionRoleManager.setRole(workers.get(i), FarmRole.CROP_SPECIALIST);
                    assignPlot(workers.get(i), crops, i);
                } else {
                    ProfessionRoleManager.setRole(workers.get(i), FarmRole.ANIMAL_SPECIALIST);
                    assignPlot(workers.get(i), pens, i - cropCount);
                }
            }

        } else {
            int idx = 0;
            int harvesters = Math.max(1, (int)(total * 0.4));
            int planters   = Math.max(1, (int)(total * 0.2));
            int tenders    = Math.max(1, (int)(total * 0.2));
            int sellers    = Math.max(1, (int)(total * 0.1));

            for (int i = 0; i < harvesters && idx < total; i++, idx++) {
                ProfessionRoleManager.setRole(workers.get(idx), FarmRole.HARVESTER);
                assignPlot(workers.get(idx), crops, i);
            }
            for (int i = 0; i < planters && idx < total; i++, idx++) {
                ProfessionRoleManager.setRole(workers.get(idx), FarmRole.PLANTER);
                assignPlot(workers.get(idx), crops, i);
            }
            for (int i = 0; i < tenders && idx < total; i++, idx++) {
                ProfessionRoleManager.setRole(workers.get(idx), FarmRole.ANIMAL_TENDER);
                assignPlot(workers.get(idx), pens, i);
            }
            for (int i = 0; i < sellers && idx < total; i++, idx++) {
                ProfessionRoleManager.setRole(workers.get(idx), FarmRole.MARKET_SELLER);
            }
            while (idx < total) {
                ProfessionRoleManager.setRole(workers.get(idx), FarmRole.FERTILIZER);
                idx++;
            }
        }

        LOGGER.debug("[FarmRoleAssigner] Assigned roles to {} workers for {}",
                total, farmhouse.getName());
    }

    private static void assignPlot(TownspersonMob npc,
                                   List<FarmPlot> plots, int index) {
        if (plots.isEmpty()) return;
        npc.setAssignedPlotId(plots.get(index % plots.size()).getId());
    }
}