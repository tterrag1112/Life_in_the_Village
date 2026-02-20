package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.data.PackOutput;
import tterrag1112.life_in_the_village.Blocks.Entity.ModBlockEntities;
import tterrag1112.life_in_the_village.Blocks.ModBlocks;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Life_in_the_village;

public class ModModelProvider extends ModelProvider {
    public ModModelProvider(PackOutput output) {
        super(output, Life_in_the_village.MODID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        itemModels.generateFlatItem(ModItems.DENIER.get(), ModelTemplates.FLAT_ITEM);
        itemModels.generateFlatItem(ModItems.DENIER_ARGENT.get(), ModelTemplates.FLAT_ITEM);
        itemModels.generateFlatItem(ModItems.DENIER_OR.get(), ModelTemplates.FLAT_ITEM);
        itemModels.generateFlatItem(ModItems.PURSE.get(), ModelTemplates.FLAT_HANDHELD_ITEM);
        itemModels.generateFlatItem(ModItems.MONEYBIN.get(), ModelTemplates.FLAT_HANDHELD_ITEM);
        itemModels.generateFlatItem(ModItems.VILLAGE_MAP.get(), ModelTemplates.FLAT_HANDHELD_ITEM);
        blockModels.createTrivialCube(ModBlocks.VILLAGE_FOUNDATION.get());

    }
}
