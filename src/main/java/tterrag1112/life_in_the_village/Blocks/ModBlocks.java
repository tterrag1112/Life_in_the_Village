package tterrag1112.life_in_the_village.Blocks;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Blocks.custom.GuardPostBlock;
import tterrag1112.life_in_the_village.Blocks.custom.SubBuildingAnchorBlock;
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
    public static final DeferredBlock<Block> NOBLE_STONE = registerBlock("noble_stone",
            Block::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.REINFORCED_DEEPSLATE));

    /**
     * Doc 03 §"Detection by anchor block entities" — author-only
     * marker. Lives inside building NBTs; consumed by
     * {@code SubBuildingScanner} at building-placement time.
     * No creative-menu entry; use {@code /give} or NBT tooling.
     */
    public static final DeferredBlock<SubBuildingAnchorBlock> SUBBUILDING_ANCHOR = registerBlock(
            "subbuilding_anchor",
            SubBuildingAnchorBlock::new,
            BlockBehaviour.Properties.of()
                    .strength(0.5f)
                    .noOcclusion());




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
