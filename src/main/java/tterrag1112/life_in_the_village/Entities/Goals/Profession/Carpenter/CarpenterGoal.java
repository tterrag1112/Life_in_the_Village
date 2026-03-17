package tterrag1112.life_in_the_village.Entities.Goals.Profession.Carpenter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.CraftingTableBlock;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.*;

public class CarpenterGoal extends Goal {

    private enum Phase {
        IDLE,
        GATHERING,
        WALKING_TO_TABLE,
        CRAFTING,
        DEPOSITING
    }

    private static final int IDLE_COOLDOWN = 600;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final int CRAFT_TICKS = 60; // ticks per craft operation
    private static final int DEPOSIT_THRESHOLD = 16;

    // Wood types the carpenter can work with
    private static final List<Item> WOOD_INPUTS = List.of(
            Items.OAK_LOG,     Items.SPRUCE_LOG,   Items.BIRCH_LOG,
            Items.JUNGLE_LOG,  Items.ACACIA_LOG,   Items.DARK_OAK_LOG,
            Items.MANGROVE_LOG, Items.CHERRY_LOG
    );

    // Items the carpenter produces — derived from vanilla recipes
    private static final List<Item> TARGET_OUTPUTS = List.of(
            Items.OAK_PLANKS,     Items.SPRUCE_PLANKS,   Items.BIRCH_PLANKS,
            Items.OAK_SLAB,       Items.SPRUCE_SLAB,     Items.BIRCH_SLAB,
            Items.OAK_STAIRS,     Items.SPRUCE_STAIRS,   Items.BIRCH_STAIRS,
            Items.OAK_DOOR,       Items.SPRUCE_DOOR,     Items.BIRCH_DOOR,
            Items.OAK_FENCE,      Items.SPRUCE_FENCE,    Items.BIRCH_FENCE,
            Items.OAK_FENCE_GATE, Items.SPRUCE_FENCE_GATE,
            Items.CRAFTING_TABLE, Items.OAK_TRAPDOOR,    Items.SPRUCE_TRAPDOOR,
            Items.CHEST,          Items.BARREL,           Items.BOOKSHELF
    );

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int craftTimer = 0;
    private int craftCount = 0;

    private Building carpentry = null;
    private Building stockpile = null;
    private BlockPos tablePos = null;
    private CraftingRecipeEntry currentRecipe = null;

    // Represents a craftable recipe with its input requirements
    private record CraftingRecipeEntry(
            Item input, int inputCount,
            Item output, int outputCount
    ) {}

