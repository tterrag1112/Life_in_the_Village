package tterrag1112.life_in_the_village.Profession;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.tags.ItemTags;

/**
 * Heuristic mapping from an item to the {@link PlayerProfession} most
 * likely to be relevant for it. Used only for commission-board filtering;
 * not authoritative for XP or gameplay systems.
 *
 * Returns {@code null} when no profession matches (e.g. generic materials).
 */
public final class PlayerProfessionRelevance {

    private PlayerProfessionRelevance() {}

    public static PlayerProfession forItem(Item item) {
        ItemStack stack = new ItemStack(item);
        // Delegate to each profession's existing isRelevantCraft check first
        // (covers tools, weapons, planks, processed food, etc.)
        for (PlayerProfession prof : PlayerProfession.values()) {
            if (prof == PlayerProfession.GUARD) continue;    // combat-only
            if (prof == PlayerProfession.MERCHANT) continue; // accepts everything
            if (prof.isRelevantCraft(stack)) return prof;
        }
        // Broader raw-material heuristics not captured by isRelevantCraft
        if (stack.is(ItemTags.LOGS))
            return PlayerProfession.CARPENTER;
        if (item == Items.WHEAT || item == Items.CARROT
                || item == Items.POTATO || item == Items.APPLE
                || item == Items.BEETROOT || item == Items.SUGAR_CANE)
            return PlayerProfession.FARMER;
        if (stack.is(ItemTags.COALS) || item == Items.IRON_ORE
                || item == Items.GOLD_ORE || item == Items.COPPER_ORE
                || item == Items.RAW_IRON || item == Items.RAW_GOLD
                || item == Items.RAW_COPPER)
            return PlayerProfession.MINER;
        return null;
    }
}
