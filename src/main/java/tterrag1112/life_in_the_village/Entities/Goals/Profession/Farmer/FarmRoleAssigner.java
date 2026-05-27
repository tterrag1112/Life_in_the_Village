package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;
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

        // Phase 6.7.1 — pin locked-specialization workers to their role
        // before count-driven distribution. Locked specs reflect explicit
        // operator / inhabitant-registry intent; quota math should treat
        // them as already-placed and distribute remaining roles among the
        // unlocked pool only.
        int pinnedShepherds = 0, pinnedCropSpecs = 0;
        List<TownspersonMob> unlocked = new ArrayList<>(workers.size());
        for (TownspersonMob w : workers) {
            FarmRole role = lockedSpecRole(w);
            if (role == null) { unlocked.add(w); continue; }
            ProfessionRoleManager.setRole(w, role);
            if (role == FarmRole.SHEPHERD || role == FarmRole.ANIMAL_SPECIALIST) {
                assignPlot(w, pens, pinnedShepherds++);
            } else if (role == FarmRole.CROP_SPECIALIST) {
                assignPlot(w, crops, pinnedCropSpecs++);
            }
            LOGGER.debug("[FarmRoleAssigner] {} (locked {}) pinned to {} role",
                    w.getNpcName(),
                    w.getSpecializationComponent().currentId()
                            .map(Identifier::toString).orElse("?"),
                    role.name());
        }
        workers = unlocked;
        total = workers.size();
        if (total == 0) {
            LOGGER.debug("[FarmRoleAssigner] All workers were locked-pinned for {}",
                    farmhouse.getName());
            return;
        }

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

    /**
     * Phase 6.7.1 — maps a locked specialization to a fixed FarmRole.
     * Returns {@code null} when the NPC has no specialization, the
     * specialization is unlocked, or the specialization doesn't have a
     * natural role mapping (FARMER_MIXED stays in the count-driven pool).
     *
     * <p>BEEKEEPER intentionally does NOT take a pen plot (bees attach
     * to hives, not pasture plots). Pasture pinning for SHEPHERD reuses
     * the existing pen-assignment helper.</p>
     */
    private static FarmRole lockedSpecRole(TownspersonMob npc) {
        var comp = npc.getSpecializationComponent();
        if (!comp.isLocked()) return null;
        Identifier id = comp.currentId().orElse(null);
        if (id == null) return null;
        if (id.equals(NpcSpecializationTypes.FARMER_SHEPHERD.name()))       return FarmRole.SHEPHERD;
        if (id.equals(NpcSpecializationTypes.FARMER_BEEKEEPER.name()))      return FarmRole.BEEKEEPER;
        if (id.equals(NpcSpecializationTypes.FARMER_CROP_FOCUS.name()))     return FarmRole.CROP_SPECIALIST;
        if (id.equals(NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name()))   return FarmRole.ANIMAL_SPECIALIST;
        return null;
    }
}