    public CarpenterGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (entity.isWorkingBlocked()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        VillageSavedData data = VillageSavedData.get(level);
        carpentry = findCarpentry(level, data);
        if (carpentry == null) return false;

        tablePos = findCraftingTable(level);
        if (tablePos == null) return false;

        stockpile = findStockpile(level, data);
        return stockpile != null;
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
            case GATHERING        -> gather(level);
            case WALKING_TO_TABLE -> walkToTable();
            case CRAFTING         -> craft(level);
            case DEPOSITING       -> deposit(level);
            default -> {}
        }
    }

    // --- Phase handlers ---

    private void analyze() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // Find what wood is available in stockpile
        currentRecipe = findBestRecipe(level);
        if (currentRecipe == null) {
            goIdle();
            return;
        }

        phase = Phase.GATHERING;
    }

    private void gather(ServerLevel level) {
        entity.setCurrentActivity("Gathering resources");
        if (currentRecipe == null) { goIdle(); return; }

        // Take wood from stockpile
        int toTake = Math.min(
                BuildingStorageAccess.countItem(
                        level, stockpile, currentRecipe.input()),
                16 // take up to 16 logs at a time
        );

        if (toTake < currentRecipe.inputCount()) {
            goIdle();
            return;
        }

        // Round down to multiples of inputCount
        toTake = (toTake / currentRecipe.inputCount())
                * currentRecipe.inputCount();

        boolean taken = BuildingStorageAccess.takeItem(
                level, stockpile, currentRecipe.input(), toTake);

        if (!taken) { goIdle(); return; }

        entity.getPersonalInventory().addItem(
                new ItemStack(currentRecipe.input(), toTake));
        craftCount = toTake / currentRecipe.inputCount();

        entity.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.IRON_AXE));
        phase = Phase.WALKING_TO_TABLE;
    }

    private void walkToTable() {
        if (tablePos == null) { goIdle(); return; }

        double distSq = entity.distanceToSqr(
                tablePos.getX(), tablePos.getY(), tablePos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    tablePos.getX(), tablePos.getY(),
                    tablePos.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            craftTimer = 0;
            phase = Phase.CRAFTING;
        }
    }

    private void craft(ServerLevel level) {
        entity.setCurrentActivity("Crafting " + currentRecipe.output());
        craftTimer++;

        // Face crafting table
        entity.getLookControl().setLookAt(
                tablePos.getX(), tablePos.getY(), tablePos.getZ());

        // Swing arm periodically
        if (craftTimer % 20 == 0) {
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, tablePos,
                    SoundEvents.AXE_STRIP,
                    SoundSource.NEUTRAL, 0.5f,
                    0.8f + entity.getRandom().nextFloat() * 0.4f);
        }

        if (craftTimer >= CRAFT_TICKS) {
            craftTimer = 0;

            if (currentRecipe == null || craftCount <= 0) {
                phase = Phase.DEPOSITING;
                return;
            }

            // Consume input and produce output
            removeFromInventory(currentRecipe.input(),
                    currentRecipe.inputCount());
            entity.getPersonalInventory().addItem(
                    new ItemStack(currentRecipe.output(),
                            currentRecipe.outputCount()));

            craftCount--;

            System.out.println("Carpenter crafted "
                    + currentRecipe.outputCount() + "x "
                    + currentRecipe.output().getDescriptionId());

            // Check if done or inventory full
            if (craftCount <= 0 || isInventoryFull()) {
                entity.setItemInHand(InteractionHand.MAIN_HAND,
                        ItemStack.EMPTY);
                phase = Phase.DEPOSITING;
            }
        }
    }

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing resources");
        if (carpentry == null) { goIdle(); return; }

        BlockPos target = carpentry.getShape().getOrigin();
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
                    level, carpentry, stack.copy());
            if (stored) inv.setItem(i, ItemStack.EMPTY);
        }

        goIdle();
    }

    // --- Helpers ---

    private CraftingRecipeEntry findBestRecipe(ServerLevel level) {
        // Hardcoded wood conversion recipes
        // These mirror vanilla recipes but simplified for NPC use
        List<CraftingRecipeEntry> recipes = buildRecipeList();

        for (CraftingRecipeEntry recipe : recipes) {
            int available = BuildingStorageAccess.countItem(
                    level, stockpile, recipe.input());
            if (available >= recipe.inputCount()) {
                return recipe;
            }
        }
        return null;
    }

    private List<CraftingRecipeEntry> buildRecipeList() {
        List<CraftingRecipeEntry> recipes = new ArrayList<>();

        // Log -> Planks (1 log = 4 planks)
        Map<Item, Item> logToPlanks = Map.of(
                Items.OAK_LOG,      Items.OAK_PLANKS,
                Items.SPRUCE_LOG,   Items.SPRUCE_PLANKS,
                Items.BIRCH_LOG,    Items.BIRCH_PLANKS,
                Items.JUNGLE_LOG,   Items.JUNGLE_PLANKS,
                Items.ACACIA_LOG,   Items.ACACIA_PLANKS,
                Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS,
                Items.MANGROVE_LOG, Items.MANGROVE_PLANKS,
                Items.CHERRY_LOG,   Items.CHERRY_PLANKS
        );

        logToPlanks.forEach((log, planks) ->
                recipes.add(new CraftingRecipeEntry(log, 1, planks, 4)));

        // Planks -> Slabs (3 planks = 6 slabs)
        Map<Item, Item> planksToSlabs = Map.of(
                Items.OAK_PLANKS,      Items.OAK_SLAB,
                Items.SPRUCE_PLANKS,   Items.SPRUCE_SLAB,
                Items.BIRCH_PLANKS,    Items.BIRCH_SLAB,
                Items.JUNGLE_PLANKS,   Items.JUNGLE_SLAB,
                Items.ACACIA_PLANKS,   Items.ACACIA_SLAB,
                Items.DARK_OAK_PLANKS, Items.DARK_OAK_SLAB
        );
        planksToSlabs.forEach((planks, slab) ->
                recipes.add(new CraftingRecipeEntry(planks, 3, slab, 6)));

        // Planks -> Stairs (6 planks = 4 stairs)
        Map<Item, Item> planksToStairs = Map.of(
                Items.OAK_PLANKS,    Items.OAK_STAIRS,
                Items.SPRUCE_PLANKS, Items.SPRUCE_STAIRS,
                Items.BIRCH_PLANKS,  Items.BIRCH_STAIRS
        );
        planksToStairs.forEach((planks, stairs) ->
                recipes.add(new CraftingRecipeEntry(planks, 6, stairs, 4)));

        // Planks -> Doors (6 planks = 3 doors)
        Map<Item, Item> planksToDoors = Map.of(
                Items.OAK_PLANKS,    Items.OAK_DOOR,
                Items.SPRUCE_PLANKS, Items.SPRUCE_DOOR,
                Items.BIRCH_PLANKS,  Items.BIRCH_DOOR
        );
        planksToDoors.forEach((planks, door) ->
                recipes.add(new CraftingRecipeEntry(planks, 6, door, 3)));

        // Planks -> Fence (6 planks = 3 fences)
        Map<Item, Item> planksToFence = Map.of(
                Items.OAK_PLANKS,    Items.OAK_FENCE,
                Items.SPRUCE_PLANKS, Items.SPRUCE_FENCE,
                Items.BIRCH_PLANKS,  Items.BIRCH_FENCE
        );
        planksToFence.forEach((planks, fence) ->
                recipes.add(new CraftingRecipeEntry(planks, 6, fence, 3)));

        // Special items
        recipes.add(new CraftingRecipeEntry(
                Items.OAK_PLANKS, 8, Items.CHEST, 1));
        recipes.add(new CraftingRecipeEntry(
                Items.OAK_PLANKS, 4, Items.CRAFTING_TABLE, 1));
        recipes.add(new CraftingRecipeEntry(
                Items.OAK_PLANKS, 6, Items.BOOKSHELF, 1));
        recipes.add(new CraftingRecipeEntry(
                Items.OAK_PLANKS, 6, Items.BARREL, 1));

        return recipes;
    }

    private BlockPos findCraftingTable(ServerLevel level) {
        if (carpentry == null) return null;
        BlockPos min = carpentry.getShape().getMin();
        BlockPos max = carpentry.getShape().getMax();

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).getBlock()
                    instanceof CraftingTableBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    private Building findCarpentry(ServerLevel level,
                                   VillageSavedData data) {
        return entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.CARPENTRY)
                .orElse(null);
    }

    private Building findStockpile(ServerLevel level,
                                   VillageSavedData data) {
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.STOCKPILE)
                        .findFirst())
                .orElse(null);
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

    private boolean isInventoryFull() {
        var inv = entity.getPersonalInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            total += inv.getItem(i).getCount();
        }
        return total >= DEPOSIT_THRESHOLD;
    }

    private void goIdle() {
        entity.clearCurrentActivity();
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        craftTimer = 0;
        craftCount = 0;
        currentRecipe = null;
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    @Override
    public void stop() { goIdle(); }
}