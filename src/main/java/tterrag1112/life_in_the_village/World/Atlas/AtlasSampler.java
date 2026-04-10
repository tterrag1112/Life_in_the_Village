package tterrag1112.life_in_the_village.World.Atlas;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Pure functions that query a {@link ChunkGenerator} for surface height
 * and biome at arbitrary world coordinates without loading any chunks.
 *
 * <h3>How it works</h3>
 * NeoForge exposes two APIs that operate purely on noise functions:
 * <ul>
 *   <li>{@link ChunkGenerator#getBaseHeight} — predicts the surface Y
 *       value the chunk would generate if it were loaded. Uses the
 *       same noise routers vanilla structure placement uses.</li>
 *   <li>{@link BiomeSource#getNoiseBiome} — returns the biome holder
 *       at quart resolution (one sample per 4 blocks).</li>
 * </ul>
 * Both are stateless with respect to the world — they only read the
 * server level's chunk source for the {@link RandomState} (which
 * carries the seed and noise router config). They are safe to call
 * from any thread that holds a reference to a stable RandomState,
 * and very fast (microseconds per call).
 *
 * <h3>Caveats</h3>
 * The returned height is the surface BEFORE features are placed —
 * trees, ores, structures, and player edits are not reflected. For
 * site selection this is exactly what we want: we need to know if
 * the underlying terrain is flat enough, what biome it is, and
 * whether it's water. Features can be dealt with at realisation time.
 */
public final class AtlasSampler {

    private AtlasSampler() {}

    // =========================================================================
    // Single-point queries
    // =========================================================================

    /** Returns the predicted surface Y at the given world XZ. */
    public static int sampleHeight(ServerLevel level, int blockX, int blockZ) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        RandomState rs = level.getChunkSource().randomState();
        return gen.getBaseHeight(blockX, blockZ,
                Heightmap.Types.WORLD_SURFACE_WG, level, rs);
    }

    /**
     * Returns the biome holder at the given world position.
     * The Y coordinate matters — different biomes can stack vertically
     * in some worldgen configurations (e.g. cave biomes vs surface).
     */
    public static Holder<Biome> sampleBiome(ServerLevel level,
                                            int blockX, int blockY, int blockZ) {
        BiomeSource src = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        // Biomes are stored at quart (4-block) resolution
        return src.getNoiseBiome(blockX >> 2, blockY >> 2, blockZ >> 2, sampler);
    }

    // =========================================================================
    // Cell sampling — the main entry point used by WorldAtlas
    // =========================================================================

    /**
     * Samples a single atlas cell. Uses 5 sample points: the cell centre
     * and the four corners. The centre determines biome and centerY; the
     * five points together determine minY, maxY, and the steep flag.
     *
     * <p>Adjacency flags (coast, river-adjacent, freshwater) are NOT set
     * here because they require knowledge of neighbour cells — those are
     * filled in by {@link WorldAtlas#computeAdjacencyFlags} after the
     * neighbours have themselves been sampled.
     */
    public static AtlasCell sampleCell(ServerLevel level, int cellX, int cellZ) {
        int cxBlock = (cellX << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        int czBlock = (cellZ << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
        int minBX   =  cellX << AtlasCell.CELL_SHIFT;
        int minBZ   =  cellZ << AtlasCell.CELL_SHIFT;
        int maxBX   = minBX + AtlasCell.CELL_SIZE - 1;
        int maxBZ   = minBZ + AtlasCell.CELL_SIZE - 1;

        // ── Sample heights ───────────────────────────────────────────────────
        int yC  = sampleHeight(level, cxBlock, czBlock);
        int yNW = sampleHeight(level, minBX, minBZ);
        int yNE = sampleHeight(level, maxBX, minBZ);
        int ySW = sampleHeight(level, minBX, maxBZ);
        int ySE = sampleHeight(level, maxBX, maxBZ);

        int minY = Math.min(yC, Math.min(Math.min(yNW, yNE), Math.min(ySW, ySE)));
        int maxY = Math.max(yC, Math.max(Math.max(yNW, yNE), Math.max(ySW, ySE)));

        // ── Biome at centre ──────────────────────────────────────────────────
        // Sample at or above sea level so ocean cells return their surface
        // biome instead of the cave biome that lives at ocean-floor depth.
        // Without this, oceans get classified as OTHER and the COAST/RIVER
        // adjacency flags never fire on neighbouring land cells.
        int biomeY = Math.max(yC, level.getSeaLevel()) + 4;
        Holder<Biome> biome = sampleBiome(level, cxBlock, biomeY, czBlock);
        String biomeKey = biome.unwrapKey()
                .map(k -> k.identifier().toString())
                .orElse("minecraft:plains");
        BiomeCategory category = BiomeCategory.fromBiomeKey(biomeKey);

        // ── Intrinsic flags from this cell alone ─────────────────────────────
        int flags = AtlasCell.FLAG_SAMPLED;
        if (category == BiomeCategory.RIVER)  flags |= AtlasCell.FLAG_HAS_RIVER;
        if (category == BiomeCategory.OCEAN)  flags |= AtlasCell.FLAG_HAS_OCEAN;
        if (category == BiomeCategory.BEACH)  flags |= AtlasCell.FLAG_HAS_BEACH;
        if (category == BiomeCategory.SWAMP)  flags |= AtlasCell.FLAG_HAS_SWAMP;
        if (maxY - minY > AtlasCell.STEEP_THRESHOLD) flags |= AtlasCell.FLAG_STEEP;
        // FLAG_FRESHWATER is set later if neighbours include river/swamp
        if (category.isFreshwater()) flags |= AtlasCell.FLAG_FRESHWATER;

        return new AtlasCell(cellX, cellZ, yC, minY, maxY,
                biomeKey, category, flags);
    }
}