package tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop.WorkshopRole;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Goal for any NPC that owns a market stall.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 * IDLE → WALKING_TO_STALL → STOCKING → MINDING_STALL → IDLE
 * </pre>
 *
 * <ul>
 *   <li>IDLE — waits until work time, checks stall assignment once per minute</li>
 *   <li>WALKING_TO_STALL — navigates to the stall chest position</li>
 *   <li>STOCKING — moves sellable items from personal inventory to stall chest</li>
 *   <li>MINDING_STALL — stands near stall for the remainder of the work window,
 *       facing passing players. Payment is handled by TradeHandler, not here.</li>
 * </ul>
 *
 * <h3>Registration</h3>
 * This goal is added dynamically when an NPC is assigned a stall, not via
 * ProfessionGoalFactory. Call {@link #assignStall} after the stall is claimed:
 * <pre>
 *   StallKeeperGoal goal = new StallKeeperGoal(npc, stall);
 *   npc.goalSelector.addGoal(ProfessionGoalFactory.P_WORK_PRIMARY, goal);
 * </pre>
 */
public class StallKeeperGoal extends Goal {

    private static final double REACH_SQ      = 4.0;   // 2 blocks
    private static final double MOVE_SPEED    = 0.6;
    private static final int    IDLE_CHECK    = 1200;  // 1 min

    private enum Phase { IDLE, WALKING_TO_STALL, STOCKING, MINDING_STALL }

    private final TownspersonMob entity;
    private MarketStall stall;
    private Phase phase = Phase.IDLE;
    private int idleTimer = 0;

    public StallKeeperGoal(TownspersonMob entity, MarketStall stall) {
        this.entity = entity;
        this.stall  = stall;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (stall == null || !stall.isActive()) return false;
        if (phase != Phase.IDLE) return true;

        WorkshopRole role = ProfessionRoleManager.getRole(entity, WorkshopRole.class);

        // MARKET_SELLER: active during work time (this is their entire job)
        // GENERALIST / null: active during social time (production happens during work time)
        boolean correctTime = (role == WorkshopRole.MARKET_SELLER)
                ? entity.isWorkTime()
                : entity.isSocialTime();

        if (!correctTime) return false;

        return ++idleTimer >= IDLE_CHECK;
    }

    @Override
    public boolean canContinueToUse() {
        return entity.isWorkTime() && stall != null && stall.isActive();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void start() {
        idleTimer = 0;
        if (stall == null || !stall.isActive()) { goIdle(); return; }
        phase = Phase.WALKING_TO_STALL;
        entity.setCurrentActivity("Going to stall...");
    }

    @Override
    public void stop() { goIdle(); }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // Re-validate stall each tick — could have been reclaimed
        if (stall == null || !stall.isActive()) { goIdle(); return; }

        switch (phase) {
            case WALKING_TO_STALL -> tickWalking();
            case STOCKING         -> tickStocking(level);
            case MINDING_STALL    -> tickMinding();
            default -> {}
        }
    }

    // =========================================================================
    // Phase ticks
    // =========================================================================

    private void tickWalking() {
        BlockPos target = stall.getChestPos();
        if (target.equals(BlockPos.ZERO)) { goIdle(); return; }

        if (entity.distanceToSqr(target.getX(), target.getY(), target.getZ()) <= REACH_SQ) {
            entity.getNavigation().stop();
            phase = Phase.STOCKING;
            entity.setCurrentActivity("Stocking stall...");
            return;
        }

        if (entity.getNavigation().isDone()) {
            entity.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    MOVE_SPEED);
        }
    }

    private void tickStocking(ServerLevel level) {
        BlockEntity be = level.getBlockEntity(stall.getChestPos());
        if (!(be instanceof Container chest)) { goIdle(); return; }

        // Move sellable items from personal inventory to stall chest
        var personalInv = entity.getPersonalInventory();
        boolean movedAnything = false;

        for (int i = 0; i < personalInv.getContainerSize(); i++) {
            ItemStack stack = personalInv.getItem(i);
            if (stack.isEmpty()) continue;
            if (!isSellableItem(stack.getItem())) continue;

            // Try to fit in chest
            ItemStack remaining = tryAddToChest(chest, stack.copy());
            int moved = stack.getCount() - remaining.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) personalInv.setItem(i, ItemStack.EMPTY);
                movedAnything = true;
            }
        }

        if (movedAnything) {
            System.out.println("[StallKeeperGoal] " + entity.getNpcName()
                    + " stocked stall " + stall.getSlotIndex());
        }

        phase = Phase.MINDING_STALL;
        entity.setCurrentActivity("Minding stall...");
    }

    private void tickMinding() {
        // Stay near the stall — face the stall front
        BlockPos target = stall.getStallOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > REACH_SQ * 4) {
            entity.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    MOVE_SPEED);
        } else {
            entity.getNavigation().stop();
            // Face outward from the stall
            entity.getLookControl().setLookAt(
                    target.getX() + 0.5 + entity.level().getRandom().nextGaussian() * 2,
                    target.getY() + 1,
                    target.getZ() + 0.5 + entity.level().getRandom().nextGaussian() * 2);
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Updates the stall reference (e.g. if the NPC changes stalls). */
    public void assignStall(MarketStall stall) {
        this.stall = stall;
        phase = Phase.IDLE;
        idleTimer = 0;
    }

    public MarketStall getStall() { return stall; }

    public boolean isMindingStall() { return phase == Phase.MINDING_STALL; }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void goIdle() {
        phase = Phase.IDLE;
        idleTimer = 0;
        entity.getNavigation().stop();
        entity.clearCurrentActivity();
    }

    /**
     * Items the NPC considers "sellable" — anything that isn't a coin
     * and isn't a tool or weapon they personally need.
     * This is intentionally broad; the NPC's personal stash of coins
     * is protected because coins are checked separately in TradeHandler.
     */
    private boolean isSellableItem(Item item) {
        // Exclude coins — leave those in personal inventory for spending
        if (item == tterrag1112.life_in_the_village.Items.ModItems.DENIER.get()
                || item == tterrag1112.life_in_the_village.Items.ModItems.DENIER_ARGENT.get()
                || item == tterrag1112.life_in_the_village.Items.ModItems.DENIER_OR.get()) {
            return false;
        }
        return true;
    }

    private static ItemStack tryAddToChest(Container chest, ItemStack stack) {
        // Merge with existing stacks first
        for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack existing = chest.getItem(i);
            if (existing.is(stack.getItem())
                    && existing.getCount() < existing.getMaxStackSize()) {
                int space = existing.getMaxStackSize() - existing.getCount();
                int add = Math.min(space, stack.getCount());
                existing.grow(add);
                stack.shrink(add);
            }
        }
        // Then empty slots
        for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
            if (chest.getItem(i).isEmpty()) {
                chest.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
        return stack; // whatever couldn't fit
    }
}