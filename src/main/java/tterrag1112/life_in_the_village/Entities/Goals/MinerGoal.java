package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.MiningYieldData;
import tterrag1112.life_in_the_village.Village.Economy.Resources.MiningYieldRegistry;

import java.util.EnumSet;

public class MinerGoal extends Goal {

    private enum Phase {
        IDLE, WALKING_TO_MINE, MINING, DEPOSITING
    }

    private static final int IDLE_COOLDOWN = 400;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final int DEPOSIT_THRESHOLD = 16; // deposit when carrying this many items

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int miningTimer = 0;
    private int nextYieldTick = 0;

    private Building mine = null;
    private BlockPos minePos = null;

    public MinerGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (entity.isWorkingBlocked()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        mine = findMine(level);
        if (mine == null) return false;

        minePos = mine.getShape().getOrigin().offset(
                mine.getShape().getWidth() / 2, 1,
                mine.getShape().getLength() / 2
        );

        return true;
    }

    @Override
    public void start() {
        phase = Phase.WALKING_TO_MINE;
        miningTimer = 0;
        nextYieldTick = 100; // short initial delay before first yield
        entity.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.IRON_PICKAXE));
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case WALKING_TO_MINE -> walkToMine();
            case MINING          -> mine(level);
            case DEPOSITING      -> deposit(level);
            default -> {}
        }
    }

    private void walkToMine() {
        if (minePos == null) { goIdle(); return; }

        double distSq = entity.distanceToSqr(
                minePos.getX(), minePos.getY(), minePos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    minePos.getX(), minePos.getY(),
                    minePos.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            miningTimer = 0;

            // Schedule first yield
            MiningYieldData data = MiningYieldRegistry.INSTANCE.getDefault();
            if (data != null) {
                MiningYieldData.YieldEntry first = data.rollYield(entity.getRandom());
                nextYieldTick = miningTimer + first.tickInterval(mine.getLevel());
            }
            if (entity.needsPickaxe()) {
                System.out.println("Miner " + entity.getNpcName()
                        + " needs a pickaxe before mining");
                goIdle();
                return;
            }
            phase = Phase.MINING;
            }
    }
    private void usePickaxe(ServerLevel level) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(net.minecraft.tags.ItemTags.PICKAXES)) {
                entity.setItemInHand(InteractionHand.MAIN_HAND, stack);
                final int slot = i;
                stack.hurtAndBreak(1, level, null,
                        item -> inv.setItem(slot, ItemStack.EMPTY));
                return;
            }
        }
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private void mine(ServerLevel level) {
        miningTimer++;

        // Swing pickaxe animation
        if (miningTimer == 1) {
            System.out.println("Mining started - nextYieldTick=" + nextYieldTick);
        }
        if (miningTimer % 20 == 0) {
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.STONE_HIT, SoundSource.NEUTRAL,
                    0.5f, 0.8f + entity.getRandom().nextFloat() * 0.4f);
        }

        // Look at mine wall
        if (miningTimer % 40 == 0) {
            entity.getLookControl().setLookAt(
                    minePos.getX(), minePos.getY() - 1, minePos.getZ());
        }

        if (miningTimer % 100 == 0) {
            System.out.println("Still mining: " + miningTimer + "/" + nextYieldTick);
        }

        // Yield resource when timer hits
        if (miningTimer >= nextYieldTick) {
            System.out.println("YIELD TRIGGERED at tick " + miningTimer);

            MiningYieldData yieldData = MiningYieldRegistry.INSTANCE.getDefault();
            if (yieldData == null) return;

            MiningYieldData.YieldEntry entry = yieldData.rollYield(entity.getRandom());
            int amount = entry.roll(entity.getRandom(), mine.getLevel());

            entity.getPersonalInventory().addItem(new ItemStack(entry.item(), amount));

            System.out.println("Miner " + entity.getNpcName() + " yielded "
                    + amount + "x " + entry.item().getDescriptionId()
                    + " (" + entry.rarity() + ") next in " + nextYieldTick + " ticks");

            miningTimer = 0;
            nextYieldTick = entry.tickInterval(mine.getLevel());
            usePickaxe(level);

            // Schedule NEXT yield relative to current timer

            if (isInventoryAboveThreshold()) {
                phase = Phase.DEPOSITING;
            }
        }
    }

    private void deposit(ServerLevel level) {
        if (mine == null) { goIdle(); return; }

        BlockPos target = mine.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();
        var inv = entity.getPersonalInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            boolean stored = BuildingStorageAccess.storeItem(
                    level, mine, stack.copy());
            if (stored) inv.setItem(i, ItemStack.EMPTY);
        }

        // Reset timer and schedule next yield fresh
        miningTimer = 0;
        MiningYieldData data = MiningYieldRegistry.INSTANCE.getDefault();
        if (data != null) {
            MiningYieldData.YieldEntry next = data.rollYield(entity.getRandom());
            nextYieldTick = next.tickInterval(mine.getLevel());
        }

        phase = Phase.WALKING_TO_MINE;
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        goIdle();
    }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        miningTimer = 0;
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private Building findMine(ServerLevel level) {
        return entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == Building.BuildingType.MINE)
                .orElse(null);
    }

    private boolean isInventoryAboveThreshold() {
        var inv = entity.getPersonalInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            total += inv.getItem(i).getCount();
        }
        return total >= DEPOSIT_THRESHOLD; // DEPOSIT_THRESHOLD = 16 items total
    }
}
