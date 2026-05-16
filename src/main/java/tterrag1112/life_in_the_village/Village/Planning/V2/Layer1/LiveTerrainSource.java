package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Track E1 — production {@link TerrainSource} backed by a live
 * {@link Level}. Pure forwarding wrapper; introduces no semantics of
 * its own. The live spawn path is byte-identical with this in place
 * because every method here is a single call to the corresponding
 * {@code Level} method, with the {@code -1} on heightmap reads moved
 * inside so callers no longer perform it themselves.
 */
public final class LiveTerrainSource implements TerrainSource {

    private final Level level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LiveTerrainSource(Level level) {
        this.level = level;
    }

    @Override
    public int height(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    @Override
    public BlockState blockAt(int x, int y, int z) {
        cursor.set(x, y, z);
        return level.getBlockState(cursor);
    }

    @Override
    public int maxY() {
        return level.getMaxY();
    }

    @Override
    public Holder<Biome> biomeAt(BlockPos pos) {
        return level.getBiome(pos);
    }
}
