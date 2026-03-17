package tterrag1112.life_in_the_village.Village.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

public enum VillageBiomeStyle {

    PLAINS(
            Blocks.OAK_PLANKS,
            Blocks.OAK_LOG,
            Blocks.OAK_FENCE,
            Blocks.OAK_FENCE_GATE,
            Blocks.OAK_STAIRS,
            Blocks.OAK_SLAB,
            Blocks.COBBLESTONE,
            Blocks.COBBLESTONE_WALL,
            Blocks.COBBLESTONE_STAIRS,
            Blocks.COBBLESTONE_SLAB,
            Blocks.DIRT_PATH,
            Blocks.LANTERN,
            Blocks.POPPY
    ),
    DESERT(
            Blocks.SANDSTONE,
            Blocks.SMOOTH_SANDSTONE,
            Blocks.OAK_FENCE,        // no desert fence, oak works
            Blocks.OAK_FENCE_GATE,
            Blocks.SANDSTONE_STAIRS,
            Blocks.SANDSTONE_SLAB,
            Blocks.SMOOTH_SANDSTONE,
            Blocks.SANDSTONE_WALL,
            Blocks.SMOOTH_SANDSTONE_STAIRS,
            Blocks.SMOOTH_SANDSTONE_SLAB,
            Blocks.SAND,              // paths are sand
            Blocks.LANTERN,
            Blocks.DEAD_BUSH
    ),
    TAIGA(
            Blocks.SPRUCE_PLANKS,
            Blocks.SPRUCE_LOG,
            Blocks.SPRUCE_FENCE,
            Blocks.SPRUCE_FENCE_GATE,
            Blocks.SPRUCE_STAIRS,
            Blocks.SPRUCE_SLAB,
            Blocks.STONE_BRICKS,
            Blocks.STONE_BRICK_WALL,
            Blocks.STONE_BRICK_STAIRS,
            Blocks.STONE_BRICK_SLAB,
            Blocks.DIRT_PATH,
            Blocks.SOUL_LANTERN,      // eerie taiga feel
            Blocks.FERN
    ),
    JUNGLE(
            Blocks.JUNGLE_PLANKS,
            Blocks.JUNGLE_LOG,
            Blocks.JUNGLE_FENCE,
            Blocks.JUNGLE_FENCE_GATE,
            Blocks.JUNGLE_STAIRS,
            Blocks.JUNGLE_SLAB,
            Blocks.MOSSY_COBBLESTONE,
            Blocks.MOSSY_COBBLESTONE_WALL,
            Blocks.MOSSY_COBBLESTONE_STAIRS,  // Note: may need tag check
            Blocks.MOSSY_COBBLESTONE_SLAB,    // Note: may need tag check
            Blocks.DIRT_PATH,
            Blocks.LANTERN,
            Blocks.OXEYE_DAISY
    ),
    SAVANNA(
            Blocks.ACACIA_PLANKS,
            Blocks.ACACIA_LOG,
            Blocks.ACACIA_FENCE,
            Blocks.ACACIA_FENCE_GATE,
            Blocks.ACACIA_STAIRS,
            Blocks.ACACIA_SLAB,
            Blocks.SMOOTH_STONE,
            Blocks.COBBLESTONE_WALL,
            Blocks.SMOOTH_STONE_SLAB,    // smooth stone has no stairs
            Blocks.SMOOTH_STONE_SLAB,
            Blocks.COARSE_DIRT,           // paths are coarse dirt
            Blocks.LANTERN,
            Blocks.DANDELION
    ),
    SNOWY(
            Blocks.SPRUCE_PLANKS,
            Blocks.SPRUCE_LOG,
            Blocks.SPRUCE_FENCE,
            Blocks.SPRUCE_FENCE_GATE,
            Blocks.SPRUCE_STAIRS,
            Blocks.SPRUCE_SLAB,
            Blocks.PACKED_ICE,
            Blocks.STONE_BRICK_WALL,
            Blocks.STONE_BRICK_STAIRS,
            Blocks.STONE_BRICK_SLAB,
            Blocks.DIRT_PATH,
            Blocks.LANTERN,
            Blocks.AZURE_BLUET
    ),
    SWAMP(
            Blocks.DARK_OAK_PLANKS,
            Blocks.DARK_OAK_LOG,
            Blocks.DARK_OAK_FENCE,
            Blocks.DARK_OAK_FENCE_GATE,
            Blocks.DARK_OAK_STAIRS,
            Blocks.DARK_OAK_SLAB,
            Blocks.MOSSY_COBBLESTONE,
            Blocks.MOSSY_COBBLESTONE_WALL,
            Blocks.MOSSY_STONE_BRICK_STAIRS,
            Blocks.MOSSY_STONE_BRICK_SLAB,
            Blocks.MUD,                   // paths are mud
            Blocks.SOUL_LANTERN,
            Blocks.BLUE_ORCHID
    );

    // -------------------------------------------------------------------------
    // Style components
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
    public final Block pathBlock;
    public final Block lantern;
    public final Block flower;

    VillageBiomeStyle(Block planks, Block log, Block fence,
                      Block fenceGate, Block woodStairs, Block woodSlab,
                      Block stone, Block stoneWall, Block stoneStairs,
                      Block stoneSlab, Block pathBlock, Block lantern,
                      Block flower) {
        this.planks      = planks;
        this.log         = log;
        this.fence       = fence;
        this.fenceGate   = fenceGate;
        this.woodStairs  = woodStairs;
        this.woodSlab    = woodSlab;
        this.stone       = stone;
        this.stoneWall   = stoneWall;
        this.stoneStairs = stoneStairs;
        this.stoneSlab   = stoneSlab;
        this.pathBlock   = pathBlock;
        this.lantern     = lantern;
        this.flower      = flower;
    }

    // -------------------------------------------------------------------------
    // Convenience block state builders
    // -------------------------------------------------------------------------

    public BlockState pathState() {
        return pathBlock.defaultBlockState();
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
    // Biome detection
    // -------------------------------------------------------------------------

    /**
     * Detects the biome style at a given position in the world.
     * Falls back to PLAINS if the biome is not recognized.
     */
    public static VillageBiomeStyle detect(ServerLevel level,
                                           BlockPos pos) {
        var biomeHolder = level.getBiome(pos);
        String biomeId  = biomeHolder.unwrapKey()
                .map(k -> k.registry().toString())
                .orElse("minecraft:plains");

        return fromBiomeId(biomeId);
    }

    public static VillageBiomeStyle fromBiomeId(String biomeId) {
        // Desert
        if (biomeId.contains("desert")) return DESERT;

        // Snowy
        if (biomeId.contains("snowy") || biomeId.contains("frozen")
                || biomeId.contains("ice")) return SNOWY;

        // Taiga
        if (biomeId.contains("taiga") || biomeId.contains("spruce"))
            return TAIGA;

        // Jungle
        if (biomeId.contains("jungle") || biomeId.contains("bamboo"))
            return JUNGLE;

        // Savanna
        if (biomeId.contains("savanna") || biomeId.contains("badlands")
                || biomeId.contains("mesa")) return SAVANNA;

        // Swamp
        if (biomeId.contains("swamp") || biomeId.contains("mangrove"))
            return SWAMP;

        // Default
        return PLAINS;
    }
}