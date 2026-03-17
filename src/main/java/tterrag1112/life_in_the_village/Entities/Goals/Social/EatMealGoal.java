package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Needs.FoodValueHelper;

import java.util.EnumSet;

public class EatMealGoal extends Goal {

    private static final int EAT_INTERVAL = 6000; // eat every 5 min
    private int eatTimer = 0;

    private final TownspersonMob entity;

    public EatMealGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        if (!entity.hasHome()) return false;
        eatTimer++;
        return eatTimer >= EAT_INTERVAL;
    }

    @Override
    public boolean canContinueToUse() { return false; } // instant

    @Override
    public void start() {
        eatTimer = 0;
        if (!(entity.level() instanceof ServerLevel level)) return;

        entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .ifPresent(house -> {
                    // Find any food in home storage and consume one item
                    for (var container : BuildingStorageAccess.findInventories(level, house)) {
                        for (int i = 0; i < container.getContainerSize(); i++) {
                            ItemStack stack = container.getItem(i);
                            if (stack.isEmpty()) continue;
                            if (!FoodValueHelper.isFood(stack.getItem())) continue;

                            // Consume one item
                            stack.shrink(1);
                            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);

                            // Play eat sound
                            entity.playSound(
                                    SoundEvents.GENERIC_EAT.value(),
                                    0.5f, 1.0f
                            );
                            return;
                        }
                    }
                });
    }
}
