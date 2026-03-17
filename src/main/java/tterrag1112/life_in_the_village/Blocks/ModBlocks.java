package tterrag1112.life_in_the_village.Blocks;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Blocks.custom.GuardPostBlock;
import tterrag1112.life_in_the_village.Blocks.custom.VillageFoundationBlock;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.function.Function;
import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Life_in_the_village.MODID);

    public static final DeferredBlock<Block> VILLAGE_FOUNDATION = registerBlock("village_foundation",
            VillageFoundationBlock::new,
            BlockBehaviour.Properties.of());
    public static final DeferredBlock<Block> GUARD_POST = registerBlock("guard_post",
            GuardPostBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(2.0f)
                    .requiresCorrectToolForDrops());




    private static <T extends Block> DeferredBlock<T> registerBlock(String name, Supplier<T> block) {
        DeferredBlock<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }
    private static <T extends Block> DeferredBlock<T> registerBlock(String name, Function<BlockBehaviour.Properties, ? extends T> func, BlockBehaviour.Properties props) {
        DeferredBlock<T> toReturn = BLOCKS.register(name, (key) -> func.apply(props.setId(ResourceKey.create(Registries.BLOCK, key))));
        registerBlockItem(name, toReturn);
        return toReturn;
    }

    private static <T extends Block> void registerBlockItem(String name, DeferredBlock<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties().useBlockDescriptionPrefix()
                .setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, name)))));
    }
}
