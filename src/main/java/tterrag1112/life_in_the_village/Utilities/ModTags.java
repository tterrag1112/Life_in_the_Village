package tterrag1112.life_in_the_village.Utilities;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Life_in_the_village;

public class ModTags {

    public static class Items{
        public static final TagKey<Item> IS_CURRENCY = createTag("is_currency");
        /** Track 5a — items a HEALER NPC accepts as a donation. */
        public static final TagKey<Item> HEALER_DONATION = createTag("healer_donation");

        private static TagKey<Item> createTag(String name) {
            return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, name));

        }

    }
}
