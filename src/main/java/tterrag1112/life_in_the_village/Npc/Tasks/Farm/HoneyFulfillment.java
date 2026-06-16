package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.UUID;

/**
 * G2b — fulfillment strategy for {@link FarmVerb#COLLECT_HONEY} tasks.
 *
 * <p>Registered under {@link tterrag1112.life_in_the_village.Npc.Tasks.Objective.Type#PERFORM_SERVICE}
 * in {@link tterrag1112.life_in_the_village.Npc.Tasks.Fulfillments}.
 * The {@code kind=="collect_honey"} guard is disjoint from all other
 * PERFORM_SERVICE fulfillments.</p>
 *
 * <h3>canFulfill criteria</h3>
 * <ul>
 *   <li>Objective is {@link Objective.PerformService} with kind {@link FarmVerb#COLLECT_HONEY}.</li>
 *   <li>Actor is {@link Profession#FARMER} with an assigned {@link BuildingType#FARMHOUSE}.</li>
 *   <li>FarmRole is {@link FarmRole#BEEKEEPER}.</li>
 *   <li>At least one of (shears + HONEYCOMB below quota) or (glass_bottle + HONEY_BOTTLE below quota)
 *       is satisfied — mirrors {@code BeekeeperBehavior.hasActionableWork}.</li>
 *   <li>BEE roster exists for the farmhouse with at least one adult.</li>
 * </ul>
 */
public final class HoneyFulfillment implements Fulfillment {

    private static final int HONEYCOMB_STOCK_QUOTA    = 16;
    private static final int HONEY_BOTTLE_STOCK_QUOTA = 8;

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return false;
        if (!FarmVerb.COLLECT_HONEY.equals(ps.kind())) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.FARMER) return false;

        ServerLevel level = ctx.level();

        // Must have an assigned farmhouse.
        UUID farmhouseId = ps.ref().map(UUID::fromString).orElse(null);
        if (farmhouseId == null) return false;

        Building farmhouse = npc.getAssignedBuildingId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);
        if (farmhouse == null) return false;

        // Role gate: must be BEEKEEPER.
        FarmRole role = ProfessionRoleManager.getRole(npc, FarmRole.class);
        if (role != FarmRole.BEEKEEPER) return false;

        // Tool + quota gate: mirrors BeekeeperBehavior.hasActionableWork.
        boolean canMakeComb = hasItem(npc, Items.SHEARS)
                && BuildingStorageAccess.countItem(level, farmhouse, Items.HONEYCOMB)
                        < HONEYCOMB_STOCK_QUOTA;
        boolean canMakeBottle = hasItem(npc, Items.GLASS_BOTTLE)
                && BuildingStorageAccess.countItem(level, farmhouse, Items.HONEY_BOTTLE)
                        < HONEY_BOTTLE_STOCK_QUOTA;
        if (!canMakeComb && !canMakeBottle) return false;

        // Roster gate: BEE roster with at least one adult.
        return RosterSavedData.get(level)
                .getRoster(farmhouseId, AnimalRosterDefinitions.BEE)
                .map(r -> r.countAdults() > 0)
                .orElse(false);
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 8.0;
    }

    @Override
    public TaskExecutor executor() {
        return new HoneyExecutor();
    }

    private static boolean hasItem(TownspersonMob entity, net.minecraft.world.item.Item item) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) return true;
        }
        return false;
    }
}
