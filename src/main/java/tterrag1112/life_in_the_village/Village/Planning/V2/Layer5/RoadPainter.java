package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;

/**
 * V2 Layer 5 — paint road blocks along skeleton centerlines.
 *
 * <p>Walks each segment at 1-block resolution, places
 * {@code culture.roadMaterial} (resolved from the block registry,
 * fallback {@link Blocks#DIRT_PATH}) in a {@code width}-wide
 * perpendicular strip at surface y - 1 (the solid block one
 * below the heightmap result).
 *
 * <p>Crude vs V1's {@code OrganicRoadPlacer}: no drift, no organic
 * edge fade, no biome-aware material variants. Trade-off: simpler
 * code, no dependency on V1's PathMaterial / BuildingFootprint
 * types. Swap to OrganicRoadPlacer in a follow-up cycle if V1
 * villages need the visual quality.
 */
public final class RoadPainter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadPainter.class);

    /** Air clearance above the road surface (so trees / leftover
     *  vegetation that sneaked in don't sit IN the road). */
    private static final int ROAD_HEAD_CLEARANCE = 3;

    private RoadPainter() {}

    public static int paintAll(ServerLevel level, RoadNetwork roads, Culture culture) {
        BlockState material = resolveMaterial(culture.roadMaterial());
        int total = 0;
        for (RoadSegment seg : roads.skeleton().allSegments()) {
            total += paintSegment(level, seg, material);
        }
        return total;
    }

    private static int paintSegment(ServerLevel level, RoadSegment seg, BlockState material) {
        BlockPos a = seg.start();
        BlockPos b = seg.end();
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return 0;
        int steps = Math.max(1, (int) Math.round(len));

        // Perpendicular unit vector in XZ.
        double perpX = -dz / len;
        double perpZ = dx / len;
        int half = (seg.width() + 1) / 2;
        int painted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int cx = (int) Math.round(a.getX() + dx * t);
            int cz = (int) Math.round(a.getZ() + dz * t);
            for (int p = -half; p <= half; p++) {
                int x = cx + (int) Math.round(perpX * p);
                int z = cz + (int) Math.round(perpZ * p);
                int surfY = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int paveY = surfY - 1;
                cursor.set(x, paveY, z);
                level.setBlock(cursor, material, 2);
                painted++;
                // Air clearance above the new pave.
                for (int dy = 1; dy <= ROAD_HEAD_CLEARANCE; dy++) {
                    cursor.set(x, paveY + dy, z);
                    BlockState bs = level.getBlockState(cursor);
                    if (!bs.isAir()) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        return painted;
    }

    /** Parse {@code id} (e.g. {@code "minecraft:dirt_path"}) into a
     *  block state. Logs once on failure, falls back to dirt path. */
    private static BlockState resolveMaterial(String id) {
        if (id == null || id.isEmpty()) return Blocks.DIRT_PATH.defaultBlockState();
        try {
            Identifier rid = Identifier.parse(id);
            return BuiltInRegistries.BLOCK.getOptional(rid)
                    .map(Block::defaultBlockState)
                    .orElseGet(() -> {
                        LOGGER.warn("RoadPainter: unknown road material '{}', falling back to dirt path", id);
                        return Blocks.DIRT_PATH.defaultBlockState();
                    });
        } catch (Exception e) {
            LOGGER.warn("RoadPainter: malformed road material id '{}': {}", id, e.getMessage());
            return Blocks.DIRT_PATH.defaultBlockState();
        }
    }
}
