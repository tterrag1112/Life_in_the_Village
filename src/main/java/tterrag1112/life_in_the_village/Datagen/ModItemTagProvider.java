package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.data.ItemTagsProvider;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Utilities.ModTags;

import java.util.concurrent.CompletableFuture;

public class ModItemTagProvider extends ItemTagsProvider {
    public ModItemTagProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, Life_in_the_village.MODID);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ModTags.Items.IS_CURRENCY)
                .add(ModItems.DENIER.get())
                .add(ModItems.DENIER_ARGENT.get())
                .add(ModItems.DENIER_OR.get())
                .add(Items.DIAMOND)
                .add(Items.EMERALD)
                .add(Items.GOLD_INGOT);


    }
}
