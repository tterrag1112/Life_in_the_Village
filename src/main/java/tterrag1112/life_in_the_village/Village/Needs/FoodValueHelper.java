package tterrag1112.life_in_the_village.Village.Needs;

import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class FoodValueHelper {

    /**
     * Returns the nutrition value of one item using vanilla FoodProperties.
     * Returns 0 if the item is not food.
     */
    public static int getNutrition(Item item) {
        FoodProperties food = item.components()
                .get(net.minecraft.core.component.DataComponents.FOOD);
        if (food == null) return 0;
        return food.nutrition();
    }

    /**
     * Total food value of a stack.
     */
    public static int getStackNutrition(ItemStack stack) {
        return getNutrition(stack.getItem()) * stack.getCount();
    }

    public static boolean isFood(Item item) {
        return getNutrition(item) > 0;
    }
}
