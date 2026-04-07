package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.List;

/**
 * Assigns {@link WorkshopRole}s to workers based on total worker count.
 * Storage is handled by {@link ProfessionRoleManager}.
 *
 * <p>Covers all single-workstation production buildings: blacksmith,
 * carpenter, miller, baker, weaver, mason, etc.</p>
 */
public final class WorkshopRoleAssigner {

    private WorkshopRoleAssigner() {}

    public static void assignRoles(ServerLevel level, Building building) {
        List<TownspersonMob> workers =
                ProfessionRoleManager.findWorkersForBuilding(level, building);

        int total = workers.size();
        if (total == 0) return;

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
            int idx = 0;

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

        System.out.println("[WorkshopRoleAssigner] Assigned roles to " + total
                + " workers for " + building.getName());
    }
}