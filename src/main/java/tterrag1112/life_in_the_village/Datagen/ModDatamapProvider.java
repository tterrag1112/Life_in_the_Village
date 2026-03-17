package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.data.DataMapProvider;
import tterrag1112.life_in_the_village.Village.Economy.Currency.ModCurrency;

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
