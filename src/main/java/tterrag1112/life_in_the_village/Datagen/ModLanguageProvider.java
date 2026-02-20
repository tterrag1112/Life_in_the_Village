package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.LanguageProvider;
import net.neoforged.neoforge.registries.DeferredBlock;
import tterrag1112.life_in_the_village.Blocks.Entity.ModBlockEntities;
import tterrag1112.life_in_the_village.Blocks.ModBlocks;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Life_in_the_village;

public class ModLanguageProvider extends LanguageProvider {
    public ModLanguageProvider(PackOutput output) {
        super(output, Life_in_the_village.MODID, "en_us");
    }

    public static String capitalizeString(String string) {
        char[] chars = string.toLowerCase().toCharArray();
        boolean found = false;
        for (int i = 0; i < chars.length; i++) {
            if (!found && Character.isLetter(chars[i])) {
                chars[i] = Character.toUpperCase(chars[i]);
                found = true;
            } else if (Character.isWhitespace(chars[i]) || chars[i] == '.' || chars[i] == '\'') {
                found = false;
            }
        }
        return String.valueOf(chars);
    }
    private void generateItemTranslations(Item item) {
        String temp = capitalizeString(Identifier.read(String.valueOf(item)).getOrThrow().getPath().replace("_", " "));
        this.add(item, temp);
    }
    private void generateBlockTranslations(DeferredBlock<Block> block) {
        String temp = capitalizeString(Identifier.read(String.valueOf(block.getId())).getOrThrow().getPath().replace("_", " "));
        this.addBlock(block, temp);
    }

    private void generateItemTagTranslations(TagKey<Item> itemTag) {
        String temp = capitalizeString(itemTag.location().getPath().replace("_", " "));
        this.add(itemTag, temp);
    }

    @Override
    protected void addTranslations() {
        generateItemTranslations(ModItems.DENIER.get());
        generateItemTranslations(ModItems.DENIER_ARGENT.get());
        generateItemTranslations(ModItems.DENIER_OR.get());
        generateItemTranslations(ModItems.PURSE.get());
        generateItemTranslations(ModItems.MONEYBIN.get());
        generateItemTranslations(ModItems.VILLAGE_MAP.get());
        generateBlockTranslations(ModBlocks.VILLAGE_FOUNDATION);




    }
}
