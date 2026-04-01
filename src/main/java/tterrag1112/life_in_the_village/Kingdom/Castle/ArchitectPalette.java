package tterrag1112.life_in_the_village.Kingdom.Castle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import java.util.List;
import java.util.Map;

/**
 * TMA-style palette: a named map of block slots used by WallDesign rules.
 *
 * Every decoration rule in a WallDesign references a slot name.
 * Swapping the palette changes the material without touching the design.
 *
 * Slot conventions:
 *   fill        — primary wall block (bulk)
 *   fill_alt    — secondary fill for variation (e.g. mossy variant)
 *   trim        — accent/trim block (string courses, quoins)
 *   support     — structural element (pilaster, column shaft)
 *   support_cap — top of pilaster/column
 *   floor       — interior floor surface
 *   floor_alt   — alternate floor (e.g. carpet strip)
 *   stair       — stair block for steps, battlements, arches
 *   slab        — slab block for parapets, floor detail
 *   wall_top    — wall/fence block for parapets
 *   roof        — roof surface block
 *   roof_trim   — roof edge trim
 *   window_fill — block placed inside window frame (iron bars, glass)
 *   cracked     — weathered fill used by ruination
 */
public record ArchitectPalette(
        Map<String, WeightedBlockList> paletteSlots
) {

    // ---- Weighted block list ------------------------------------------------

    public record WeightedBlockList(List<MaterialPalette.WeightedBlock> blocks) {

        public static final Codec<WeightedBlockList> CODEC =
                MaterialPalette.WeightedBlock.CODEC.listOf()
                        .xmap(WeightedBlockList::new, WeightedBlockList::blocks);

        /** Pick a block from this list using weighted random selection. */
        public BlockState pick(RandomSource rng) {
            if (blocks.isEmpty()) return Blocks.STONE_BRICKS.defaultBlockState();
            int total = blocks.stream().mapToInt(MaterialPalette.WeightedBlock::weight).sum();
            if (total <= 0) return blocks.get(0).block().defaultBlockState();
            int roll = rng.nextInt(total);
            int cum  = 0;
            for (MaterialPalette.WeightedBlock wb : blocks) {
                cum += wb.weight();
                if (roll < cum) return wb.block().defaultBlockState();
            }
            return blocks.get(blocks.size() - 1).block().defaultBlockState();
        }

        /** Pick the first (highest-weight) block without randomness. */
        public BlockState pickFirst() {
            return blocks.isEmpty()
                    ? Blocks.STONE_BRICKS.defaultBlockState()
                    : blocks.get(0).block().defaultBlockState();
        }

        /** Get the raw block for property access (stairs, slabs, etc.). */
        public Block primaryBlock() {
            return blocks.isEmpty() ? Blocks.STONE_BRICKS : blocks.get(0).block();
        }
    }

    // ---- Codec -------------------------------------------------------------

    public static final Codec<ArchitectPalette> CODEC =
            Codec.unboundedMap(Codec.STRING, WeightedBlockList.CODEC)
                    .xmap(ArchitectPalette::new, ArchitectPalette::paletteSlots);

    // ---- Slot access -------------------------------------------------------

    /** Pick a block from the named slot. Returns stone bricks if slot missing. */
    public BlockState pick(String slot, RandomSource rng) {
        WeightedBlockList list = paletteSlots.get(slot);
        return list != null ? list.pick(rng) : Blocks.STONE_BRICKS.defaultBlockState();
    }

    /** Get primary block of a slot without randomness (for property access). */
    public Block block(String slot) {
        WeightedBlockList list = paletteSlots.get(slot);
        return list != null ? list.primaryBlock() : Blocks.STONE_BRICKS;
    }

    /** True if this palette defines the given slot. */
    public boolean has(String slot) {
        return paletteSlots.containsKey(slot) && !paletteSlots.get(slot).blocks().isEmpty();
    }

    // ---- Convenience state builders ----------------------------------------

    public BlockState stair(String slot, net.minecraft.core.Direction facing,
                            Half half, RandomSource rng) {
        Block b = block(slot);
        if (!(b instanceof net.minecraft.world.level.block.StairBlock)) {
            return pick(slot, rng);
        }
        return b.defaultBlockState()
                .setValue(net.minecraft.world.level.block.StairBlock.FACING, facing)
                .setValue(net.minecraft.world.level.block.StairBlock.HALF, half)
                .setValue(net.minecraft.world.level.block.StairBlock.SHAPE,
                        StairsShape.STRAIGHT);
    }

    public BlockState slab(String slot, SlabType type) {
        Block b = block(slot);
        if (!b.defaultBlockState().hasProperty(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)) {
            return b.defaultBlockState();
        }
        return b.defaultBlockState().setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE,
                type);
    }

    // ---- Built-in palettes -------------------------------------------------

    public static ArchitectPalette stoneBrick() {
        return of(slots(
                "fill",        wbl(wb("minecraft:stone_bricks", 70),
                        wb("minecraft:mossy_stone_bricks", 20),
                        wb("minecraft:cracked_stone_bricks", 10)),
                "fill_alt",    wbl(wb("minecraft:mossy_stone_bricks", 60),
                        wb("minecraft:stone_bricks", 40)),
                "trim",        wbl(wb("minecraft:chiseled_stone_bricks", 100)),
                "support",     wbl(wb("minecraft:stone_brick_wall", 100)),
                "support_cap", wbl(wb("minecraft:stone_bricks", 100)),
                "floor",       wbl(wb("minecraft:stone_bricks", 60),
                        wb("minecraft:cobblestone", 40)),
                "floor_alt",   wbl(wb("minecraft:gravel", 100)),
                "stair",       wbl(wb("minecraft:stone_brick_stairs", 100)),
                "slab",        wbl(wb("minecraft:stone_brick_slab", 100)),
                "wall_top",    wbl(wb("minecraft:stone_brick_wall", 100)),
                "roof",        wbl(wb("minecraft:stone_bricks", 100)),
                "roof_trim",   wbl(wb("minecraft:stone_brick_stairs", 100)),
                "window_fill", wbl(wb("minecraft:iron_bars", 100)),
                "cracked",     wbl(wb("minecraft:cracked_stone_bricks", 60),
                        wb("minecraft:cobblestone", 40))
        ));
    }

    public static ArchitectPalette sandstone() {
        return of(slots(
                "fill",        wbl(wb("minecraft:sandstone", 60),
                        wb("minecraft:smooth_sandstone", 30),
                        wb("minecraft:chiseled_sandstone", 10)),
                "fill_alt",    wbl(wb("minecraft:smooth_sandstone", 100)),
                "trim",        wbl(wb("minecraft:chiseled_sandstone", 100)),
                "support",     wbl(wb("minecraft:sandstone_wall", 100)),
                "support_cap", wbl(wb("minecraft:chiseled_sandstone", 100)),
                "floor",       wbl(wb("minecraft:sandstone", 70),
                        wb("minecraft:smooth_sandstone", 30)),
                "floor_alt",   wbl(wb("minecraft:sand", 100)),
                "stair",       wbl(wb("minecraft:sandstone_stairs", 100)),
                "slab",        wbl(wb("minecraft:sandstone_slab", 100)),
                "wall_top",    wbl(wb("minecraft:sandstone_wall", 100)),
                "roof",        wbl(wb("minecraft:smooth_sandstone", 100)),
                "roof_trim",   wbl(wb("minecraft:sandstone_stairs", 100)),
                "window_fill", wbl(wb("minecraft:glass_pane", 100)),
                "cracked",     wbl(wb("minecraft:sandstone", 80),
                        wb("minecraft:sand", 20))
        ));
    }

    public static ArchitectPalette deepslate() {
        return of(slots(
                "fill",        wbl(wb("minecraft:deepslate_bricks", 65),
                        wb("minecraft:deepslate_tiles", 25),
                        wb("minecraft:cracked_deepslate_bricks", 10)),
                "fill_alt",    wbl(wb("minecraft:deepslate_tiles", 100)),
                "trim",        wbl(wb("minecraft:chiseled_deepslate", 100)),
                "support",     wbl(wb("minecraft:deepslate_brick_wall", 100)),
                "support_cap", wbl(wb("minecraft:chiseled_deepslate", 100)),
                "floor",       wbl(wb("minecraft:deepslate_bricks", 60),
                        wb("minecraft:cobbled_deepslate", 40)),
                "floor_alt",   wbl(wb("minecraft:cobbled_deepslate", 100)),
                "stair",       wbl(wb("minecraft:deepslate_brick_stairs", 100)),
                "slab",        wbl(wb("minecraft:deepslate_brick_slab", 100)),
                "wall_top",    wbl(wb("minecraft:deepslate_brick_wall", 100)),
                "roof",        wbl(wb("minecraft:deepslate_tiles", 100)),
                "roof_trim",   wbl(wb("minecraft:deepslate_brick_stairs", 100)),
                "window_fill", wbl(wb("minecraft:iron_bars", 100)),
                "cracked",     wbl(wb("minecraft:cracked_deepslate_bricks", 60),
                        wb("minecraft:cobbled_deepslate", 40))
        ));
    }

    public static ArchitectPalette blackstone() {
        return of(slots(
                "fill",        wbl(wb("minecraft:polished_blackstone_bricks", 65),
                        wb("minecraft:blackstone", 25),
                        wb("minecraft:cracked_polished_blackstone_bricks", 10)),
                "fill_alt",    wbl(wb("minecraft:blackstone", 100)),
                "trim",        wbl(wb("minecraft:gilded_blackstone", 100)),
                "support",     wbl(wb("minecraft:polished_blackstone_brick_wall", 100)),
                "support_cap", wbl(wb("minecraft:gilded_blackstone", 100)),
                "floor",       wbl(wb("minecraft:polished_blackstone", 60),
                        wb("minecraft:blackstone", 40)),
                "floor_alt",   wbl(wb("minecraft:soul_sand", 100)),
                "stair",       wbl(wb("minecraft:polished_blackstone_brick_stairs", 100)),
                "slab",        wbl(wb("minecraft:polished_blackstone_brick_slab", 100)),
                "wall_top",    wbl(wb("minecraft:polished_blackstone_brick_wall", 100)),
                "roof",        wbl(wb("minecraft:blackstone", 100)),
                "roof_trim",   wbl(wb("minecraft:polished_blackstone_stairs", 100)),
                "window_fill", wbl(wb("minecraft:soul_fire_lantern", 50),
                        wb("minecraft:iron_bars", 50)),
                "cracked",     wbl(wb("minecraft:cracked_polished_blackstone_bricks", 60),
                        wb("minecraft:blackstone", 40))
        ));
    }

    public static ArchitectPalette quartz() {
        return of(slots(
                "fill",        wbl(wb("minecraft:quartz_block", 60),
                        wb("minecraft:smooth_quartz", 30),
                        wb("minecraft:chiseled_quartz_block", 10)),
                "fill_alt",    wbl(wb("minecraft:smooth_quartz", 100)),
                "trim",        wbl(wb("minecraft:chiseled_quartz_block", 100)),
                "support",     wbl(wb("minecraft:quartz_pillar", 100)),
                "support_cap", wbl(wb("minecraft:chiseled_quartz_block", 100)),
                "floor",       wbl(wb("minecraft:quartz_block", 70),
                        wb("minecraft:smooth_quartz", 30)),
                "floor_alt",   wbl(wb("minecraft:calcite", 100)),
                "stair",       wbl(wb("minecraft:quartz_stairs", 100)),
                "slab",        wbl(wb("minecraft:quartz_slab", 100)),
                "wall_top",    wbl(wb("minecraft:quartz_block", 100)),
                "roof",        wbl(wb("minecraft:smooth_quartz", 100)),
                "roof_trim",   wbl(wb("minecraft:quartz_stairs", 100)),
                "window_fill", wbl(wb("minecraft:glass_pane", 100)),
                "cracked",     wbl(wb("minecraft:calcite", 70),
                        wb("minecraft:white_concrete", 30))
        ));
    }

    public static ArchitectPalette redSandstone() {
        return of(slots(
                "fill",        wbl(wb("minecraft:red_sandstone", 60),
                        wb("minecraft:smooth_red_sandstone", 30),
                        wb("minecraft:chiseled_red_sandstone", 10)),
                "fill_alt",    wbl(wb("minecraft:smooth_red_sandstone", 100)),
                "trim",        wbl(wb("minecraft:chiseled_red_sandstone", 100)),
                "support",     wbl(wb("minecraft:red_sandstone_wall", 100)),
                "support_cap", wbl(wb("minecraft:chiseled_red_sandstone", 100)),
                "floor",       wbl(wb("minecraft:red_sandstone", 70),
                        wb("minecraft:smooth_red_sandstone", 30)),
                "floor_alt",   wbl(wb("minecraft:red_sand", 100)),
                "stair",       wbl(wb("minecraft:red_sandstone_stairs", 100)),
                "slab",        wbl(wb("minecraft:red_sandstone_slab", 100)),
                "wall_top",    wbl(wb("minecraft:red_sandstone_wall", 100)),
                "roof",        wbl(wb("minecraft:smooth_red_sandstone", 100)),
                "roof_trim",   wbl(wb("minecraft:red_sandstone_stairs", 100)),
                "window_fill", wbl(wb("minecraft:glass_pane", 100)),
                "cracked",     wbl(wb("minecraft:red_sandstone", 80),
                        wb("minecraft:red_sand", 20))
        ));
    }

    public static ArchitectPalette oakWood() {
        return of(slots(
                "fill",        wbl(wb("minecraft:oak_planks", 60),
                        wb("minecraft:oak_log", 30),
                        wb("minecraft:stripped_oak_log", 10)),
                "fill_alt",    wbl(wb("minecraft:oak_log", 100)),
                "trim",        wbl(wb("minecraft:stripped_oak_log", 100)),
                "support",     wbl(wb("minecraft:oak_fence", 100)),
                "support_cap", wbl(wb("minecraft:oak_log", 100)),
                "floor",       wbl(wb("minecraft:oak_planks", 80),
                        wb("minecraft:stripped_oak_log", 20)),
                "floor_alt",   wbl(wb("minecraft:moss_block", 100)),
                "stair",       wbl(wb("minecraft:oak_stairs", 100)),
                "slab",        wbl(wb("minecraft:oak_slab", 100)),
                "wall_top",    wbl(wb("minecraft:oak_fence", 100)),
                "roof",        wbl(wb("minecraft:oak_planks", 100)),
                "roof_trim",   wbl(wb("minecraft:oak_stairs", 100)),
                "window_fill", wbl(wb("minecraft:glass_pane", 100)),
                "cracked",     wbl(wb("minecraft:mossy_cobblestone", 60),
                        wb("minecraft:cobblestone", 40))
        ));
    }

    public static ArchitectPalette prismarine() {
        return of(slots(
                "fill",        wbl(wb("minecraft:prismarine_bricks", 60),
                        wb("minecraft:prismarine", 30),
                        wb("minecraft:dark_prismarine", 10)),
                "fill_alt",    wbl(wb("minecraft:dark_prismarine", 100)),
                "trim",        wbl(wb("minecraft:sea_lantern", 100)),
                "support",     wbl(wb("minecraft:prismarine_wall", 100)),
                "support_cap", wbl(wb("minecraft:sea_lantern", 100)),
                "floor",       wbl(wb("minecraft:prismarine_bricks", 70),
                        wb("minecraft:dark_prismarine", 30)),
                "floor_alt",   wbl(wb("minecraft:dark_prismarine", 100)),
                "stair",       wbl(wb("minecraft:prismarine_brick_stairs", 100)),
                "slab",        wbl(wb("minecraft:prismarine_slab", 100)),
                "wall_top",    wbl(wb("minecraft:prismarine_wall", 100)),
                "roof",        wbl(wb("minecraft:dark_prismarine", 100)),
                "roof_trim",   wbl(wb("minecraft:prismarine_stairs", 100)),
                "window_fill", wbl(wb("minecraft:glass_pane", 100)),
                "cracked",     wbl(wb("minecraft:dark_prismarine", 80),
                        wb("minecraft:prismarine", 20))
        ));
    }
    /** Formal palace — smooth stone, polished variants, glass windows. */
    public static ArchitectPalette formalPalace() {
        return of(slots(
                "fill",        wbl(wb("minecraft:smooth_stone",          60),
                        wb("minecraft:stone_bricks",          30),
                        wb("minecraft:chiseled_stone_bricks", 10)),
                "fill_alt",    wbl(wb("minecraft:stone_bricks",          100)),
                "trim",        wbl(wb("minecraft:chiseled_stone_bricks", 100)),
                "support",     wbl(wb("minecraft:quartz_pillar",          100)),
                "support_cap", wbl(wb("minecraft:chiseled_stone_bricks", 100)),
                "floor",       wbl(wb("minecraft:smooth_stone",           70),
                        wb("minecraft:polished_andesite",      30)),
                "floor_alt",   wbl(wb("minecraft:polished_andesite",     100)),
                "stair",       wbl(wb("minecraft:stone_brick_stairs",    100)),
                "slab",        wbl(wb("minecraft:stone_brick_slab",      100)),
                "wall_top",    wbl(wb("minecraft:stone_brick_wall",      100)),
                "roof",        wbl(wb("minecraft:smooth_stone",           80),
                        wb("minecraft:stone_bricks",           20)),
                "roof_trim",   wbl(wb("minecraft:stone_brick_stairs",    100)),
                "window_fill", wbl(wb("minecraft:glass_pane",            100)),
                "cracked",     wbl(wb("minecraft:cracked_stone_bricks",   60),
                        wb("minecraft:cobblestone",             40))
        ));
    }

    /** Grand palace — polished stone + white concrete for a bright regal look. */
    public static ArchitectPalette grandPalace() {
        return of(slots(
                "fill",        wbl(wb("minecraft:polished_andesite",     50),
                        wb("minecraft:smooth_stone",           40),
                        wb("minecraft:white_concrete",         10)),
                "fill_alt",    wbl(wb("minecraft:white_concrete",        100)),
                "trim",        wbl(wb("minecraft:quartz_block",          100)),
                "support",     wbl(wb("minecraft:quartz_pillar",         100)),
                "support_cap", wbl(wb("minecraft:chiseled_quartz_block", 100)),
                "floor",       wbl(wb("minecraft:polished_andesite",      60),
                        wb("minecraft:smooth_stone",           40)),
                "floor_alt",   wbl(wb("minecraft:white_carpet",          100)),
                "stair",       wbl(wb("minecraft:stone_brick_stairs",    100)),
                "slab",        wbl(wb("minecraft:quartz_slab",           100)),
                "wall_top",    wbl(wb("minecraft:quartz_block",          100)),
                "roof",        wbl(wb("minecraft:white_concrete",         60),
                        wb("minecraft:quartz_block",           40)),
                "roof_trim",   wbl(wb("minecraft:quartz_stairs",         100)),
                "window_fill", wbl(wb("minecraft:glass_pane",            100)),
                "cracked",     wbl(wb("minecraft:calcite",                70),
                        wb("minecraft:white_concrete",          30))
        ));
    }

    /** Rustic manor — warm stone + terracotta accents. */
    public static ArchitectPalette rusticManor() {
        return of(slots(
                "fill",        wbl(wb("minecraft:stone_bricks",          55),
                        wb("minecraft:cobblestone",            30),
                        wb("minecraft:mossy_stone_bricks",     15)),
                "fill_alt",    wbl(wb("minecraft:mossy_stone_bricks",    100)),
                "trim",        wbl(wb("minecraft:terracotta",            100)),
                "support",     wbl(wb("minecraft:oak_log",               100)),
                "support_cap", wbl(wb("minecraft:terracotta",            100)),
                "floor",       wbl(wb("minecraft:stone_bricks",           60),
                        wb("minecraft:cobblestone",             40)),
                "floor_alt",   wbl(wb("minecraft:oak_planks",            100)),
                "stair",       wbl(wb("minecraft:stone_brick_stairs",    100)),
                "slab",        wbl(wb("minecraft:stone_brick_slab",      100)),
                "wall_top",    wbl(wb("minecraft:cobblestone_wall",       100)),
                "roof",        wbl(wb("minecraft:terracotta",             80),
                        wb("minecraft:stone_bricks",           20)),
                "roof_trim",   wbl(wb("minecraft:stone_brick_stairs",    100)),
                "window_fill", wbl(wb("minecraft:glass_pane",            100)),
                "cracked",     wbl(wb("minecraft:mossy_cobblestone",      60),
                        wb("minecraft:gravel",                  40))
        ));
    }

    // ---- Factory helpers ---------------------------------------------------

    private static ArchitectPalette of(Map<String, WeightedBlockList> slots) {
        return new ArchitectPalette(slots);
    }

    private static WeightedBlockList wbl(MaterialPalette.WeightedBlock... blocks) {
        return new WeightedBlockList(List.of(blocks));
    }

    private static MaterialPalette.WeightedBlock wb(String id, int weight) {
        Block block = BuiltInRegistries.BLOCK
                .getOptional(Identifier.parse(id))
                .orElse(Blocks.STONE_BRICKS.builtInRegistryHolder().value());
        return new MaterialPalette.WeightedBlock(block, weight);
    }
    private static Map<String, WeightedBlockList> slots(Object... pairs) {
        Map<String, WeightedBlockList> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (WeightedBlockList) pairs[i + 1]);
        }
        return map;
    }
}