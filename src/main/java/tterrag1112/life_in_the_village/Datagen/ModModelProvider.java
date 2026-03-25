package tterrag1112.life_in_the_village.Datagen;

import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ItemModelUtils;
import net.minecraft.client.data.models.model.ModelLocationUtils;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Blocks.Entity.ModBlockEntities;
import tterrag1112.life_in_the_village.Blocks.ModBlocks;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Life_in_the_village;

import static net.minecraft.client.data.models.ItemModelGenerators.createFlatModelDispatch;

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
        blockModels.createTrivialCube(ModBlocks.GUARD_POST.get());
        this.generateGrimoir(itemModels, ModItems.KINGDOM_BOOK.get());
        this.generateGrimoir(itemModels, ModItems.COMPANY_LEDGER.get());

        blockModels.createTrivialCube(ModBlocks.NOBLE_STONE.get());


    }


    public void generateGrimoir(ItemModelGenerators itemModel, Item grimoirItem) {
        ItemModel.Unbaked itemmodel$unbaked = ItemModelUtils.plainModel(itemModel.createFlatItemModel(grimoirItem, ModelTemplates.FLAT_HANDHELD_ITEM));
        ItemModel.Unbaked itemmodel$unbaked1 = ItemModelUtils.plainModel(ModelLocationUtils.getModelLocation(grimoirItem, "_3d"));
        itemModel.itemModelOutput.accept(grimoirItem, createFlatModelDispatch(itemmodel$unbaked, itemmodel$unbaked1));
    }
}
