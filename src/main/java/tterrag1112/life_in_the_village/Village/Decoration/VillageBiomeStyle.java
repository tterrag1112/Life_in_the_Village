// src/main/java/tterrag1112/life_in_the_village/Village/Decoration/VillageBiomeStyle.java
package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.StreetTier;

public enum VillageBiomeStyle {

    PLAINS(
            Blocks.OAK_PLANKS, Blocks.OAK_LOG,
            Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE,
            Blocks.OAK_STAIRS, Blocks.OAK_SLAB,
            Blocks.COBBLESTONE, Blocks.COBBLESTONE_WALL,
            Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_SLAB,
            Blocks.DIRT_PATH, Blocks.GRAVEL,
            Blocks.COBBLESTONE, Blocks.COBBLESTONE_SLAB,
            Blocks.LANTERN, Blocks.POPPY
    ),
    DESERT(
            Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE,
            Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE,
            Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB,
            Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE_WALL,
            Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB,
            Blocks.SAND, Blocks.SAND,
            Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE_SLAB,
            Blocks.LANTERN, Blocks.DEAD_BUSH
    ),
    TAIGA(
            Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG,
            Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE,
            Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB,
            Blocks.STONE_BRICKS, Blocks.STONE_BRICK_WALL,
            Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
            Blocks.DIRT_PATH, Blocks.GRAVEL,
            Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB,
            Blocks.SOUL_LANTERN, Blocks.FERN
    ),
    JUNGLE(
            Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_LOG,
            Blocks.JUNGLE_FENCE, Blocks.JUNGLE_FENCE_GATE,
            Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_SLAB,
            Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_WALL,
            Blocks.MOSSY_COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_SLAB,
            Blocks.DIRT_PATH, Blocks.GRAVEL,
            Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_SLAB,
            Blocks.LANTERN, Blocks.OXEYE_DAISY
    ),
    SAVANNA(
            Blocks.ACACIA_PLANKS, Blocks.ACACIA_LOG,
            Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE,
            Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB,
            Blocks.SMOOTH_STONE, Blocks.COBBLESTONE_WALL,
            Blocks.SMOOTH_STONE_SLAB, Blocks.SMOOTH_STONE_SLAB,
            Blocks.COARSE_DIRT, Blocks.GRAVEL,
            Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB,
            Blocks.LANTERN, Blocks.DANDELION
    ),
    SNOWY(
            Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG,
            Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE,
            Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB,
            Blocks.PACKED_ICE, Blocks.STONE_BRICK_WALL,
            Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB,
            Blocks.DIRT_PATH, Blocks.GRAVEL,
            Blocks.PACKED_ICE, Blocks.STONE_BRICK_SLAB,
            Blocks.LANTERN, Blocks.AZURE_BLUET
    ),
    SWAMP(
            Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_LOG,
            Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_FENCE_GATE,
            Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB,
            Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_WALL,
            Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_SLAB,
            Blocks.MUD, Blocks.GRAVEL,
            Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE_SLAB,
            Blocks.SOUL_LANTERN, Blocks.BLUE_ORCHID
    );

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    public final Block planks;
    public final Block log;
    public final Block fence;
    public final Block fenceGate;
    public final Block woodStairs;
    public final Block woodSlab;
    public final Block stone;
    public final Block stoneWall;
    public final Block stoneStairs;
    public final Block stoneSlab;
    /** Single-block path surface (tertiary streets). */
    public final Block pathBlock;
    /** Secondary street surface (gravel, sand, coarse dirt). */
    public final Block secondaryStreetBlock;
    /** Primary street surface (cobblestone, smooth sandstone, etc.). */
    public final Block primaryStreetBlock;
    /** Primary street step/kerb (slab form of primaryStreetBlock). */
    public final Block primaryStreetSlab;
    public final Block lantern;
    public final Block flower;

