package tterrag1112.life_in_the_village.Kingdom.Castle.Placers;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Determines what liquid (or absence thereof) fills the moat channel.
 * Serialised as a string in FeatureConfig JSON.
 */
public enum MoatFillType implements StringRepresentable {

    WATER  ("water",  () -> Blocks.WATER.defaultBlockState()),
    LAVA   ("lava",   () -> Blocks.LAVA.defaultBlockState()),
    EMPTY  ("empty",  () -> Blocks.AIR.defaultBlockState()),
    /** Poison — water tinted green via biome or via cauldron-like custom block. */
    POISON ("poison", () -> Blocks.WATER.defaultBlockState()); // visual handled by RuinationPass

    private final String serialName;
    private final java.util.function.Supplier<BlockState> blockSupplier;

    MoatFillType(String serialName, java.util.function.Supplier<BlockState> blockSupplier) {
        this.serialName    = serialName;
        this.blockSupplier = blockSupplier;
    }

    public BlockState getBlock() {
        return blockSupplier.get();
    }

    public static final Codec<MoatFillType> CODEC =
            StringRepresentable.fromEnum(MoatFillType::values);

    @Override
    public String getSerializedName() {
        return serialName;
    }
}