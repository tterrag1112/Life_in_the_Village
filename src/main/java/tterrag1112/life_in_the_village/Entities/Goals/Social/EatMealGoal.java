package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.WorkSchedule;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Needs.FoodValueHelper;

import java.util.EnumSet;
import java.util.Optional;

/**
 * NPCs eat a meal during their scheduled meal window.
 *
 * <h3>Behavior</h3>
 * <ol>
 *   <li>Activates when the NPC's {@link WorkSchedule.DayPhase} is MEAL</li>
 *   <li>NPC walks toward their house (if not already nearby)</li>
 *   <li>Finds food in the house's storage containers</li>
 *   <li>Consumes one food item with an eating sound and animation</li>
 *   <li>Stands still for a brief duration (simulating the meal)</li>
 *   <li>Deactivates after one meal per window — a flag prevents re-eating</li>
 * </ol>
 *
 * <h3>Changes from previous version</h3>
 * <ul>
 *   <li>Old: triggered on a flat 6000-tick timer regardless of time of day</li>
 *   <li>New: triggers during the MEAL phase from WorkSchedule, so NPCs eat
 *       at a predictable midday time that players can observe</li>
 *   <li>New: NPC walks toward home before eating (was instant-consume)</li>
 *   <li>New: brief stationary period to look like they're actually eating</li>
 *   <li>New: only eats once per meal window</li>
 * </ul>
 */
public class EatMealGoal extends Goal {

    private static final int EAT_DURATION = 80; // ticks to stand still while "eating"
    private static final double HOME_RANGE_SQ = 64; // 8 blocks

    private final TownspersonMob entity;

    private enum Phase { WALKING, EATING, DONE }
    private Phase phase = Phase.DONE;
    private int eatTimer = 0;
    private boolean ateThisWindow = false;
    private long lastMealWindowStart = -1;

    public EatMealGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!entity.hasHome()) return false;
        if (!(entity.level() instanceof ServerLevel)) return false;

        // Check if we're in the meal window
        boolean isMealTime = WorkSchedule.isMealTime(entity);

        if (!isMealTime) {
            // Reset the flag when the window closes so we eat next window
            ateThisWindow = false;
            return false;
        }

        // Only eat once per meal window
        if (ateThisWindow) return false;

        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE) return false;
        return WorkSchedule.isMealTime(entity);
    }

    @Override
    public void start() {
        phase = Phase.WALKING;
        eatTimer = 0;
        entity.setCurrentActivity("Heading to eat");
        navigateToHome();
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        if (phase == Phase.DONE || phase == Phase.EATING) {
            ateThisWindow = true;
        }
        phase = Phase.DONE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case WALKING -> tickWalking(level);
            case EATING  -> tickEating(level);
            case DONE    -> {} // shouldn't happen, canContinueToUse returns false
        }
    }

    // =========================================================================
    // Phase handlers
    // =========================================================================

    private void tickWalking(ServerLevel level) {
        // Check if close enough to home
        Optional<BlockPos> homePos = getHomePosition(level);
        if (homePos.isEmpty()) {
            // No home found — just eat where we stand
            tryEat(level);
            return;
        }

        double distSq = entity.distanceToSqr(
                homePos.get().getX(), homePos.get().getY(), homePos.get().getZ());

        if (distSq <= HOME_RANGE_SQ) {
            // Close enough — try to eat
            entity.getNavigation().stop();
            tryEat(level);
        } else if (!entity.getNavigation().isInProgress()) {
            // Re-navigate if path expired
            navigateToHome();
        }
    }

    private void tickEating(ServerLevel level) {
        eatTimer++;

        // Look down slightly (eating animation)
        entity.getLookControl().setLookAt(
                entity.getX(), entity.getY() - 1, entity.getZ());

        // Eating particles/sound at start
        if (eatTimer == 1) {
            entity.playSound(SoundEvents.GENERIC_EAT.value(), 0.5f, 1.0f);
            entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }

        // Second bite sound
        if (eatTimer == EAT_DURATION / 2) {
            entity.playSound(SoundEvents.GENERIC_EAT.value(), 0.3f, 1.1f);
        }

        if (eatTimer >= EAT_DURATION) {
            entity.setCurrentActivity("");
            phase = Phase.DONE;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void tryEat(ServerLevel level) {
        Optional<Building> house = entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));

        if (house.isEmpty()) {
            phase = Phase.DONE;
            return;
        }

        // Find food in house storage
        for (var container : BuildingStorageAccess.findInventories(level, house.get())) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                if (!FoodValueHelper.isFood(stack.getItem())) continue;

                // Consume one
                stack.shrink(1);
                if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);

                entity.setCurrentActivity("Eating");
                phase = Phase.EATING;
                eatTimer = 0;
                return;
            }
        }

        // No food found — skip meal
        entity.setCurrentActivity("No food...");
        phase = Phase.DONE;
    }

    private void navigateToHome() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        getHomePosition(level).ifPresent(pos ->
                entity.getNavigation().moveTo(
                        pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.9));
    }

    private Optional<BlockPos> getHomePosition(ServerLevel level) {
        return entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .map(b -> b.getShape().getOrigin());
    }
}