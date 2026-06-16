package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Client.FarmingVisualEffects;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.ToolUseSupport;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
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
import tterrag1112.life_in_the_village.World.SeasonTracker;
import tterrag1112.life_in_the_village.World.WeatherContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * G1 — executor for a {@link FarmVerb#HARVEST} task.
 *
 * <p>Phase machine: WALK_TO_PLOT → HARVEST_BLOCKS → WALK_TO_FARMHOUSE → DEPOSIT</p>
 *
 * <p>Behavior-preserving port of {@code FarmerBehavior.harvest()} +
 * {@code FarmerBehavior.walkToFarmhouse()} + {@code FarmerBehavior.deposit()}:
 * same yield formula (season × soil × weather × drought × frost × blight × hoe),
 * same XP routing (CROP_FARMING / ORCHARDING by plot type + specialty multiplier),
 * same tool use (hoe durability), same deposit (BuildingStorageAccess.storeItem
 * per slot, skipping hoes), same FARMING +2 XP on deposit completion.</p>
 */
public final class HarvestExecutor implements TaskExecutor {

    private static final double INTERACT_RANGE_SQ = 4.0;
    private static final int    TICKS_PER_ACTION  = 20;

    private enum Phase { WALK_TO_PLOT, HARVEST_BLOCKS, WALK_TO_FARMHOUSE, DEPOSIT }

    private Phase phase = Phase.WALK_TO_PLOT;
    private int   actionTimer;
    private FarmPlot targetPlot;
    private Building farmhouse;
    private final List<BlockPos> toHarvest = new ArrayList<>();

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;

        if (!(task.objective() instanceof Objective.PerformService ps)) return Result.FAILED;
        UUID plotId = ps.ref().map(UUID::fromString).orElse(null);
        if (plotId == null) return Result.FAILED;

        // Lazily resolve plot and farmhouse on first tick
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

        // G1b: pull a hoe from farmhouse storage if the NPC has none.
        // One-time call per executor instance; no-op when already holding a hoe.
        if (phase == Phase.WALK_TO_PLOT) {
            FarmHoe.ensureHoe(level, farmhouse, npc);
        }

        return switch (phase) {
            case WALK_TO_PLOT      -> tickWalkToPlot(level, npc);
            case HARVEST_BLOCKS    -> tickHarvest(level, npc);
            case WALK_TO_FARMHOUSE -> tickWalkToFarmhouse(level, npc);
            case DEPOSIT           -> tickDeposit(level, npc);
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
        // Arrived at plot area — scan harvest targets
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        scanHarvestTargets(level);
        if (toHarvest.isEmpty()) return Result.DONE; // nothing to harvest
        phase = Phase.HARVEST_BLOCKS;
        return Result.RUNNING;
    }

    private void scanHarvestTargets(ServerLevel level) {
        toHarvest.clear();
        for (BlockPos farmland : targetPlot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                toHarvest.add(cropPos);
            }
        }
    }

    // ── Phase: HARVEST_BLOCKS ─────────────────────────────────────────────────

    private Result tickHarvest(ServerLevel level, TownspersonMob npc) {
        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return Result.RUNNING;
        actionTimer = 0;

        if (toHarvest.isEmpty()) {
            // Done harvesting — walk to farmhouse
            ItemStack carried = firstHarvestStack(npc);
            if (!carried.isEmpty()) {
                npc.getBrain().setMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), carried.copy());
            }
            phase = Phase.WALK_TO_FARMHOUSE;
            return Result.RUNNING;
        }

        BlockPos cropPos = toHarvest.get(0);
        double distSq = npc.distanceToSqr(cropPos.getX(), cropPos.getY(), cropPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            NpcBehaviorHelpers.walkTo(npc, cropPos, 1.0);
            return Result.RUNNING;
        }

        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        BlockState state = level.getBlockState(cropPos);
        if (!(state.getBlock() instanceof CropBlock crop)) {
            toHarvest.remove(0);
            return Result.RUNNING;
        }
        if (!crop.isMaxAge(state)) {
            toHarvest.remove(0);
            return Result.RUNNING;
        }

        // === Port of FarmerBehavior.harvest() yield calculation ===
        FarmingVisualEffects.showHarvestEffect(level, cropPos, state);
        FarmingVisualEffects.playHarvestSound(level, cropPos);

        List<ItemStack> drops = Block.getDrops(state, level, cropPos, null);
        float seasonMult  = SeasonTracker.getYieldMultiplier(level);
        float soilMult    = targetPlot.getSoilQuality();
        float weatherMult = WeatherContext.yieldMultiplier(level);
        UUID villageId    = resolveVillageId(level, npc);
        float droughtMult = WeatherContext.droughtYieldMultiplier(level, villageId);
        float frostMult   = WeatherContext.frostYieldMultiplier(level, cropPos,
                targetPlot.getCropType().coldTolerance());
        float blightMult  = targetPlot.isBlighted() ? FarmPlot.BLIGHT_YIELD_MULT : 1.0f;
        float hoeMult     = ToolUseSupport.bestToolMultiplier(
                npc, FarmHoe::isHoe, FarmHoe::hoeProductivityMultiplier,
                FarmHoe.HOE_PRODUCTIVITY_NO_HOE);
        float yieldMult   = seasonMult * soilMult * weatherMult
                * droughtMult * frostMult * blightMult * hoeMult;

        for (ItemStack drop : drops) {
            int scaledCount = 0;
            for (int i = 0; i < drop.getCount(); i++) {
                if (npc.getRandom().nextFloat() < yieldMult) scaledCount++;
            }
            scaledCount = Math.max(1, scaledCount);
            ItemStack scaled = drop.copy();
            scaled.setCount(scaledCount);
            npc.getPersonalInventory().addItem(scaled);
        }

        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
        ToolUseSupport.useToolFromInventory(npc, FarmHoe::isHoe, level, InteractionHand.MAIN_HAND);
        npc.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cropPos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);

        // XP: ORCHARDING for orchards, CROP_FARMING otherwise (mirrors awardCropXp)
        Skill xpTarget = (targetPlot.getCropType() == FarmPlot.CropType.ORCHARD)
                ? Skill.ORCHARDING : Skill.CROP_FARMING;
        float xpAmount = 1f * FarmerSpecialtyMultiplier.of(npc, xpTarget);
        SkillXp.award(npc, xpTarget, xpAmount, level.getGameTime());

        toHarvest.remove(0);

        // Mirror FarmerBehavior: walk to farmhouse if nearly full
        if (isPersonalInventoryNearlyFull(npc)) {
            ItemStack c = firstHarvestStack(npc);
            if (!c.isEmpty()) {
                npc.getBrain().setMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), c.copy());
            }
            phase = Phase.WALK_TO_FARMHOUSE;
        }
        return Result.RUNNING;
    }

    // ── Phase: WALK_TO_FARMHOUSE ──────────────────────────────────────────────

    private Result tickWalkToFarmhouse(ServerLevel level, TownspersonMob npc) {
        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = npc.distanceToSqr(target.getX(), target.getY(), target.getZ());
        if (distSq > INTERACT_RANGE_SQ) {
            NpcBehaviorHelpers.walkTo(npc, target, 1.0);
            return Result.RUNNING;
        }
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        phase = Phase.DEPOSIT;
        return Result.RUNNING;
    }

    // ── Phase: DEPOSIT ────────────────────────────────────────────────────────

    private Result tickDeposit(ServerLevel level, TownspersonMob npc) {
        SimpleContainer inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (FarmHoe.isHoe(stack)) continue;  // keep tools
            BuildingStorageAccess.storeItem(level, farmhouse, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }
        npc.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        // FARMING +2 XP on deposit completion (mirrors FarmerBehavior.deposit)
        SkillXp.award(npc, Skill.FARMING, 2, level.getGameTime());

        // If there are still blocks to harvest (interrupted by near-full), resume
        if (!toHarvest.isEmpty()) {
            phase = Phase.HARVEST_BLOCKS;
            return Result.RUNNING;
        }
        return Result.DONE;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isPersonalInventoryNearlyFull(TownspersonMob npc) {
        SimpleContainer inv = npc.getPersonalInventory();
        int emptySlots = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) emptySlots++;
        }
        return emptySlots < 3;
    }

    private static ItemStack firstHarvestStack(TownspersonMob npc) {
        SimpleContainer inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (FarmHoe.isHoe(s)) continue;
            return s;
        }
        return ItemStack.EMPTY;
    }

    private static UUID resolveVillageId(ServerLevel level, TownspersonMob npc) {
        VillageSavedData data = VillageSavedData.get(level);
        return npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(v -> v.getId())
                .orElse(null);
    }
}
