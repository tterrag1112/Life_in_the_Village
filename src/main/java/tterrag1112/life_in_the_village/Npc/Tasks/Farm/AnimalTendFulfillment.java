package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.UUID;

/**
 * G2 — fulfillment strategy for {@link FarmVerb#ANIMAL_TEND} tasks.
 *
 * <p>Registered under {@link tterrag1112.life_in_the_village.Npc.Tasks.Objective.Type#PERFORM_SERVICE}
 * in {@link tterrag1112.life_in_the_village.Npc.Tasks.Fulfillments}. The
 * {@code kind=="animal_tend"} guard is distinct from
 * {@link FarmCropFulfillment}'s {@code isCropVerb} guard, so the two
 * fulfillments never compete for the same task.</p>
 *
 * <h3>canFulfill criteria</h3>
 * <ul>
 *   <li>Objective is {@link Objective.PerformService} with kind
 *       {@link FarmVerb#ANIMAL_TEND}.</li>
 *   <li>Actor is {@link Profession#FARMER} with an assigned
 *       {@link BuildingType#FARMHOUSE}.</li>
 *   <li>Farmer's role is an animal role (same predicate as
 *       {@link AnimalTaskSource}: ANIMAL_SPECIALIST / ANIMAL_TENDER /
 *       FERTILIZER / GENERALIST+FARMER_ANIMAL_FOCUS).</li>
 *   <li>At least one animal roster exists for the farmhouse
 *       (mirrors AnimalTaskSource roster gate).</li>
 * </ul>
 */
public final class AnimalTendFulfillment implements Fulfillment {

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return false;
        if (!FarmVerb.ANIMAL_TEND.equals(ps.kind())) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.FARMER) return false;

        ServerLevel level = ctx.level();

        // Must have an assigned farmhouse
        boolean hasFarmhouse = npc.getAssignedBuildingId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get(level).getBuildingById(id))
                .map(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(false);
        if (!hasFarmhouse) return false;

        // Role gate (mirrors AnimalTaskSource / FarmerBehavior.analyze predicate)
        FarmRole role = ProfessionRoleManager.getRole(npc, FarmRole.class);
        boolean specBiasAnimal = (role == FarmRole.GENERALIST)
                && npc.getSpecializationComponent().currentId()
                        .map(id -> id.equals(NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name()))
                        .orElse(false);
        boolean animalRole = role == FarmRole.ANIMAL_SPECIALIST
                || role == FarmRole.ANIMAL_TENDER
                || role == FarmRole.FERTILIZER
                || specBiasAnimal;
        if (!animalRole) return false;

        // Roster gate: at least one animal roster must exist for this farmhouse
        UUID farmhouseId = ps.ref().map(UUID::fromString).orElse(null);
        if (farmhouseId == null) return false;
        return !RosterSavedData.get(level).getRostersForBuilding(farmhouseId).isEmpty();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // Constant score — intra-task ordering is handled by board ranking
        // (tier desc → urgency desc). NORMAL tier puts animal_tend above LOW-tier
        // crop tasks, which is appropriate for animal workers.
        return 8.0;
    }

    @Override
    public TaskExecutor executor() {
        return new AnimalTendExecutor();
    }
}
