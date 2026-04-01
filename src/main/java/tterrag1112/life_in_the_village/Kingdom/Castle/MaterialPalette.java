package tterrag1112.life_in_the_village.Kingdom.Castle;


import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.List;

/**
 * Describes the block palette for one material role (primary wall, accent, floor, etc.).
 *
 * Supports weighted random selection so e.g. a wall can be 80% stone bricks
 * and 20% mossy stone bricks for natural variation, without extra logic in placers.
 *
 * Also carries optional stair/slab variants so the structural placers can use
 * the correct decorative block without hardcoding Blocks.STONE_BRICK_STAIRS everywhere.
 */
public record MaterialPalette(
        List<WeightedBlock> mainBlocks,        // Weighted list for bulk fill
        List<WeightedBlock> crackedVariants,   // Used during ruination
        Block stairBlock,                       // Nullable-equivalent: use Blocks.AIR for none
        Block slabBlock,
        Block wallBlock                         // For wall-type decorations (parapet walls etc.)
) {

    // -------------------------------------------------------------------------
    // Codec
    // -------------------------------------------------------------------------

    public static final Codec<MaterialPalette> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    WeightedBlock.CODEC.listOf().fieldOf("main_blocks").forGetter(MaterialPalette::mainBlocks),
                    WeightedBlock.CODEC.listOf().fieldOf("cracked_variants").forGetter(MaterialPalette::crackedVariants),
                    BuiltInRegistries.BLOCK.byNameCodec().fieldOf("stair_block").forGetter(MaterialPalette::stairBlock),
                    BuiltInRegistries.BLOCK.byNameCodec().fieldOf("slab_block").forGetter(MaterialPalette::slabBlock),
                    BuiltInRegistries.BLOCK.byNameCodec().fieldOf("wall_block").forGetter(MaterialPalette::wallBlock)
            ).apply(instance, MaterialPalette::new)
    );

    // -------------------------------------------------------------------------
    // Weighted inner record
    // -------------------------------------------------------------------------

    public record WeightedBlock(Block block, int weight) {
        public static final Codec<WeightedBlock> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(WeightedBlock::block),
                        Codec.INT.fieldOf("weight").forGetter(WeightedBlock::weight)
                ).apply(instance, WeightedBlock::new)
        );
    }

    // -------------------------------------------------------------------------
    // Selection helpers
    // -------------------------------------------------------------------------

    /**
     * Pick a random main block using the weighted distribution.
     * Falls back to Blocks.STONE_BRICKS if the list is empty.
     */
    public BlockState pickMain(RandomSource rng) {
        return weightedPick(mainBlocks, rng, Blocks.STONE_BRICKS);
    }

    /**
     * Pick a cracked/ruined variant. Falls back to pickMain if no cracked
     * variants are defined.
     */
    public BlockState pickCracked(RandomSource rng) {
        if (crackedVariants.isEmpty()) return pickMain(rng);
        return weightedPick(crackedVariants, rng, Blocks.CRACKED_STONE_BRICKS);
    }

    /** Whether this palette has a valid stair block. */
    public boolean hasStairs() {
        return stairBlock != null && stairBlock != Blocks.AIR;
    }

    /** Whether this palette has a valid slab block. */
    public boolean hasSlab() {
        return slabBlock != null && slabBlock != Blocks.AIR;
    }

    /** Whether this palette has a valid wall block. */
    public boolean hasWall() {
        return wallBlock != null && wallBlock != Blocks.AIR;
    }

    public BlockState stairState(net.minecraft.core.Direction facing, Half half) {
        if (!hasStairs()) return pickMain(null);
        return stairBlock.defaultBlockState()
                .setValue(StairBlock.FACING, facing)
                .setValue(StairBlock.HALF, half);
    }

    public BlockState slabState(SlabType type) {
        if (!hasSlab()) return pickMain(null);
        return slabBlock.defaultBlockState()
                .setValue(SlabBlock.TYPE, type);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static BlockState weightedPick(List<WeightedBlock> list,
                                           RandomSource rng,
                                           Block fallback) {
        if (list.isEmpty()) return fallback.defaultBlockState();

        int total = list.stream().mapToInt(WeightedBlock::weight).sum();
        if (total <= 0) return list.get(0).block().defaultBlockState();

        int roll = rng.nextInt(total);
        int cumulative = 0;
        for (WeightedBlock wb : list) {
            cumulative += wb.weight();
            if (roll < cumulative) return wb.block().defaultBlockState();
        }
        return list.get(list.size() - 1).block().defaultBlockState();
    }

    // -------------------------------------------------------------------------
    // Built-in palettes for default styles (used in tests / fallback)
    // -------------------------------------------------------------------------

    public static MaterialPalette stoneBrick() {
        return new MaterialPalette(
                List.of(
                        new WeightedBlock(Blocks.STONE_BRICKS, 70),
                        new WeightedBlock(Blocks.MOSSY_STONE_BRICKS, 20),
                        new WeightedBlock(Blocks.CRACKED_STONE_BRICKS, 10)
                ),
                List.of(
                        new WeightedBlock(Blocks.CRACKED_STONE_BRICKS, 60),
                        new WeightedBlock(Blocks.COBBLESTONE, 40)
                ),
                Blocks.STONE_BRICK_STAIRS,
                Blocks.STONE_BRICK_SLAB,
                Blocks.STONE_BRICK_WALL
        );
    }

    public static MaterialPalette sandstone() {
        return new MaterialPalette(
                List.of(
                        new WeightedBlock(Blocks.SANDSTONE, 60),
                        new WeightedBlock(Blocks.SMOOTH_SANDSTONE, 30),
                        new WeightedBlock(Blocks.CHISELED_SANDSTONE, 10)
                ),
                List.of(
                        new WeightedBlock(Blocks.SANDSTONE, 80),
                        new WeightedBlock(Blocks.SAND, 20)
                ),
                Blocks.SANDSTONE_STAIRS,
                Blocks.SANDSTONE_SLAB,
                Blocks.SANDSTONE_WALL
        );
    }

    public static MaterialPalette deepslate() {
        return new MaterialPalette(
                List.of(
                        new WeightedBlock(Blocks.DEEPSLATE_BRICKS, 65),
                        new WeightedBlock(Blocks.DEEPSLATE_TILES, 25),
                        new WeightedBlock(Blocks.CRACKED_DEEPSLATE_BRICKS, 10)
                ),
                List.of(
                        new WeightedBlock(Blocks.CRACKED_DEEPSLATE_BRICKS, 50),
                        new WeightedBlock(Blocks.COBBLED_DEEPSLATE, 50)
                ),
                Blocks.DEEPSLATE_BRICK_STAIRS,
                Blocks.DEEPSLATE_BRICK_SLAB,
                Blocks.DEEPSLATE_BRICK_WALL
        );
    }
    public static final MaterialPalette QUARTZ = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.QUARTZ_BLOCK,        60),
                    new WeightedBlock(Blocks.SMOOTH_QUARTZ,       30),
                    new WeightedBlock(Blocks.CHISELED_QUARTZ_BLOCK,10)),
            List.of(new WeightedBlock(Blocks.CALCITE,             70),
                    new WeightedBlock(Blocks.WHITE_CONCRETE,      30)),
            Blocks.QUARTZ_STAIRS,
            Blocks.QUARTZ_SLAB,
            Blocks.QUARTZ_BLOCK  // no wall variant — use block as fallback
    );

    public static final MaterialPalette STONE = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.STONE,               60),
                    new WeightedBlock(Blocks.COBBLESTONE,         30),
                    new WeightedBlock(Blocks.MOSSY_COBBLESTONE,   10)),
            List.of(new WeightedBlock(Blocks.MOSSY_COBBLESTONE,   60),
                    new WeightedBlock(Blocks.GRAVEL,              40)),
            Blocks.STONE_STAIRS,
            Blocks.STONE_SLAB,
            Blocks.COBBLESTONE_WALL
    );

    public static final MaterialPalette BLACKSTONE = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.POLISHED_BLACKSTONE_BRICKS, 65),
                    new WeightedBlock(Blocks.BLACKSTONE,                  25),
                    new WeightedBlock(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, 10)),
            List.of(new WeightedBlock(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, 60),
                    new WeightedBlock(Blocks.BLACKSTONE,                          40)),
            Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS,
            Blocks.POLISHED_BLACKSTONE_BRICK_SLAB,
            Blocks.POLISHED_BLACKSTONE_BRICK_WALL
    );

    public static final MaterialPalette RED_SANDSTONE = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.RED_SANDSTONE,         60),
                    new WeightedBlock(Blocks.SMOOTH_RED_SANDSTONE,  30),
                    new WeightedBlock(Blocks.CHISELED_RED_SANDSTONE,10)),
            List.of(new WeightedBlock(Blocks.RED_SANDSTONE,         80),
                    new WeightedBlock(Blocks.RED_SAND,              20)),
            Blocks.RED_SANDSTONE_STAIRS,
            Blocks.RED_SANDSTONE_SLAB,
            Blocks.RED_SANDSTONE_WALL
    );

    public static final MaterialPalette BRICK = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.BRICKS,          70),
                    new WeightedBlock(Blocks.CRACKED_STONE_BRICKS, 20),
                    new WeightedBlock(Blocks.MOSSY_STONE_BRICKS,   10)),
            List.of(new WeightedBlock(Blocks.CRACKED_STONE_BRICKS, 60),
                    new WeightedBlock(Blocks.COBBLESTONE,           40)),
            Blocks.BRICK_STAIRS,
            Blocks.BRICK_SLAB,
            Blocks.BRICK_WALL
    );

    public static final MaterialPalette PRISMARINE = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.PRISMARINE_BRICKS,  60),
                    new WeightedBlock(Blocks.PRISMARINE,          30),
                    new WeightedBlock(Blocks.DARK_PRISMARINE,     10)),
            List.of(new WeightedBlock(Blocks.DARK_PRISMARINE,    60),
                    new WeightedBlock(Blocks.PRISMARINE,          40)),
            Blocks.PRISMARINE_BRICK_STAIRS,
            Blocks.PRISMARINE_SLAB,
            Blocks.PRISMARINE_WALL
    );

    public static final MaterialPalette OAK_WOOD = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.OAK_PLANKS,      60),
                    new WeightedBlock(Blocks.OAK_LOG,         30),
                    new WeightedBlock(Blocks.STRIPPED_OAK_LOG,10)),
            List.of(new WeightedBlock(Blocks.OAK_LOG,         70),
                    new WeightedBlock(Blocks.MOSSY_COBBLESTONE,30)),
            Blocks.OAK_STAIRS,
            Blocks.OAK_SLAB,
            Blocks.COBBLESTONE_WALL  // wood has no wall block
    );

    public static final MaterialPalette NETHER_BRICK = new MaterialPalette(
            List.of(new WeightedBlock(Blocks.NETHER_BRICKS,         65),
                    new WeightedBlock(Blocks.RED_NETHER_BRICKS,      25),
                    new WeightedBlock(Blocks.CRACKED_NETHER_BRICKS,  10)),
            List.of(new WeightedBlock(Blocks.CRACKED_NETHER_BRICKS,  60),
                    new WeightedBlock(Blocks.BLACKSTONE,              40)),
            Blocks.NETHER_BRICK_STAIRS,
            Blocks.NETHER_BRICK_SLAB,
            Blocks.NETHER_BRICK_WALL
    );
}
