package tterrag1112.life_in_the_village.Village.Needs;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class FoodValueHelper {

    private FoodValueHelper() {}

    /**
     * Returns the total effective nutrition in the stack.
     * Returns 0 for non-food items.
     */
    public static float getStackNutrition(ItemStack stack) {
        if (stack.isEmpty()) return 0f;
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) return 0f;
        float perItem = food.nutrition() * food.saturation() * 2.0f;
        return perItem * stack.getCount();
    }

    /**
     * Returns the per-item effective nutrition value.
     */
    public static float getItemNutrition(Item item) {
        // Items no longer hold food properties directly — create a dummy stack
        // to query the default component value
        FoodProperties food = new ItemStack(item).get(DataComponents.FOOD);
        if (food == null) return 0f;
        return food.nutrition() * food.saturation() * 2.0f;
    }

    public static boolean isFood(Item item) {
        return new ItemStack(item).get(DataComponents.FOOD) != null;
    }
    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null;
    }
}