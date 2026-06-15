package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

import java.util.UUID;

/**
 * G1 — executor for a {@link FarmVerb#COMPOST} task.
 *
 * <p>Phase machine: WALK_TO_PLOT → APPLY</p>
 *
 * <p>Behavior-preserving port of {@code FarmerBehavior.compost()}: walk to plot
 * origin, pull 1 BONE_MEAL from farmhouse storage, call
 * {@code FarmPlot.onComposted()}, award FARMING +2 and ANIMAL_HUSBANDRY +1
 * (with specialty multiplier), setDirty. Identical logic, just driven through
 * the task executor contract.</p>
 */
public final class CompostExecutor implements TaskExecutor {

    private static final double INTERACT_RANGE_SQ = 4.0 * 4.0; // matches FarmerBehavior compost range
    private static final int    TICKS_PER_ACTION  = 20;

    private enum Phase { WALK_TO_PLOT, APPLY }

    private Phase    phase = Phase.WALK_TO_PLOT;
    private int      actionTimer;
    private FarmPlot targetPlot;
    private Building farmhouse;

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;

        if (!(task.objective() instanceof Objective.PerformService ps)) return Result.FAILED;
        UUID plotId = ps.ref().map(UUID::fromString).orElse(null);
        if (plotId == null) return Result.FAILED;

        if (targetPlot == null) {
            targetPlot = VillageSavedData.get(level).getFarmPlotById(plotId).orElse(null);
            if (targetPlot == null) return Result.FAILED;
        }
        if (farmhouse == null) {
            farmhouse = npc.getAssignedBuildingId()
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                    .orElse(null);
            if (farmhouse == null) return Result.FAILED;
        }

        return switch (phase) {
            case WALK_TO_PLOT -> tickWalkToPlot(level, npc);
            case APPLY        -> tickApply(level, npc);
        };
    }

    // ── Phase: WALK_TO_PLOT ───────────────────────────────────────────────────

    private Result tickWalkToPlot(ServerLevel level, TownspersonMob npc) {
        BlockPos target = targetPlot.getOrigin();
        double distSq = npc.distanceToSqr(target.getX(), target.getY(), target.getZ());
        if (distSq > INTERACT_RANGE_SQ) {
            NpcBehaviorHelpers.walkTo(npc, target, 1.0);
            return Result.RUNNING;
        }
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        phase = Phase.APPLY;
        return Result.RUNNING;
    }

    // ── Phase: APPLY ──────────────────────────────────────────────────────────

    private Result tickApply(ServerLevel level, TownspersonMob npc) {
        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return Result.RUNNING;
        actionTimer = 0;

        // Pull 1 BONE_MEAL from farmhouse (mirrors FarmerBehavior.compost)
        boolean taken = BuildingStorageAccess.takeItem(level, farmhouse, Items.BONE_MEAL, 1);
        if (!taken) return Result.FAILED; // stock vanished

        targetPlot.onComposted(level.getGameTime());
        VillageSavedData.get(level).setDirty();

        // XP mirrors FarmerBehavior.compost(): FARMING +2 direct, ANIMAL_HUSBANDRY +1
        // (composting bridges both domains per spec i.2.C).
        SkillXp.award(npc, Skill.FARMING, 2, level.getGameTime());
        float animalXp = 1f * FarmerSpecialtyMultiplier.of(npc, Skill.ANIMAL_HUSBANDRY);
        SkillXp.award(npc, Skill.ANIMAL_HUSBANDRY, animalXp, level.getGameTime());

        return Result.DONE;
    }
}
