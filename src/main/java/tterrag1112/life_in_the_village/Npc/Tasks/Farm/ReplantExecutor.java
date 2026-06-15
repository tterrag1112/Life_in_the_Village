package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
import tterrag1112.life_in_the_village.World.WeatherContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * G1 — executor for a {@link FarmVerb#REPLANT} task.
 *
 * <p>Phase machine: WALK_TO_PLOT → REPLANT_BLOCKS → WALK_TO_FARMHOUSE → DEPOSIT</p>
 *
 * <p>Behavior-preserving port of {@code FarmerBehavior.replant()}: same till-then-plant
 * in-place logic, same rotation decision (non-apprentice, FARMING ≥ 40), same frost-aware
 * crop swap (POTATOES fallback), same seed from farmhouse storage, same XP routing
 * (CROP_FARMING / ORCHARDING + specialty multiplier), same soil/history update via
 * {@code FarmPlot.onPlanted()}, same drought double-decay, same VillageSavedData.setDirty().</p>
 */
public final class ReplantExecutor implements TaskExecutor {

    private static final double INTERACT_RANGE_SQ       = 4.0;
    private static final int    TICKS_PER_ACTION        = 20;
    private static final int    ROTATION_SKILL_THRESHOLD = 40;

    private enum Phase { WALK_TO_PLOT, REPLANT_BLOCKS, WALK_TO_FARMHOUSE, DEPOSIT }

    private Phase  phase = Phase.WALK_TO_PLOT;
    private int    actionTimer;
    private FarmPlot targetPlot;
    private Building farmhouse;
    private boolean isApprentice;
    private final List<BlockPos>  toReplant        = new ArrayList<>();
    private final Set<UUID>       rotatedThisCycle = new HashSet<>();

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

        // Apprentice check mirrored from FarmerBehavior.shouldRotateCrops
        isApprentice = isApprenticeTier(level, npc, farmhouse);

        return switch (phase) {
            case WALK_TO_PLOT      -> tickWalkToPlot(level, npc);
            case REPLANT_BLOCKS    -> tickReplant(level, npc);
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
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        scanReplantTargets(level);
        if (toReplant.isEmpty()) return Result.DONE;
        phase = Phase.REPLANT_BLOCKS;
        return Result.RUNNING;
    }

    private void scanReplantTargets(ServerLevel level) {
        toReplant.clear();
        for (BlockPos farmland : targetPlot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            if (level.getBlockState(cropPos).isAir()) {
                toReplant.add(cropPos);
            }
        }
        // Also include tillable surfaces (mirrors FarmerBehavior.scanPlotForTasks s.2)
        for (BlockPos dirt : targetPlot.getTillableSurfaces(level)) {
            BlockPos cropPos = dirt.above();
            if (level.getBlockState(cropPos).isAir()) {
                toReplant.add(cropPos);
            }
        }
    }

    // ── Phase: REPLANT_BLOCKS ─────────────────────────────────────────────────

    private Result tickReplant(ServerLevel level, TownspersonMob npc) {
        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return Result.RUNNING;
        actionTimer = 0;

        if (toReplant.isEmpty()) {
            phase = Phase.WALK_TO_FARMHOUSE;
            return Result.RUNNING;
        }

        BlockPos targetPos = toReplant.get(0);
        double distSq = npc.distanceToSqr(targetPos.getX(), targetPos.getY(), targetPos.getZ());
        if (distSq > INTERACT_RANGE_SQ) {
            NpcBehaviorHelpers.walkTo(npc, targetPos, 1.0);
            return Result.RUNNING;
        }
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // === Port of FarmerBehavior.replant() s.2: till-then-plant ===
        BlockState belowState = level.getBlockState(targetPos.below());
        if (!(belowState.getBlock() instanceof FarmBlock)) {
            boolean tillable = belowState.is(BlockTags.DIRT)
                    || belowState.getBlock() instanceof GrassBlock;
            if (!tillable) {
                toReplant.remove(0);
                return Result.RUNNING;
            }
            level.setBlock(targetPos.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            ToolUseSupport.useToolFromInventory(npc, FarmerBehavior::isHoe, level, InteractionHand.MAIN_HAND);
            npc.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, targetPos.below(), SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0f, 1.0f);
            // Continue below to plant immediately after tilling
        }

        // === Port of FarmerBehavior.replant() rotation logic (h.2) ===
        if (rotatedThisCycle.add(targetPlot.getId()) && shouldRotateCrops(npc)) {
            FarmPlot.CropType rotated = FarmPlot.CropFamily.suggestRotation(
                    targetPlot.getCropType(), targetPlot.getCropHistory(), npc.getRandom());
            if (rotated != targetPlot.getCropType()) {
                targetPlot.setCropType(rotated);
                VillageSavedData.get(level).setDirty();
            }
        }

        // === Port of FarmerBehavior.replant() frost-aware swap (k.3) ===
        if (shouldRotateCrops(npc)
                && targetPlot.getCropType().coldTolerance()
                        == FarmPlot.CropType.ColdTolerance.WARM_SEASON
                && WeatherContext.isFrost(level, targetPlot.getOrigin())) {
            targetPlot.setCropType(FarmPlot.CropType.POTATOES);
            VillageSavedData.get(level).setDirty();
        }

        Block cropBlock = targetPlot.getCropType().resolveCropBlock();
        net.minecraft.world.item.Item seedItem = targetPlot.getCropType().resolveSeedItem();
        if (cropBlock == null) {
            toReplant.remove(0);
            return Result.RUNNING;
        }

        // Take seed from farmhouse storage (mirrors FarmerBehavior.replant)
        boolean taken = BuildingStorageAccess.takeItem(level, farmhouse, seedItem, 1);
        if (!taken) {
            toReplant.remove(0);
            return Result.RUNNING; // no seed; skip position
        }

        level.setBlock(targetPos, cropBlock.defaultBlockState(), 3);
        npc.getLookControl().setLookAt(targetPos.getX(), targetPos.getY(), targetPos.getZ());
        npc.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, targetPos, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0f, 1.0f);

        // XP (mirrors awardCropXp with specialty multiplier)
        Skill xpTarget = (targetPlot.getCropType() == FarmPlot.CropType.ORCHARD)
                ? Skill.ORCHARDING : Skill.CROP_FARMING;
        float xpAmount = 1f * FarmerSpecialtyMultiplier.of(npc, xpTarget);
        SkillXp.award(npc, xpTarget, xpAmount, level.getGameTime());

        // Soil update + optional drought extra decay (mirrors FarmerBehavior.replant)
        targetPlot.onPlanted(targetPlot.getCropType(), level.getGameTime());
        UUID villageId = resolveVillageId(level, npc);
        if (WeatherContext.isDrought(level, villageId)) {
            targetPlot.setSoilQuality(targetPlot.getSoilQuality() - FarmPlot.SOIL_DEC_PLANT);
        }
        VillageSavedData.get(level).setDirty();

        toReplant.remove(0);
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
        net.minecraft.world.SimpleContainer inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (FarmerBehavior.isHoe(stack)) continue;
            BuildingStorageAccess.storeItem(level, farmhouse, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }
        SkillXp.award(npc, Skill.FARMING, 2, level.getGameTime());
        return Result.DONE;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Mirrors FarmerBehavior.shouldRotateCrops(). */
    private boolean shouldRotateCrops(TownspersonMob npc) {
        if (isApprentice) return false;
        return npc.getSkills().getLevel(Skill.FARMING) >= ROTATION_SKILL_THRESHOLD;
    }

    private static boolean isApprenticeTier(ServerLevel level,
                                            TownspersonMob npc, Building farmhouse) {
        var bdata = tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData.get(level);
        for (var business : bdata.getAllBusinesses()) {
            if (!business.getBuildingIds().contains(farmhouse.getId())) continue;
            return business.getWorkerTier(npc.getUUID())
                    .map(t -> t == tterrag1112.life_in_the_village.Guilds.Companies.EmploymentTier.APPRENTICE)
                    .orElse(false);
        }
        return false;
    }

    private static UUID resolveVillageId(ServerLevel level, TownspersonMob npc) {
        VillageSavedData data = VillageSavedData.get(level);
        return npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(v -> v.getId())
                .orElse(null);
    }
}
