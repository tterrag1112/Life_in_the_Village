// New file: src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Farmer/FertilizerGoal.java

package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.*;

/**
 * Fertilizer specialist goal - applies bone meal to crops and maintains soil quality.
 */
public class FertilizerGoal extends Goal {

    private static final int TICKS_PER_ACTION = 40;
    private static final int IDLE_COOLDOWN = 300;
    private static final double INTERACT_RANGE_SQ = 4.0;

    private final TownspersonMob entity;

    private Phase phase;
    private int actionTimer;
    private int idleCooldown;

    private FarmPlot assignedPlot;
    private Building farmhouse;
    private List<BlockPos> toFertilize;
    private boolean hasBoneMeal;

    private enum Phase {
        IDLE,
        ANALYZING,
        GETTING_BONE_MEAL,
        FERTILIZING
    }

    public FertilizerGoal(TownspersonMob entity) {
        this.entity = entity;
        this.phase = Phase.IDLE;
        this.toFertilize = new ArrayList<>();
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel)) return false;
        if (idleCooldown > 0) {
            idleCooldown--;
            return false;
        }

        // Only fertilizer role uses this goal
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);
        if (role != FarmRole.FERTILIZER) {
            return false;
        }

        // Must be assigned to a crop plot
        VillageSavedData data = VillageSavedData.get((ServerLevel) entity.level());
        assignedPlot = entity.getAssignedPlotId()
                .flatMap(data::getFarmPlotById)
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .orElse(null);

        return assignedPlot != null;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE;
    }

    @Override
    public void start() {
        phase = Phase.ANALYZING;
        actionTimer = 0;
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case IDLE -> { /* do nothing */ }
            case ANALYZING -> analyze(level);
            case GETTING_BONE_MEAL -> getBoneMeal(level);
            case FERTILIZING -> fertilize(level);
        }
    }

    // =========================================================================
    // Phase: ANALYZING
    // =========================================================================

    private void analyze(ServerLevel level) {
        entity.setCurrentActivity("Planning fertilization...");

        VillageSavedData data = VillageSavedData.get(level);

        farmhouse = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) {
            goIdle();
            return;
        }

        // Scan for crops that need fertilization
        toFertilize.clear();
        for (BlockPos farmland : assignedPlot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);

            // Fertilize growing crops (not fully mature)
            if (state.getBlock() instanceof CropBlock crop && !crop.isMaxAge(state)) {
                // Random chance to fertilize (not every crop every time)
                if (entity.getRandom().nextFloat() < 0.3f) {
                    toFertilize.add(cropPos);
                }
            }
        }

        if (toFertilize.isEmpty()) {
            goIdle();
            return;
        }

        // Check if we have bone meal
        hasBoneMeal = entity.getPersonalInventory().hasAnyOf(Set.of(Items.BONE_MEAL));

        if (!hasBoneMeal) {
            phase = Phase.GETTING_BONE_MEAL;
        } else {
            phase = Phase.FERTILIZING;
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL));
        }
    }

    // =========================================================================
    // Phase: GETTING BONE MEAL
    // =========================================================================

    private void getBoneMeal(ServerLevel level) {
        entity.setCurrentActivity("Getting bone meal");

        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Get bone meal from storage
        boolean taken = BuildingStorageAccess.takeItem(level, farmhouse, Items.BONE_MEAL, 8);

        if (taken) {
            entity.getPersonalInventory().addItem(new ItemStack(Items.BONE_MEAL, 8));
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BONE_MEAL));
            phase = Phase.FERTILIZING;
        } else {
            // No bone meal available
            goIdle();
        }
    }

    // =========================================================================
    // Phase: FERTILIZING
    // =========================================================================

    private void fertilize(ServerLevel level) {
        entity.setCurrentActivity("Fertilizing crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toFertilize.isEmpty()) {
            goIdle();
            return;
        }

        BlockPos cropPos = toFertilize.get(0);
        double distSq = entity.distanceToSqr(
                cropPos.getX(), cropPos.getY(), cropPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    cropPos.getX(), cropPos.getY(), cropPos.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        BlockState state = level.getBlockState(cropPos);
        if (!(state.getBlock() instanceof CropBlock crop)) {
            toFertilize.remove(0);
            return;
        }

        // FIX: Access age property through state properties
        var ageProperty = state.getProperties().stream()
                .filter(p -> p.getName().equals("age"))
                .findFirst();

        if (ageProperty.isEmpty()) {
            toFertilize.remove(0);
            return;
        }

        try {
            net.minecraft.world.level.block.state.properties.IntegerProperty ageProp =
                    (net.minecraft.world.level.block.state.properties.IntegerProperty) ageProperty.get();

            int currentAge = state.getValue(ageProp);
            int maxAge = ageProp.getPossibleValues().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(7);

            // Don't fertilize if already at max age
            if (currentAge >= maxAge) {
                toFertilize.remove(0);
                return;
            }

            // Consume bone meal from inventory
            boolean hasItem = false;
            var inv = entity.getPersonalInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.is(Items.BONE_MEAL)) {
                    stack.shrink(1);
                    hasItem = true;
                    break;
                }
            }

            if (hasItem) {
                // Advance crop growth
                int newAge = Math.min(maxAge, currentAge + entity.getRandom().nextInt(3) + 2);
                level.setBlock(cropPos, state.setValue(ageProp, newAge), 2);

                // Particle effects
                level.levelEvent(2005, cropPos, 0);
                level.playSound(null, cropPos, SoundEvents.BONE_MEAL_USE, SoundSource.BLOCKS, 1.0f, 1.0f);

                entity.swing(InteractionHand.MAIN_HAND);

                // TODO: Update crop quality tracker here
            }
        } catch (Exception e) {
            // Property access failed, skip this crop
            System.err.println("Failed to access crop age property: " + e.getMessage());
        }

        toFertilize.remove(0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        toFertilize.clear();
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
    }

    @Override
    public void stop() {
        goIdle();
    }
}