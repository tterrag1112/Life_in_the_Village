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
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.UUID;

/**
 * G2b — fulfillment strategy for {@link FarmVerb#SHEAR} tasks.
 *
 * <p>Registered under {@link tterrag1112.life_in_the_village.Npc.Tasks.Objective.Type#PERFORM_SERVICE}
 * in {@link tterrag1112.life_in_the_village.Npc.Tasks.Fulfillments}.
 * The {@code kind=="shear"} guard is disjoint from every other
 * PERFORM_SERVICE fulfillment:</p>
 * <ul>
 *   <li>{@link FarmCropFulfillment} — gates on {@code isCropVerb} (harvest/replant/till/compost)</li>
 *   <li>{@link AnimalTendFulfillment} — gates on {@code kind=="animal_tend"}</li>
 *   <li>{@link HoneyFulfillment} — gates on {@code kind=="collect_honey"}</li>
 * </ul>
 *
 * <h3>canFulfill criteria</h3>
 * <ul>
 *   <li>Objective is {@link Objective.PerformService} with kind {@link FarmVerb#SHEAR}.</li>
 *   <li>Actor is {@link Profession#FARMER} with an assigned {@link BuildingType#FARMHOUSE}.</li>
 *   <li>FarmRole is {@link FarmRole#SHEPHERD}.</li>
 *   <li>Actor has shears in personal inventory.</li>
 *   <li>SHEEP roster exists for the farmhouse with at least one adult.</li>
 * </ul>
 */
public final class ShearFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return false;
        if (!FarmVerb.SHEAR.equals(ps.kind())) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.FARMER) return false;

        ServerLevel level = ctx.level();

        // Must have an assigned farmhouse.
        boolean hasFarmhouse = npc.getAssignedBuildingId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get(level).getBuildingById(id))
                .map(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(false);
        if (!hasFarmhouse) return false;

        // Role gate: must be SHEPHERD.
        FarmRole role = ProfessionRoleManager.getRole(npc, FarmRole.class);
        if (role != FarmRole.SHEPHERD) return false;

        // Tool gate: must have shears.
        if (!hasShears(npc)) return false;

        // Roster gate: SHEEP roster with at least one adult.
        UUID farmhouseId = ps.ref().map(UUID::fromString).orElse(null);
        if (farmhouseId == null) return false;
        return RosterSavedData.get(level)
                .getRoster(farmhouseId, AnimalRosterDefinitions.SHEEP)
                .map(r -> r.countAdults() > 0)
                .orElse(false);
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 8.0;
    }

    @Override
    public TaskExecutor executor() {
        return new ShearExecutor();
    }

    private static boolean hasShears(TownspersonMob entity) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Items.SHEARS) return true;
        }
        return false;
    }
}
