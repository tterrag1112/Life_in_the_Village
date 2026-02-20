package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.data.DataMapProvider;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import tterrag1112.life_in_the_village.Currency.ModCurrency;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModDatamapProvider extends DataMapProvider {
    public ModDatamapProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> lookupProvider){
        super(packOutput, lookupProvider);
    }
    @Override
    protected void gather(HolderLookup.Provider provider) {
        this.builder(ModCurrency.MOD_CURRENCY)
                .add(Items.DIAMOND.asItem().builtInRegistryHolder(), new ModCurrency("Diamonds", 10), false)
                .add(Items.EMERALD.asItem().builtInRegistryHolder(), new ModCurrency("Emeralds", 15), false)
                .build();
    }


}
