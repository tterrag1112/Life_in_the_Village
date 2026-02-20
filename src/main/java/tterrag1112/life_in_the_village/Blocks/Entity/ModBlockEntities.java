package tterrag1112.life_in_the_village.Blocks.Entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import tterrag1112.life_in_the_village.Blocks.Entity.custom.VillageFoundationBlockEntity;
import tterrag1112.life_in_the_village.Blocks.ModBlocks;
import tterrag1112.life_in_the_village.Blocks.custom.VillageFoundationBlock;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Life_in_the_village.MODID);

    public static final Supplier<BlockEntityType<VillageFoundationBlockEntity>> VILLAGE_FOUNDATION_BE = BLOCK_ENTITIES.register(
            "village_foundation_be",
            () -> new BlockEntityType<>(
                    VillageFoundationBlockEntity::new,
                    ModBlocks.VILLAGE_FOUNDATION.get()));

    public static void register(IEventBus eventBus){
        BLOCK_ENTITIES.register(eventBus);
    }
}
