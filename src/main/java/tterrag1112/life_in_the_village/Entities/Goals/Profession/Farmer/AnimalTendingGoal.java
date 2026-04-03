// New file: src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Farmer/AnimalTendingGoal.java

package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.*;

public class AnimalTendingGoal extends Goal {

    private static final int TICKS_PER_ACTION = 40;
    private static final int IDLE_COOLDOWN = 200;
    private static final double INTERACT_RANGE_SQ = 4.0;
    private static final int MAX_ANIMALS_PER_PEN = 12;
    private static final int MIN_BREEDING_STOCK = 2;

    private final TownspersonMob entity;

    private Phase phase;
    private int actionTimer;
    private int idleCooldown;

    private FarmPlot assignedPen;
    private Building farmhouse;
    private List<Animal> animalsToFeed;
    private List<Animal> animalsToBreed;
    private List<Sheep> sheepToShear;
    private List<Chicken> chickensForEggs;
    private List<Cow> cowsToMilk;
    private List<Animal> animalsToButcher;
    private Animal currentTarget;

    private enum Phase {
        IDLE,
        CHECKING,
        FEEDING,
        BREEDING,
        SHEARING,
        COLLECTING_EGGS,
        MILKING,
        BUTCHERING,
        DEPOSITING
    }

    public AnimalTendingGoal(TownspersonMob entity) {
        this.entity = entity;
        this.phase = Phase.IDLE;
        this.animalsToFeed = new ArrayList<>();
        this.animalsToBreed = new ArrayList<>();
        this.sheepToShear = new ArrayList<>();
        this.chickensForEggs = new ArrayList<>();
        this.cowsToMilk = new ArrayList<>();
        this.animalsToButcher = new ArrayList<>();
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel)) return false;
        if (idleCooldown > 0) {
            idleCooldown--;
            return false;
        }

        // Must be assigned to an animal pen
        VillageSavedData data = VillageSavedData.get((ServerLevel) entity.level());
        assignedPen = entity.getAssignedPlotId()
                .flatMap(data::getFarmPlotById)
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.ANIMAL_PEN)
                .orElse(null);

        return assignedPen != null;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE;
    }

    @Override
    public void start() {
        phase = Phase.CHECKING;
        actionTimer = 0;
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case IDLE -> { /* do nothing */ }
            case CHECKING -> analyze(level);
            case FEEDING -> feedAnimals(level);
            case BREEDING -> breedAnimals(level);
            case SHEARING -> shearSheep(level);
            case COLLECTING_EGGS -> collectEggs(level);
            case MILKING -> milkCows(level);
            case BUTCHERING -> butcherAnimals(level);
            case DEPOSITING -> deposit(level);
        }
    }

    // =========================================================================
    // Phase: CHECKING
    // =========================================================================

    private void analyze(ServerLevel level) {
        entity.setCurrentActivity("Checking animals...");
        VillageSavedData data = VillageSavedData.get(level);

        // Find farmhouse for storage access
        farmhouse = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) {
            goIdle();
            return;
        }

        // Scan pen for animals
        BlockPos penCenter = assignedPen.getOrigin();
        int radius = assignedPen.getRadius();

        // FIX: Create AABB properly
        AABB penBounds = new AABB(
                penCenter.getX() - radius, penCenter.getY() - 5, penCenter.getZ() - radius,
                penCenter.getX() + radius, penCenter.getY() + 5, penCenter.getZ() + radius
        );

        List<Animal> allAnimals = level.getEntitiesOfClass(Animal.class, penBounds);

        // Clear previous lists
        animalsToFeed.clear();
        animalsToBreed.clear();
        sheepToShear.clear();
        chickensForEggs.clear();
        cowsToMilk.clear();
        animalsToButcher.clear();

        // Categorize animals by need
        int totalCount = allAnimals.size();

        for (Animal animal : allAnimals) {
            // Animals that need food
            if (!animal.isBaby() && animal.getAge() == 0 && canBreed(animal)) {
                animalsToFeed.add(animal);
            }

            // Sheep that need shearing
            if (animal instanceof Sheep sheep && !sheep.isSheared() && !sheep.isBaby()) {
                sheepToShear.add(sheep);
            }

            // Chickens (for egg collection - passive, no action needed)
            if (animal instanceof Chicken chicken && !chicken.isBaby()) {
                chickensForEggs.add(chicken);
            }

            // Cows to milk
            if (animal instanceof Cow cow && !cow.isBaby()) {
                cowsToMilk.add(cow);
            }
        }

        // Breeding logic: if we have 2+ animals and space, breed them
        if (totalCount >= MIN_BREEDING_STOCK && totalCount < MAX_ANIMALS_PER_PEN) {
            animalsToBreed.addAll(animalsToFeed.subList(
                    0, Math.min(2, animalsToFeed.size())));
        }

        // Butchering logic: if overcrowded, mark oldest for butchering
        if (totalCount > MAX_ANIMALS_PER_PEN) {
            int excess = totalCount - MAX_ANIMALS_PER_PEN;
            allAnimals.stream()
                    .filter(a -> !a.isBaby())
                    .limit(excess)
                    .forEach(animalsToButcher::add);
        }

        // Decide next action
        if (!sheepToShear.isEmpty()) {
            phase = Phase.SHEARING;
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
        } else if (!cowsToMilk.isEmpty() && entity.getRandom().nextFloat() < 0.3f) {
            phase = Phase.MILKING;
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        } else if (!animalsToBreed.isEmpty() && hasFeedInStorage(level)) {
            phase = Phase.BREEDING;
        } else if (!animalsToButcher.isEmpty()) {
            phase = Phase.BUTCHERING;
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
        } else if (!chickensForEggs.isEmpty() && entity.getRandom().nextFloat() < 0.2f) {
            // Occasionally check for eggs on ground
            phase = Phase.COLLECTING_EGGS;
        } else {
            goIdle();
        }
    }

    // =========================================================================
    // Phase: SHEARING
    // =========================================================================

    private void shearSheep(ServerLevel level) {
        entity.setCurrentActivity("Shearing sheep");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (sheepToShear.isEmpty()) {
            phase = Phase.DEPOSITING;
            return;
        }

        Sheep sheep = sheepToShear.get(0);
        if (!sheep.isAlive()) {
            sheepToShear.remove(0);
            return;
        }

        double distSq = entity.distanceToSqr(sheep);
        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(sheep, 1.0);
            return;
        }

        entity.getNavigation().stop();

        // FIX: Shear the sheep with proper API
        if (!sheep.isSheared()) {
            ItemStack shears = entity.getMainHandItem();
            sheep.shear(level, SoundSource.NEUTRAL, shears);

            // Manually add wool drops to inventory
            int woolCount = 1 + entity.getRandom().nextInt(3); // 1-3 wool
            net.minecraft.world.item.DyeColor color = sheep.getColor();
            Item woolItem = net.minecraft.world.level.block.Block.byItem(
                    net.minecraft.world.item.Items.WHITE_WOOL).asItem();

            // Get the correct colored wool
            woolItem = switch (color) {
                case WHITE -> Items.WHITE_WOOL;
                case ORANGE -> Items.ORANGE_WOOL;
                case MAGENTA -> Items.MAGENTA_WOOL;
                case LIGHT_BLUE -> Items.LIGHT_BLUE_WOOL;
                case YELLOW -> Items.YELLOW_WOOL;
                case LIME -> Items.LIME_WOOL;
                case PINK -> Items.PINK_WOOL;
                case GRAY -> Items.GRAY_WOOL;
                case LIGHT_GRAY -> Items.LIGHT_GRAY_WOOL;
                case CYAN -> Items.CYAN_WOOL;
                case PURPLE -> Items.PURPLE_WOOL;
                case BLUE -> Items.BLUE_WOOL;
                case BROWN -> Items.BROWN_WOOL;
                case GREEN -> Items.GREEN_WOOL;
                case RED -> Items.RED_WOOL;
                case BLACK -> Items.BLACK_WOOL;
            };

            entity.getPersonalInventory().addItem(new ItemStack(woolItem, woolCount));
            entity.swing(InteractionHand.MAIN_HAND);
        }

        sheepToShear.remove(0);

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.DEPOSITING;
        }
    }

    // =========================================================================
    // Phase: MILKING
    // =========================================================================

    private void milkCows(ServerLevel level) {
        entity.setCurrentActivity("Milking cows");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (cowsToMilk.isEmpty()) {
            phase = Phase.DEPOSITING;
            return;
        }

        Cow cow = cowsToMilk.get(0);
        if (!cow.isAlive()) {
            cowsToMilk.remove(0);
            return;
        }

        double distSq = entity.distanceToSqr(cow);
        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(cow, 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Milk the cow (simplified - just add milk bucket)
        entity.getPersonalInventory().addItem(new ItemStack(Items.MILK_BUCKET));
        entity.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cow.blockPosition(),
                SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1.0f, 1.0f);

        cowsToMilk.remove(0);

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.DEPOSITING;
        }
    }

    // =========================================================================
    // Phase: BREEDING
    // =========================================================================

    private void breedAnimals(ServerLevel level) {
        entity.setCurrentActivity("Breeding animals");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (animalsToBreed.size() < 2) {
            phase = Phase.CHECKING;
            return;
        }

        Animal animal1 = animalsToBreed.get(0);
        Animal animal2 = animalsToBreed.get(1);

        if (!animal1.isAlive() || !animal2.isAlive()) {
            animalsToBreed.clear();
            phase = Phase.CHECKING;
            return;
        }

        // Move to first animal
        double distSq = entity.distanceToSqr(animal1);
        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(animal1, 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Get appropriate feed from storage
        ItemStack feed = getFeedForAnimal(level, animal1);
        if (feed.isEmpty()) {
            animalsToBreed.clear();
            phase = Phase.CHECKING;
            return;
        }

        // Feed both animals
        animal1.setInLove(null);
        animal2.setInLove(null);

        level.playSound(null, animal1.blockPosition(),
                SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 1.0f, 1.0f);

        animalsToBreed.clear();
        phase = Phase.CHECKING;
    }

    // =========================================================================
    // Phase: BUTCHERING
    // =========================================================================

    private void butcherAnimals(ServerLevel level) {
        entity.setCurrentActivity("Culling excess animals");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (animalsToButcher.isEmpty()) {
            phase = Phase.DEPOSITING;
            return;
        }

        Animal animal = animalsToButcher.get(0);
        if (!animal.isAlive()) {
            animalsToButcher.remove(0);
            return;
        }

        double distSq = entity.distanceToSqr(animal);
        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(animal, 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Kill animal and collect drops
        animal.hurt(level.damageSources().generic(), animal.getMaxHealth());
        entity.swing(InteractionHand.MAIN_HAND);

        // Drops are handled by vanilla entity death
        // Items will be on ground, could add collection logic here

        animalsToButcher.remove(0);
    }

    // =========================================================================
    // Phase: COLLECTING EGGS
    // =========================================================================

    private void collectEggs(ServerLevel level) {
        entity.setCurrentActivity("Collecting eggs");

        // Scan ground for eggs
        BlockPos penCenter = assignedPen.getOrigin();
        int radius = assignedPen.getRadius();
        AABB penBounds = new AABB(
                penCenter.getX() - radius, penCenter.getY() - 2, penCenter.getZ() - radius,
                penCenter.getX() + radius, penCenter.getY() + 2, penCenter.getZ() + radius
        );

        List<net.minecraft.world.entity.item.ItemEntity> items =
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, penBounds,
                        item -> item.getItem().is(Items.EGG));

        if (items.isEmpty()) {
            phase = Phase.CHECKING;
            return;
        }

        for (net.minecraft.world.entity.item.ItemEntity item : items) {
            double distSq = entity.distanceToSqr(item);
            if (distSq > INTERACT_RANGE_SQ) {
                entity.getNavigation().moveTo(item, 1.0);
                return;
            }

            entity.getPersonalInventory().addItem(item.getItem().copy());
            item.discard();
            entity.swing(InteractionHand.MAIN_HAND);
        }

        phase = Phase.DEPOSITING;
    }
    // =========================================================================
// Phase: FEEDING (for general feeding, not breeding)
// =========================================================================

    private void feedAnimals(ServerLevel level) {
        entity.setCurrentActivity("Feeding animals");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (animalsToFeed.isEmpty()) {
            phase = Phase.CHECKING;
            return;
        }

        Animal animal = animalsToFeed.get(0);
        if (!animal.isAlive()) {
            animalsToFeed.remove(0);
            return;
        }

        double distSq = entity.distanceToSqr(animal);
        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(animal, 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Get feed from storage
        ItemStack feed = getFeedForAnimal(level, animal);
        if (feed.isEmpty()) {
            animalsToFeed.remove(0);
            return;
        }

        // Feed the animal (heal it)
        animal.heal(2.0f);
        level.playSound(null, animal.blockPosition(),
                SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 1.0f, 1.0f);

        animalsToFeed.remove(0);
    }

    // =========================================================================
    // Phase: DEPOSITING
    // =========================================================================

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing products");

        if (farmhouse == null) {
            goIdle();
            return;
        }

        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Deposit all items to farmhouse
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            BuildingStorageAccess.storeItem(level, farmhouse, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }

        goIdle();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean canBreed(Animal animal) {
        return animal.getAge() == 0 && !animal.isInLove();
    }

    private boolean hasFeedInStorage(ServerLevel level) {
        if (farmhouse == null) return false;

        // Check for wheat, seeds, carrots
        return BuildingStorageAccess.hasItem(level, farmhouse, Items.WHEAT, 1)
                || BuildingStorageAccess.hasItem(level, farmhouse, Items.WHEAT_SEEDS, 1)
                || BuildingStorageAccess.hasItem(level, farmhouse, Items.CARROT, 1);
    }

    private ItemStack getFeedForAnimal(ServerLevel level, Animal animal) {
        if (farmhouse == null) return ItemStack.EMPTY;

        // Different animals eat different things
        if (animal instanceof Cow || animal instanceof Sheep) {
            if (BuildingStorageAccess.takeItem(level, farmhouse, Items.WHEAT, 1)) {
                return new ItemStack(Items.WHEAT);
            }
        } else if (animal instanceof Pig) {
            if (BuildingStorageAccess.takeItem(level, farmhouse, Items.CARROT, 1)) {
                return new ItemStack(Items.CARROT);
            }
        } else if (animal instanceof Chicken) {
            if (BuildingStorageAccess.takeItem(level, farmhouse, Items.WHEAT_SEEDS, 1)) {
                return new ItemStack(Items.WHEAT_SEEDS);
            }
        }

        return ItemStack.EMPTY;
    }

    private boolean isPersonalInventoryNearlyFull() {
        var inv = entity.getPersonalInventory();
        int emptySlots = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) emptySlots++;
        }
        return emptySlots < 3;
    }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        currentTarget = null;
        animalsToFeed.clear();
        animalsToBreed.clear();
        sheepToShear.clear();
        chickensForEggs.clear();
        cowsToMilk.clear();
        animalsToButcher.clear();
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
    }

    @Override
    public void stop() {
        goIdle();
    }
}