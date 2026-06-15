package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.FarmerBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.ToolUseSupport;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * G1 — executor for a {@link FarmVerb#TILL} task.
 *
 * <p>Phase machine: WALK_TO_PLOT → TILL_BLOCKS</p>
 *
 * <p>Behavior-preserving port of the tilling sub-step in
 * {@code FarmerBehavior.replant()} (the s.2 till-in-place branch):
 * same dirt/grass detection, same FARMLAND conversion, same hoe durability damage
 * via {@code ToolUseSupport}, same sounds. A standalone till task prepares the
 * soil when seeds are unavailable; the replant executor handles the combined
 * till-then-plant flow when seeds ARE present.</p>
 */
public final class TillExecutor implements TaskExecutor {

    private static final double INTERACT_RANGE_SQ = 4.0;
    private static final int    TICKS_PER_ACTION  = 20;

    private enum Phase { WALK_TO_PLOT, TILL_BLOCKS }

    private Phase    phase = Phase.WALK_TO_PLOT;
    private int      actionTimer;
    private FarmPlot targetPlot;
    private final List<BlockPos> toTill = new ArrayList<>();

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

        return switch (phase) {
            case WALK_TO_PLOT  -> tickWalkToPlot(level, npc);
            case TILL_BLOCKS   -> tickTill(level, npc);
        };
    }

    // ── Phase: WALK_TO_PLOT ───────────────────────────────────────────────────

    private Result tickWalkToPlot(ServerLevel level, TownspersonMob npc) {
        BlockPos origin = targetPlot.getOrigin();
        double distSq = npc.distanceToSqr(origin.getX(), origin.getY(), origin.getZ());
        if (distSq > INTERACT_RANGE_SQ * 4.0) {
            NpcBehaviorHelpers.walkTo(npc, origin, 1.0);
            return Result.RUNNING;
        }
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        // Scan tillable surfaces
        toTill.clear();
        for (BlockPos pos : targetPlot.getTillableSurfaces(level)) {
            // Confirm still tillable (world state may have changed)
            BlockState st = level.getBlockState(pos);
            boolean tillable = st.is(BlockTags.DIRT)
                    || st.getBlock() instanceof GrassBlock;
            boolean notAlreadyFarm = !(st.getBlock() instanceof FarmBlock);
            if (tillable && notAlreadyFarm && level.getBlockState(pos.above()).isAir()) {
                toTill.add(pos);
            }
        }
        if (toTill.isEmpty()) return Result.DONE;
        phase = Phase.TILL_BLOCKS;
        return Result.RUNNING;
    }

    // ── Phase: TILL_BLOCKS ────────────────────────────────────────────────────

    private Result tickTill(ServerLevel level, TownspersonMob npc) {
        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return Result.RUNNING;
        actionTimer = 0;

        if (toTill.isEmpty()) return Result.DONE;

        BlockPos pos = toTill.get(0);
        double distSq = npc.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
        if (distSq > INTERACT_RANGE_SQ) {
            NpcBehaviorHelpers.walkTo(npc, pos, 1.0);
            return Result.RUNNING;
        }
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Re-check: world may have changed since scan
        BlockState st = level.getBlockState(pos);
        boolean tillable = st.is(BlockTags.DIRT) || st.getBlock() instanceof GrassBlock;
        boolean notFarm  = !(st.getBlock() instanceof FarmBlock);
        if (!tillable || !notFarm) {
            toTill.remove(0);
            return Result.RUNNING;
        }

        // === Till the block (mirrors FarmerBehavior.replant s.2) ===
        level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), 3);
        ToolUseSupport.useToolFromInventory(npc, FarmerBehavior::isHoe, level, InteractionHand.MAIN_HAND);
        npc.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, pos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0f, 1.0f);

        toTill.remove(0);
        return Result.RUNNING;
    }
}
