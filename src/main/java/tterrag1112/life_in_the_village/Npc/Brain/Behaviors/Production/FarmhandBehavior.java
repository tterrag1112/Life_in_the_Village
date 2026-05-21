
package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

/**
 * Farmhand goal - performs specific farming tasks based on assigned role.
 *
 * Unlike head farmers, farmhands are specialized based on the number of
 * workers at the farm. They follow their assigned role and work on their
 * assigned plot only.
 */
public class FarmhandBehavior extends Behavior<TownspersonMob> {

    private static final int TICKS_PER_ACTION = 40;
    private static final int IDLE_COOLDOWN = 200;
    private static final double INTERACT_RANGE_SQ = 4.0;

    private TownspersonMob entity;

    public FarmhandBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }

    private Phase phase;
    private int actionTimer;
    private int idleCooldown;

    private FarmPlot assignedPlot;
    private Building farmhouse;

    private List<BlockPos> toHarvest;
    private List<BlockPos> toReplant;
    private Map<Item, Integer> harvestedThisCycle;

    private enum Phase {
        IDLE,
        ANALYZING,
        HARVESTING,
        DEPOSITING,
        REPLANTING
    }

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (idleCooldown > 0) {
            idleCooldown--;
            return false;
        }

        // Check role - only crop workers should use this goal
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);

        if (role == FarmRole.ANIMAL_SPECIALIST ||
                role == FarmRole.ANIMAL_TENDER ||
                role == FarmRole.MARKET_SELLER ||
                role == FarmRole.FERTILIZER) {
            return false; // These roles use different goals
        }

        // Must be assigned to a crop plot
        VillageSavedData data = VillageSavedData.get(level);
        assignedPlot = entity.getAssignedPlotId()
                .flatMap(data::getFarmPlotById)
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .orElse(null);

        return assignedPlot != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return phase != Phase.IDLE;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.ANALYZING;
        actionTimer = 0;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;

        switch (phase) {
            case IDLE -> { /* do nothing */ }
            case ANALYZING -> analyze(level);
            case HARVESTING -> harvest(level);
            case DEPOSITING -> deposit(level);
            case REPLANTING -> replant(level);
        }
    }

    // =========================================================================
    // Phase: ANALYZING
    // =========================================================================

    private void analyze(ServerLevel level) {
        entity.setCurrentActivity("Planning...");

        VillageSavedData data = VillageSavedData.get(level);

        // Find farmhouse for depositing
        farmhouse = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) {
            goIdle();
            return;
        }

        // Get role to determine what tasks we can do
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);

        // Clear task lists
        toHarvest.clear();
        toReplant.clear();

        // Scan assigned plot for tasks
        for (BlockPos farmland : assignedPlot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);

            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                if (canHarvest(role)) {
                    toHarvest.add(cropPos);
                }
            } else if (state.isAir() && level.getBlockState(farmland).getBlock() instanceof FarmBlock) {
                if (canPlant(role)) {
                    toReplant.add(cropPos);
                }
            }
        }

        // Decide next action based on role
        if (!toHarvest.isEmpty() && canHarvest(role)) {
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
            phase = Phase.HARVESTING;
            return;
        }

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.DEPOSITING;
            return;
        }

        if (!toReplant.isEmpty() && canPlant(role)) {
            phase = Phase.REPLANTING;
            return;
        }

        goIdle();
    }

    private boolean canHarvest(FarmRole role) {
        return role == FarmRole.GENERALIST ||
                role == FarmRole.CROP_SPECIALIST ||
                role == FarmRole.HARVESTER;
    }

    private boolean canPlant(FarmRole role) {
        return role == FarmRole.GENERALIST ||
                role == FarmRole.CROP_SPECIALIST ||
                role == FarmRole.PLANTER;
    }

    // =========================================================================
    // Phase: HARVESTING
    // =========================================================================

    private void harvest(ServerLevel level) {
        entity.setCurrentActivity("Harvesting crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toHarvest.isEmpty()) {
            phase = Phase.DEPOSITING;
            return;
        }

        BlockPos cropPos = toHarvest.get(0);
        double distSq = entity.distanceToSqr(
                cropPos.getX(), cropPos.getY(), cropPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    cropPos.getX(), cropPos.getY(), cropPos.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        BlockState state = level.getBlockState(cropPos);
        if (!(state.getBlock() instanceof CropBlock crop)) {
            toHarvest.remove(0);
            return;
        }

        if (!crop.isMaxAge(state)) {
            toHarvest.remove(0);
            return;
        }

        // Get drops with seasonal multiplier
        List<ItemStack> drops = Block.getDrops(state, level, cropPos, null);
        float seasonMult = SeasonTracker.getYieldMultiplier(level);

        for (ItemStack drop : drops) {
            int scaledCount = 0;
            for (int i = 0; i < drop.getCount(); i++) {
                if (entity.getRandom().nextFloat() < seasonMult) scaledCount++;
            }
            scaledCount = Math.max(1, scaledCount);

            ItemStack scaled = drop.copy();
            scaled.setCount(scaledCount);
            entity.getPersonalInventory().addItem(scaled);
            harvestedThisCycle.merge(scaled.getItem(), scaledCount, Integer::sum);
        }

        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
        entity.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cropPos,
                SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);

        toHarvest.remove(0);

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.DEPOSITING;
        }
    }

    // =========================================================================
    // Phase: DEPOSITING
    // =========================================================================

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing harvest");

        if (farmhouse == null) {
            goIdle();
            return;
        }

        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        SimpleContainer inv = entity.getPersonalInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            // Don't deposit tools
            if (stack.is(Items.IRON_HOE) || stack.is(Items.DIAMOND_HOE)) continue;

            BuildingStorageAccess.storeItem(level, farmhouse, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }

        // Continue with next task
        if (!toHarvest.isEmpty()) {
            phase = Phase.HARVESTING;
        } else if (!toReplant.isEmpty()) {
            phase = Phase.REPLANTING;
        } else {
            phase = Phase.ANALYZING;
        }
    }

    // =========================================================================
    // Phase: REPLANTING
    // =========================================================================

    private void replant(ServerLevel level) {
        entity.setCurrentActivity("Replanting crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toReplant.isEmpty()) {
            phase = Phase.ANALYZING;
            return;
        }

        BlockPos targetPos = toReplant.get(0);
        double distSq = entity.distanceToSqr(
                targetPos.getX(), targetPos.getY(), targetPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Verify farmland below
        if (!(level.getBlockState(targetPos.below()).getBlock() instanceof FarmBlock)) {
            toReplant.remove(0);
            return;
        }

        // Get crop block for this plot
        Block cropBlock = assignedPlot.getCropType().resolveCropBlock();
        if (cropBlock == null) {
            toReplant.remove(0);
            return; // PASTURE — no crops to plant
        }

        Item seedItem = assignedPlot.getCropType().resolveSeedItem();

        // Get seed from farmhouse storage
        boolean taken = BuildingStorageAccess.takeItem(level, farmhouse, seedItem, 1);

        if (taken) {
            level.setBlock(targetPos, cropBlock.defaultBlockState(), 3);
            entity.getLookControl().setLookAt(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, targetPos,
                    SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0f, 1.0f);
        }

        toReplant.remove(0);
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================



    private boolean isPersonalInventoryNearlyFull() {
        SimpleContainer inv = entity.getPersonalInventory();
        int emptySlots = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) emptySlots++;
        }
        return emptySlots < 3;
    }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        toHarvest.clear();
        toReplant.clear();
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        goIdle();
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}