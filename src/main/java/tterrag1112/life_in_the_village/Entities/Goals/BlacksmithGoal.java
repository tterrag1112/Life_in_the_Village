package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FurnaceBlock;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.BlacksmithRecipeData;
import tterrag1112.life_in_the_village.Village.Economy.Resources.BlacksmithRecipeRegistry;

import java.util.*;

public class BlacksmithGoal extends Goal {

    private enum Phase {
        IDLE,
        GATHERING_ORES,
        WALKING_TO_FURNACE,
        SMELTING,
        WALKING_TO_ANVIL,
        CRAFTING,
        DEPOSITING
    }

    private static final int IDLE_COOLDOWN = 600;
    private static final int INTERACT_RANGE_SQ = 9;

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int workTimer = 0;
    private int workDuration = 0;

    private Building blacksmith = null;
    private BlockPos furnacePos = null;
    private BlockPos anvilPos = null;

    // What we're currently working on
    private BlacksmithRecipeData.SmeltingRecipe currentSmeltRecipe = null;
    private BlacksmithRecipeData.CraftingRecipe currentCraftRecipe = null;
    private int currentBatchSize = 0;

    public BlacksmithGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (entity.isWorkingBlocked()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        blacksmith = findBlacksmith(level);
        if (blacksmith == null) return false;

        furnacePos = findBlock(level, FurnaceBlock.class);
        anvilPos   = findAnvilPos(level);

        return true;
    }

