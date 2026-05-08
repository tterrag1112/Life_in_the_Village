package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns {@link WorkshopRole}s to workers based on total worker count.
 * Storage is handled by {@link ProfessionRoleManager}.
 *
 * <p>Covers all single-workstation production buildings: blacksmith,
 * carpenter, miller, baker, weaver, mason, etc.</p>
 *
 * <p>B2.9 — added idempotence keyed by building id. The daily
 * tick-role-check would re-emit "Assigned roles to N workers" log
 * lines on every invocation even when the worker set hadn't
 * changed. Now we cache the last-assigned signature (sorted worker
 * UUIDs) per building and short-circuit on match. The log line is
 * also demoted to SLF4J debug; it's diagnostic, not user-facing.</p>
 */
public final class WorkshopRoleAssigner {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkshopRoleAssigner.class);

    /** Per-building signature of the last applied role assignment.
     *  Memory cost is negligible (one short string per workshop)
     *  and lifetime survives village reloads (it's just a guard;
     *  worst case is one redundant assignment after restart). */
    private static final ConcurrentHashMap<UUID, String> LAST_ASSIGNMENT =
            new ConcurrentHashMap<>();

    private WorkshopRoleAssigner() {}

    public static void assignRoles(ServerLevel level, Building building) {
        List<TownspersonMob> workers =
                ProfessionRoleManager.findWorkersForBuilding(level, building);

        int total = workers.size();
        if (total == 0) {
            // Worker count went to zero — clear the signature so a
            // future re-population still triggers reassignment.
            LAST_ASSIGNMENT.remove(building.getId());
            return;
        }

        // B2.9 — idempotence: signature = total + sorted worker UUIDs.
        // Sort to make order-independent. If unchanged, no-op.
        StringBuilder sig = new StringBuilder().append(total).append(':');
        workers.stream()
                .map(w -> w.getUUID().toString())
                .sorted()
                .forEach(s -> sig.append(s).append(';'));
        String signature = sig.toString();
        String prior = LAST_ASSIGNMENT.put(building.getId(), signature);
        if (signature.equals(prior)) return;

        if (total == 1) {
            ProfessionRoleManager.setRole(workers.get(0), WorkshopRole.GENERALIST);

        } else if (total <= 3) {
            // First worker is the master PRODUCER, rest are APPRENTICE
            ProfessionRoleManager.setRole(workers.get(0), WorkshopRole.PRODUCER);
            for (int i = 1; i < workers.size(); i++) {
                ProfessionRoleManager.setRole(workers.get(i), WorkshopRole.APPRENTICE);
            }

        } else {
            // 4+ workers: dedicated MARKET_SELLER, one APPRENTICE, rest PRODUCERS

            // Last worker becomes market seller
            ProfessionRoleManager.setRole(
                    workers.get(workers.size() - 1), WorkshopRole.MARKET_SELLER);

            // Second-to-last is apprentice
            ProfessionRoleManager.setRole(
                    workers.get(workers.size() - 2), WorkshopRole.APPRENTICE);

            // Everyone else is a producer
            for (int i = 0; i < workers.size() - 2; i++) {
                ProfessionRoleManager.setRole(workers.get(i), WorkshopRole.PRODUCER);
            }
        }

        LOGGER.debug("[WorkshopRoleAssigner] Assigned roles to {} workers for {}",
                total, building.getName());
    }
}