    VillageBiomeStyle(Block planks, Block log,
                      Block fence, Block fenceGate,
                      Block woodStairs, Block woodSlab,
                      Block stone, Block stoneWall,
                      Block stoneStairs, Block stoneSlab,
                      Block pathBlock, Block secondaryStreetBlock,
                      Block primaryStreetBlock, Block primaryStreetSlab,
                      Block lantern, Block flower) {
        this.planks               = planks;
        this.log                  = log;
        this.fence                = fence;
        this.fenceGate            = fenceGate;
        this.woodStairs           = woodStairs;
        this.woodSlab             = woodSlab;
        this.stone                = stone;
        this.stoneWall            = stoneWall;
        this.stoneStairs          = stoneStairs;
        this.stoneSlab            = stoneSlab;
        this.pathBlock            = pathBlock;
        this.secondaryStreetBlock = secondaryStreetBlock;
        this.primaryStreetBlock   = primaryStreetBlock;
        this.primaryStreetSlab    = primaryStreetSlab;
        this.lantern              = lantern;
        this.flower               = flower;
    }

    // -------------------------------------------------------------------------
    // BlockState builders
    // -------------------------------------------------------------------------

    /** Tertiary (alley) street surface. */
    public BlockState pathState() {
        return pathBlock.defaultBlockState();
    }

    /** Secondary street surface (gravel, sand, coarse dirt). */
    public BlockState secondaryStreetState() {
        return secondaryStreetBlock.defaultBlockState();
    }

    /** Primary street surface (cobblestone, smooth sandstone, etc.). */
    public BlockState primaryStreetState() {
        return primaryStreetBlock.defaultBlockState();
    }

    /**
     * Returns the correct street surface for a given {@link StreetTier}.
     * Centralises all tier→block logic so callers don't need a switch.
     */
    public BlockState streetStateFor(StreetTier tier) {
        return switch (tier) {
            case PRIMARY   -> primaryStreetState();
            case SECONDARY -> secondaryStreetState();
            case TERTIARY  -> pathState();
        };
    }

    /**
     * Returns the slab form of the primary street block for kerb edges
     * and slab transitions on primary streets.
     */
    public BlockState primaryStreetSlabState() {
        if (primaryStreetSlab instanceof SlabBlock) {
            return primaryStreetSlab.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        return primaryStreetSlab.defaultBlockState();
    }

    public BlockState lanternState() {
        return lantern.defaultBlockState();
    }

    public BlockState fenceState() {
        return fence.defaultBlockState();
    }

    public BlockState fenceGateState() {
        return fenceGate.defaultBlockState();
    }

    public BlockState stoneWallState() {
        return stoneWall.defaultBlockState();
    }

    public BlockState stoneSlab() {
        if (stoneSlab instanceof SlabBlock) {
            return stoneSlab.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        return stoneSlab.defaultBlockState();
    }

    public BlockState woodSlab() {
        if (woodSlab instanceof SlabBlock) {
            return woodSlab.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        return woodSlab.defaultBlockState();
    }

    public BlockState flowerState() {
        return flower.defaultBlockState();
    }

    // -------------------------------------------------------------------------
    // Biome detection  — FIXED: was using k.registry() (registry name)
    //                           now uses k.location() (biome identifier)
    // -------------------------------------------------------------------------

    public static VillageBiomeStyle detect(ServerLevel level, BlockPos pos) {
        var biomeHolder = level.getBiome(pos);
        String biomeId  = biomeHolder.unwrapKey()
                .map(k -> k.registry().toString())   // ← was k.registry()
                .orElse("minecraft:plains");
        return fromBiomeId(biomeId);
    }

    public static VillageBiomeStyle fromBiomeId(String biomeId) {
        if (biomeId.contains("desert"))                              return DESERT;
        if (biomeId.contains("snowy") || biomeId.contains("frozen")
                || biomeId.contains("ice"))                         return SNOWY;
        if (biomeId.contains("taiga") || biomeId.contains("spruce")) return TAIGA;
        if (biomeId.contains("jungle") || biomeId.contains("bamboo")) return JUNGLE;
        if (biomeId.contains("savanna") || biomeId.contains("badlands")
                || biomeId.contains("mesa"))                        return SAVANNA;
        if (biomeId.contains("swamp") || biomeId.contains("mangrove")) return SWAMP;
        return PLAINS;
    }
}