    @Override
    public void start() { analyze(); }

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
            case GATHERING_ORES    -> gatherOres(level);
            case WALKING_TO_FURNACE -> walkTo(furnacePos,
                    Phase.SMELTING);
            case SMELTING          -> smelt(level);
            case WALKING_TO_ANVIL  -> walkTo(anvilPos,
                    Phase.CRAFTING);
            case CRAFTING          -> craft(level);
            case DEPOSITING        -> deposit(level);
            default -> {}
        }
    }

    private void analyze() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        BlacksmithRecipeData recipes =
                BlacksmithRecipeRegistry.INSTANCE.getData();

        // Check smelting first — always smelt ore before crafting
        for (BlacksmithRecipeData.SmeltingRecipe recipe
                : recipes.getSmeltingRecipes()) {
            int available = countInBuildingAndInventory(
                    level, recipe.input());
            if (available > 0) {
                currentSmeltRecipe = recipe;
                currentBatchSize = available;
                phase = Phase.GATHERING_ORES;
                return;
            }
        }

        // Find crafting recipe with lowest stock in building
        // so blacksmith produces what is most needed
        BlacksmithRecipeData.CraftingRecipe bestRecipe = null;
        int lowestStock = Integer.MAX_VALUE;

        for (BlacksmithRecipeData.CraftingRecipe recipe
                : recipes.getCraftingRecipes()) {
            int available = countInBuildingAndInventory(
                    level, recipe.input());
            if (available < recipe.inputCount()) continue;

            // Check current stock of output
            int currentStock = blacksmith != null
                    ? BuildingStorageAccess.countItem(
                    level, blacksmith, recipe.output())
                    : 0;

            // Skip if already have plenty in stock
            if (currentStock >= 4) continue;

            if (currentStock < lowestStock) {
                lowestStock = currentStock;
                bestRecipe = recipe;
            }
        }

        if (bestRecipe != null) {
            currentCraftRecipe = bestRecipe;
            currentBatchSize = countInBuildingAndInventory(
                    level, bestRecipe.input()) / bestRecipe.inputCount();
            phase = anvilPos != null
                    ? Phase.WALKING_TO_ANVIL : Phase.IDLE;
            return;
        }

        goIdle();
    }

    private void gatherOres(ServerLevel level) {
        entity.setCurrentActivity("Gathering ore");

        if (currentSmeltRecipe == null) { goIdle(); return; }

        // Take ores from stockpile or mine
        int taken = 0;
        int toTake = Math.min(currentBatchSize, 8); // take up to 8 at a time

        // Try stockpile first
        Building stockpile = findBuilding(level,
                Building.BuildingType.STOCKPILE);
        if (stockpile != null) {
            if (BuildingStorageAccess.takeItem(level, stockpile,
                    currentSmeltRecipe.input(), toTake)) {
                taken = toTake;
            }
        }

        // Try mine if stockpile didn't have enough
        if (taken < toTake) {
            Building mine = findBuilding(level, Building.BuildingType.MINE);
            if (mine != null) {
                int remaining = toTake - taken;
                if (BuildingStorageAccess.takeItem(level, mine,
                        currentSmeltRecipe.input(), remaining)) {
                    taken += remaining;
                }
            }
        }

        if (taken == 0) { goIdle(); return; }

        entity.getPersonalInventory().addItem(
                new ItemStack(currentSmeltRecipe.input(), taken));
        currentBatchSize = taken;
        phase = furnacePos != null
                ? Phase.WALKING_TO_FURNACE : Phase.IDLE;
    }

    private void walkTo(BlockPos target, Phase nextPhase) {
        if (target == null) { goIdle(); return; }

        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            workTimer = 0;
            workDuration = nextPhase == Phase.SMELTING && currentSmeltRecipe != null
                    ? currentSmeltRecipe.ticks() * currentBatchSize
                    : nextPhase == Phase.CRAFTING && currentCraftRecipe != null
                    ? currentCraftRecipe.ticks()
                    : 200;
            phase = nextPhase;
        }
    }

    private void smelt(ServerLevel level) {
        entity.setCurrentActivity("Smelting " + currentSmeltRecipe.output());

        workTimer++;

        // Face furnace
        if (furnacePos != null) {
            entity.getLookControl().setLookAt(
                    furnacePos.getX(), furnacePos.getY(), furnacePos.getZ());
        }

        // Play furnace sound periodically
        if (workTimer % 40 == 0) {
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.FURNACE_FIRE_CRACKLE,
                    SoundSource.NEUTRAL, 0.5f, 1.0f);
        }

        if (workTimer >= workDuration) {
            if (currentSmeltRecipe == null) { goIdle(); return; }

            // Produce smelted output
            int outputCount = currentBatchSize * currentSmeltRecipe.count();
            entity.getPersonalInventory().addItem(
                    new ItemStack(currentSmeltRecipe.output(), outputCount));

            // Remove ores from inventory
            removeFromInventory(currentSmeltRecipe.input(), currentBatchSize);



            workTimer = 0;
            currentSmeltRecipe = null;

            // Check if we can now craft something
            analyze();
        }
    }

    private void craft(ServerLevel level) {
        entity.setCurrentActivity("Crafting " + currentCraftRecipe.output());

        workTimer++;

        // Face anvil and swing hammer
        if (anvilPos != null) {
            entity.getLookControl().setLookAt(
                    anvilPos.getX(), anvilPos.getY(), anvilPos.getZ());
        }

        if (workTimer % 15 == 0) {
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.ANVIL_USE,
                    SoundSource.NEUTRAL, 0.5f,
                    0.8f + entity.getRandom().nextFloat() * 0.4f);
        }

        if (workTimer >= workDuration) {
            if (currentCraftRecipe == null) { goIdle(); return; }

            // Produce crafted output
            entity.getPersonalInventory().addItem(
                    new ItemStack(currentCraftRecipe.output(),
                            currentCraftRecipe.count()));

            // Consume inputs
            removeFromInventory(currentCraftRecipe.input(),
                    currentCraftRecipe.inputCount());



            workTimer = 0;

            // Check if we can craft another batch
            int remaining = countInInventory(currentCraftRecipe.input());
            if (remaining >= currentCraftRecipe.inputCount()) {
                workDuration = currentCraftRecipe.ticks();
                // Continue crafting
            } else {
                currentCraftRecipe = null;
                phase = Phase.DEPOSITING;
            }
        }
    }

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing goods");

        if (blacksmith == null) { goIdle(); return; }

        BlockPos target = blacksmith.getShape().getOrigin();
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
                    level, blacksmith, stack.copy());
            if (stored) inv.setItem(i, ItemStack.EMPTY);
        }

        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        goIdle();
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        goIdle();
    }

    private void goIdle() {
        entity.clearCurrentActivity();

        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        workTimer = 0;
        currentSmeltRecipe = null;
        currentCraftRecipe = null;
        entity.getNavigation().stop();
    }

    // --- Helpers ---

    private Building findBlacksmith(ServerLevel level) {
        return entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == Building.BuildingType.BLACKSMITH)
                .orElse(null);
    }

    private Building findBuilding(ServerLevel level,
                                  Building.BuildingType type) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(id -> VillageSavedData.get(level).getBuildingById(id))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == type)
                        .findFirst())
                .orElse(null);
    }

    private <T> BlockPos findBlock(ServerLevel level, Class<T> blockClass) {
        if (blacksmith == null) return null;
        BlockPos min = blacksmith.getShape().getMin();
        BlockPos max = blacksmith.getShape().getMax();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (blockClass.isInstance(level.getBlockState(pos).getBlock())) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findAnvilPos(ServerLevel level) {
        if (blacksmith == null) return null;
        BlockPos min = blacksmith.getShape().getMin();
        BlockPos max = blacksmith.getShape().getMax();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).is(BlockTags.ANVIL)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private int countInBuildingAndInventory(ServerLevel level, Item item) {
        return countInInventory(item)
                + (blacksmith != null
                ? BuildingStorageAccess.countItem(level, blacksmith, item)
                : 0);
    }

    private int countInInventory(Item item) {
        var inv = entity.getPersonalInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private void removeFromInventory(Item item, int count) {
        var inv = entity.getPersonalInventory();
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }
}