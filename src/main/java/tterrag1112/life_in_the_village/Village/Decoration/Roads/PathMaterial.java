package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;

import java.util.*;

/**
 * Defines a road surface as a weighted mix of blocks.
 *
 * <h3>Example</h3>
 * A cobblestone road might be:
 * <pre>
 *   cobblestone: 60%
 *   andesite:    20%
 *   stone:       10%
 *   gravel:      10%
 * </pre>
 *
 * Each time a road block is placed, one entry is sampled based on weights.
 * This produces natural-looking variation without hand-painting every block.
 *
 * <h3>Edge blending</h3>
 * Roads also define an "edge" material for their outer 1-2 blocks, which
 * transitions from the road surface to the natural terrain. For example,
 * a cobblestone road might have coarse_dirt edges that fade to grass.
 *
 * <h3>Upgrades</h3>
 * Materials are tiered. When a village upgrades its roads, the PathMaterial
 * is swapped — the road positions stay the same, only the surface changes.
 */
public class PathMaterial {

    /**
     * A single weighted block entry.
     */
    public record WeightedBlock(
            Block block,
            float weight
    ) {
        public static final Codec<WeightedBlock> CODEC = RecordCodecBuilder.create(i -> i.group(
                Identifier.CODEC.fieldOf("block")
                        .forGetter(wb -> BuiltInRegistries.BLOCK.getKey(wb.block)),
                Codec.FLOAT.fieldOf("weight")
                        .forGetter(WeightedBlock::weight)
        ).apply(i, (id, w) -> new WeightedBlock(
                BuiltInRegistries.BLOCK.get(id).map(h -> h.value()).orElse(Blocks.COBBLESTONE), w)));

        public BlockState state() {
            return block.defaultBlockState();
        }
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final String name;
    private final List<WeightedBlock> coreBlocks;
    private final List<WeightedBlock> edgeBlocks;
    private final float totalCoreWeight;
    private final float totalEdgeWeight;

    // =========================================================================
    // Constructor
    // =========================================================================

    public PathMaterial(String name,
                        List<WeightedBlock> coreBlocks,
                        List<WeightedBlock> edgeBlocks) {
        this.name = name;
        this.coreBlocks = List.copyOf(coreBlocks);
        this.edgeBlocks = List.copyOf(edgeBlocks);
        this.totalCoreWeight = (float) coreBlocks.stream()
                .mapToDouble(WeightedBlock::weight).sum();
        this.totalEdgeWeight = (float) edgeBlocks.stream()
                .mapToDouble(WeightedBlock::weight).sum();
    }

    // =========================================================================
    // Sampling
    // =========================================================================

    /**
     * Samples a core road block based on weights.
     * Called for the center and inner portions of the road.
     */
    public BlockState sampleCore(RandomSource random) {
        return sample(coreBlocks, totalCoreWeight, random);
    }

    /**
     * Samples an edge/transition block based on weights.
     * Called for the outer 1-2 blocks of the road where it meets terrain.
     */
    public BlockState sampleEdge(RandomSource random) {
        return sample(edgeBlocks, totalEdgeWeight, random);
    }

    /**
     * Returns the primary (highest weight) core block.
     * Used for validation and display purposes.
     */
    public Block primaryBlock() {
        return coreBlocks.stream()
                .max(Comparator.comparingDouble(WeightedBlock::weight))
                .map(WeightedBlock::block)
                .orElse(Blocks.COBBLESTONE);
    }

    public String getName() { return name; }
    public List<WeightedBlock> getCoreBlocks() { return coreBlocks; }
    public List<WeightedBlock> getEdgeBlocks() { return edgeBlocks; }

    // =========================================================================
    // Internal sampling
    // =========================================================================

    private static BlockState sample(List<WeightedBlock> blocks,
                                     float totalWeight,
                                     RandomSource random) {
        if (blocks.isEmpty()) return Blocks.DIRT_PATH.defaultBlockState();
        if (blocks.size() == 1) return blocks.get(0).state();

        float roll = random.nextFloat() * totalWeight;
        float cumulative = 0;
        for (WeightedBlock wb : blocks) {
            cumulative += wb.weight;
            if (roll < cumulative) return wb.state();
        }
        return blocks.get(blocks.size() - 1).state();
    }

    // =========================================================================
    // Built-in presets (biome-aware)
    // =========================================================================

    /** Dirt path — bare earth with occasional grass. Tier 0. */
    public static PathMaterial dirt() {
        return new PathMaterial("dirt",
                List.of(
                        new WeightedBlock(Blocks.DIRT_PATH, 0.7f),
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.2f),
                        new WeightedBlock(Blocks.DIRT, 0.1f)
                ),
                List.of(
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.5f),
                        new WeightedBlock(Blocks.DIRT, 0.3f),
                        new WeightedBlock(Blocks.GRASS_BLOCK, 0.2f)
                ));
    }

    /** Gravel path — packed gravel with stone chips. Tier 1. */
    public static PathMaterial gravel() {
        return new PathMaterial("gravel",
                List.of(
                        new WeightedBlock(Blocks.GRAVEL, 0.55f),
                        new WeightedBlock(Blocks.DIRT_PATH, 0.25f),
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.2f)
                ),
                List.of(
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.4f),
                        new WeightedBlock(Blocks.DIRT_PATH, 0.3f),
                        new WeightedBlock(Blocks.GRASS_BLOCK, 0.3f)
                ));
    }

    /** Cobblestone road — mixed stone surface. Tier 2. */
    public static PathMaterial cobblestone() {
        return new PathMaterial("cobblestone",
                List.of(
                        new WeightedBlock(Blocks.COBBLESTONE, 0.55f),
                        new WeightedBlock(Blocks.ANDESITE, 0.20f),
                        new WeightedBlock(Blocks.STONE, 0.15f),
                        new WeightedBlock(Blocks.GRAVEL, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.GRAVEL, 0.4f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.3f),
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.3f)
                ));
    }

    /** Stone brick road — refined surface for wealthy villages. Tier 3. */
    public static PathMaterial stoneBrick() {
        return new PathMaterial("stone_brick",
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.50f),
                        new WeightedBlock(Blocks.STONE, 0.25f),
                        new WeightedBlock(Blocks.POLISHED_ANDESITE, 0.15f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.COBBLESTONE, 0.4f),
                        new WeightedBlock(Blocks.STONE, 0.3f),
                        new WeightedBlock(Blocks.GRAVEL, 0.3f)
                ));
    }

    /** Desert path — sand and sandstone mix. */
    public static PathMaterial desert() {
        return new PathMaterial("desert",
                List.of(
                        new WeightedBlock(Blocks.SMOOTH_SANDSTONE, 0.50f),
                        new WeightedBlock(Blocks.SANDSTONE, 0.30f),
                        new WeightedBlock(Blocks.SAND, 0.20f)
                ),
                List.of(
                        new WeightedBlock(Blocks.SAND, 0.6f),
                        new WeightedBlock(Blocks.SANDSTONE, 0.4f)
                ));
    }

    /** Snowy path — packed ice and stone. */
    public static PathMaterial snowy() {
        return new PathMaterial("snowy",
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICKS, 0.40f),
                        new WeightedBlock(Blocks.COBBLESTONE, 0.30f),
                        new WeightedBlock(Blocks.GRAVEL, 0.20f),
                        new WeightedBlock(Blocks.PACKED_ICE, 0.10f)
                ),
                List.of(
                        new WeightedBlock(Blocks.GRAVEL, 0.4f),
                        new WeightedBlock(Blocks.SNOW_BLOCK, 0.3f),
                        new WeightedBlock(Blocks.COARSE_DIRT, 0.3f)
                ));
    }

    /**
     * Returns the appropriate material for a biome style and tier.
     */
    public static PathMaterial forBiomeAndTier(VillageBiomeStyle biome,
                                               VillagePath.PathTier tier) {
        // Desert and snowy have their own palettes at all tiers
        if (biome == VillageBiomeStyle.DESERT) return desert();
        if (biome == VillageBiomeStyle.SNOWY) return snowy();

        return switch (tier) {
            case DIRT        -> dirt();
            case GRAVEL      -> gravel();
            case COBBLESTONE -> cobblestone();
            case STONE_BRICK -> stoneBrick();
        };
    }
}