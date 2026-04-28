package tterrag1112.life_in_the_village.Blocks.custom;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import tterrag1112.life_in_the_village.Blocks.Entity.custom.SubBuildingAnchorBlockEntity;

/**
 * Doc 03 §"Detection by anchor block entities" — author-only marker
 * block. Place inside a building NBT to mark a subbuilding origin;
 * {@code SubBuildingScanner} consumes the block at placement time
 * and replaces it with air.
 *
 * <p>The block has no creative-menu entry. Use {@code /give} or NBT
 * tooling to obtain it. It is harmless if it ever ends up in the
 * world outside an authored structure — it has no behavior of its
 * own beyond holding a {@link SubBuildingAnchorBlockEntity}.</p>
 */
public class SubBuildingAnchorBlock extends BaseEntityBlock {

    public static final MapCodec<SubBuildingAnchorBlock> CODEC =
            simpleCodec(SubBuildingAnchorBlock::new);

    public SubBuildingAnchorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SubBuildingAnchorBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
