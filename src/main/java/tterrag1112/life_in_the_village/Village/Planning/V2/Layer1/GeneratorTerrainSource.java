package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Track C1 — generator-backed {@link TerrainSource} for the charter-gen
 * survey stage (design: {@code .claude/planning/05-CHARTER-GEN-DESIGN.md}
 * §3; feasibility + architecture: {@code 08-C0-SAMPLING-SPIKE.md} §8).
 *
 * <p>Reads predicted heights/biomes straight from the chunk generator's
 * noise functions — <b>no chunks are loaded</b>. This is the load-free
 * sampler the survey stage scans through {@link V2FeatureMap#scan} to
 * site a charter without paying chunk-generation cost (the same seam the
 * harness's {@link SyntheticTerrainSource} exercises). Construction
 * mirrors the production samplers {@code AtlasSampler} and
 * {@code DeepTerrainInspector}: pull {@link ChunkGenerator},
 * {@link RandomState} and a {@link LevelHeightAccessor} from a
 * {@link ServerLevel}.
 *
 * <h3>The §4 trap — pre-surface-rule states</h3>
 * {@link ChunkGenerator#getBaseColumn} returns the column <b>before</b>
 * surface rules, carvers, and features: land is the generator's default
 * block (stone) or water/air — <b>never grass, sand, or logs/leaves</b>.
 * Feeding those raw states to {@link V2FeatureMap}'s classifier would
 * classify all land as {@code STONE_EXPOSED} and find <b>zero</b>
 * {@code FOREST}. So this source <b>synthesizes</b> a plausible surface
 * vocabulary the classifier already understands (the exact
 * grass/sand/water/log/leaf/stone set {@link SyntheticTerrainSource}
 * proves out), driven by the biome:
 * <ul>
 *   <li><b>Water + depth</b> from the WG-heightmap comparison: a column
 *       is water iff {@code WORLD_SURFACE_WG > OCEAN_FLOOR_WG}
 *       (surface counts water, ocean-floor skips it — C0 §2). The top
 *       block is then {@link Blocks#WATER}, matching
 *       {@code SyntheticTerrainSource}'s water-column convention.</li>
 *   <li><b>Forest</b> inferred from biome tags
 *       ({@code #minecraft:is_forest / is_taiga / is_jungle}), with a
 *       ResourceKey fallback when tags are unbound (headless survey
 *       construction; see {@link #matchesTagsOrKeys}). There are
 *       no real trees at survey time, so a short synthetic log+leaf
 *       column is stamped above the surface — enough log/leaf blocks for
 *       {@link V2FeatureMap}'s 30-block column scan to cross its
 *       {@code FOREST_MIN_TREE_BLOCKS} threshold.</li>
 *   <li><b>Sand</b> for desert / beach / badlands biomes; <b>grass</b>
 *       otherwise — the default land surface.</li>
 * </ul>
 * Fidelity caveat (C0 §9): biome-driven guessing is coarser than the
 * live per-column scan; realization still adapts. Acceptable for siting.
 *
 * <h3>Memoization</h3>
 * Layer 1 revisits columns during classification (water probe at
 * {@code surfaceY+1}, the 30-block forest scan), so columns are cached
 * keyed by packed {@code (x, z)} (C0 §8.1). The cache is
 * <b>per-instance</b>: one survey = one source = one cache; nothing is
 * static, so a fresh survey re-reads against the current generator.
 *
 * <h3>Threading</h3>
 * Sampling is safe off-thread for concurrent reads (C0 §5: each query
 * builds its own {@code NoiseChunk}; {@code RandomState}'s caches are
 * concurrent), but <b>this slice is synchronous</b>. The
 * {@link ConcurrentHashMap} column cache keeps the source correct if a
 * later slice schedules surveys on a worker; the budget/off-thread
 * scheduler itself (C0 §8.3, {@code GraphEdgeRealizationSystem}
 * precedent) is a <b>later C1 slice</b> and is not built here.
 *
 * <p>1.21.11 mappings ({@code Identifier}, {@code ResourceKey#identifier()})
 * — every symbol verified against decompiled {@code neoforge-21.11.38-beta}.
 */
public final class GeneratorTerrainSource implements TerrainSource {

    /** Synthetic tree-column height above the surface for forest biomes.
     *  Comfortably exceeds {@code V2FeatureMap.FOREST_MIN_TREE_BLOCKS} (3)
     *  so the classifier's column scan reliably trips FOREST. */
    private static final int SYNTH_TREE_HEIGHT = 6;
    /** Trunk height (logs) within {@link #SYNTH_TREE_HEIGHT}; the rest is
     *  leaves. 2 logs + 4 leaves comfortably clears the 3-block min. */
    private static final int SYNTH_TRUNK_HEIGHT = 2;

    private final ChunkGenerator generator;
    private final BiomeSource biomeSource;
    private final RandomState randomState;
    private final Climate.Sampler climateSampler;
    private final LevelHeightAccessor heightAccessor;

    /** Per-survey column memo, keyed by packed (x, z). */
    private final ConcurrentHashMap<Long, Column> columns = new ConcurrentHashMap<>();

    /**
     * Production constructor: build from a live {@link ServerLevel} the
     * same way {@code AtlasSampler}/{@code DeepTerrainInspector} do. No
     * chunks are touched; only the chunk source's generator + random
     * state are read.
     */
    public GeneratorTerrainSource(ServerLevel level) {
        this(level.getChunkSource().getGenerator(),
                level.getChunkSource().randomState(),
                level);
    }

    /**
     * Primitive constructor — used by the headless harness, which
     * constructs a real overworld generator from
     * {@code VanillaRegistries.createLookup()} (no server, no world;
     * C0 §2/§7). Production routes through {@link #GeneratorTerrainSource(ServerLevel)}.
     *
     * @param generator      the installed chunk generator (modded
     *                       generators answer through this seam — C0 §6)
     * @param randomState    carries seed + noise router config
     * @param heightAccessor the world Y range (a {@code ServerLevel} is
     *                       itself one; the harness builds one from the
     *                       noise settings)
     */
    public GeneratorTerrainSource(ChunkGenerator generator,
                                  RandomState randomState,
                                  LevelHeightAccessor heightAccessor) {
        this.generator = generator;
        this.biomeSource = generator.getBiomeSource();
        this.randomState = randomState;
        this.climateSampler = randomState.sampler();
        this.heightAccessor = heightAccessor;
    }

    // =========================================================================
    // TerrainSource contract
    // =========================================================================

    /**
     * Y of the highest solid (or water) block. Aligned with
     * {@link LiveTerrainSource#height} ({@code getHeight(...) - 1}): the
     * generator's {@code getBaseHeight(WORLD_SURFACE_WG)} returns the
     * first-air Y, so the highest occupied block is one below. Water
     * counts (WORLD_SURFACE_WG stops at first non-air), so a water
     * column reports the water surface Y — matching
     * {@code SyntheticTerrainSource}'s water convention.
     */
    @Override
    public int height(int x, int z) {
        return column(x, z).surfaceY;
    }

    @Override
    public BlockState blockAt(int x, int y, int z) {
        return column(x, z).blockAt(y);
    }

    @Override
    public int maxY() {
        return heightAccessor.getMaxY();
    }

    @Override
    public Holder<Biome> biomeAt(BlockPos pos) {
        // Quart (4-block) resolution — same call AtlasSampler.sampleBiome
        // makes. Never null on the live/vanilla path; the contract still
        // permits null for sources without a biome layer.
        return biomeSource.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2,
                pos.getZ() >> 2, climateSampler);
    }

    // =========================================================================
    // Column synthesis + memoization
    // =========================================================================

    private Column column(int x, int z) {
        return columns.computeIfAbsent(BlockPos.asLong(x, 0, z), key -> synthesize(x, z));
    }

    /**
     * Build the synthesized column for (x, z). One generator height
     * query for the surface, one for the ocean floor (the chunk-free
     * water+depth detector), and one biome query for the surface
     * vocabulary. Block states are produced lazily by {@link Column}
     * from these three facts; the raw {@link ChunkGenerator#getBaseColumn}
     * is intentionally NOT used — its pre-surface-rule stone/water/air
     * would defeat the classifier (the §4 trap).
     */
    private Column synthesize(int x, int z) {
        int surfaceY = generator.getBaseHeight(x, z,
                Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState) - 1;
        int floorY = generator.getBaseHeight(x, z,
                Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState) - 1;

        // WORLD_SURFACE_WG counts water; OCEAN_FLOOR_WG skips it. A gap
        // means water sits on top of the solid floor.
        boolean water = surfaceY > floorY;

        // Biome read at (or just above) the surface so ocean columns
        // return their surface biome, not the cave biome at depth — the
        // same guard AtlasSampler.sampleCell uses.
        int biomeY = Math.max(surfaceY, floorY) + 1;
        Holder<Biome> biome = biomeAt(new BlockPos(x, biomeY, z));
        Surface surface = water ? Surface.WATER : surfaceFor(biome);

        return new Column(surfaceY, floorY, water, surface);
    }

    /** Biome-driven land surface vocabulary (no real surface rules). */
    private static Surface surfaceFor(Holder<Biome> biome) {
        if (biome == null) {
            return Surface.GRASS;
        }
        if (isForest(biome)) {
            return Surface.FOREST;
        }
        if (isSandy(biome)) {
            return Surface.SAND;
        }
        return Surface.GRASS;
    }

    private static boolean isForest(Holder<Biome> biome) {
        return matchesTagsOrKeys(biome, FOREST_TAGS, FOREST_KEYS);
    }

    private static boolean isSandy(Holder<Biome> biome) {
        // DESERT is a single biome key (Biomes.DESERT) — is(ResourceKey) never
        // touches tags, so it is always safe and stays a direct check.
        return biome.is(Biomes.DESERT)
                || matchesTagsOrKeys(biome, SANDY_TAGS, SANDY_KEYS);
    }

    // =========================================================================
    // Tag-then-ResourceKey biome matching (unbound-tag robustness)
    // =========================================================================

    /**
     * Robust biome membership test. Tries the biome <b>tags</b> first — the
     * worldgen-mod-proof path (a modded forest biome carries
     * {@code #minecraft:is_forest}; C1 design §3). On a live {@link ServerLevel}
     * tags are bound and this is all that runs.
     *
     * <p>But the headless {@code VanillaRegistries.createLookup()} registries
     * (used by the harness and any pre-server survey-construction path) run
     * <b>code bootstraps only</b> — they never load the datapack biome-tag
     * JSON, so {@link Holder.Reference} tags are unbound and
     * {@code biome.is(TagKey)} throws {@code IllegalStateException("Tags not
     * bound")}. {@link Holder} exposes no non-throwing tags-present probe
     * (`isBound()` checks key+value, not tags; `tags()` itself throws when
     * unbound), so the unbound state is detected by catching that exception —
     * once per unique column thanks to the column cache — and falling back to
     * {@link ResourceKey} matching. ResourceKeys are always present on a bound
     * holder, so the fallback can never throw and exactly reproduces the
     * vanilla tag membership (the {@code *_KEYS} sets below mirror the tag
     * JSON). Vanilla biomes classify identically on both paths; only modded
     * biomes (absent from the fallback set) are coarser in the unbound case,
     * which only ever occurs pre-server where no modded generator is installed.
     */
    private static boolean matchesTagsOrKeys(Holder<Biome> biome,
                                             TagKey<Biome>[] tags,
                                             Set<ResourceKey<Biome>> keys) {
        try {
            for (TagKey<Biome> tag : tags) {
                if (biome.is(tag)) {
                    return true;
                }
            }
            return false;
        } catch (IllegalStateException unboundTags) {
            // "Tags not bound" — headless registries. Fall back to keys.
            return biome.unwrapKey().filter(keys::contains).isPresent();
        }
    }

    @SuppressWarnings("unchecked")
    private static final TagKey<Biome>[] FOREST_TAGS = (TagKey<Biome>[]) new TagKey[] {
            BiomeTags.IS_FOREST, BiomeTags.IS_TAIGA, BiomeTags.IS_JUNGLE };

    @SuppressWarnings("unchecked")
    private static final TagKey<Biome>[] SANDY_TAGS = (TagKey<Biome>[]) new TagKey[] {
            BiomeTags.IS_BEACH, BiomeTags.IS_BADLANDS };

    /** Mirrors {@code #minecraft:is_forest + is_taiga + is_jungle} membership
     *  (vanilla 1.21.11 tag JSON) for the unbound-tag fallback. */
    private static final Set<ResourceKey<Biome>> FOREST_KEYS = Set.of(
            // is_forest
            Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.BIRCH_FOREST,
            Biomes.OLD_GROWTH_BIRCH_FOREST, Biomes.DARK_FOREST,
            Biomes.PALE_GARDEN, Biomes.GROVE,
            // is_taiga
            Biomes.TAIGA, Biomes.SNOWY_TAIGA, Biomes.OLD_GROWTH_PINE_TAIGA,
            Biomes.OLD_GROWTH_SPRUCE_TAIGA,
            // is_jungle
            Biomes.JUNGLE, Biomes.SPARSE_JUNGLE, Biomes.BAMBOO_JUNGLE);

    /** Mirrors {@code #minecraft:is_beach + is_badlands} membership (vanilla
     *  1.21.11 tag JSON) for the unbound-tag fallback. DESERT is matched
     *  separately by {@link #isSandy} via {@code is(Biomes.DESERT)}. */
    private static final Set<ResourceKey<Biome>> SANDY_KEYS = Set.of(
            // is_beach
            Biomes.BEACH, Biomes.SNOWY_BEACH,
            // is_badlands
            Biomes.BADLANDS, Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS);

    // =========================================================================
    // Synthesized column model
    // =========================================================================

    /** The land/water surface vocabulary the classifier understands. */
    private enum Surface { GRASS, SAND, FOREST, WATER }

    /**
     * One memoized column. Stores the three sampled facts and answers
     * {@link #blockAt(int)} by synthesizing the state — so the
     * classifier sees a coherent surface column (grass/sand/water +
     * a synthetic tree canopy for forest), stone below, air above.
     */
    private static final class Column {
        final int surfaceY;
        final int floorY;
        final boolean water;
        final Surface surface;

        Column(int surfaceY, int floorY, boolean water, Surface surface) {
            this.surfaceY = surfaceY;
            this.floorY = floorY;
            this.water = water;
            this.surface = surface;
        }

        BlockState blockAt(int y) {
            if (water) {
                // Water from the floor up to (and including) the surface;
                // stone below; air above. The classifier's water probe
                // reads surfaceY (and surfaceY+1, which is air) — top
                // block being WATER trips isWaterFluid(surface).
                if (y > surfaceY) return Blocks.AIR.defaultBlockState();
                if (y > floorY) return Blocks.WATER.defaultBlockState();
                return Blocks.STONE.defaultBlockState();
            }

            if (y < surfaceY) {
                return Blocks.STONE.defaultBlockState();
            }
            if (y == surfaceY) {
                return switch (surface) {
                    case SAND   -> Blocks.SAND.defaultBlockState();
                    // A forest's surface is grass with a tree column above
                    // it (matching real forest floor); FOREST is detected
                    // by the canopy scan, not the surface block.
                    case FOREST -> Blocks.GRASS_BLOCK.defaultBlockState();
                    default     -> Blocks.GRASS_BLOCK.defaultBlockState();
                };
            }
            // Above the surface: synthesize a tree column for forests so
            // V2FeatureMap.hasTreeColumn finds logs/leaves; else air.
            if (surface == Surface.FOREST) {
                int above = y - surfaceY; // 1-based height into the canopy
                if (above <= SYNTH_TREE_HEIGHT) {
                    return above <= SYNTH_TRUNK_HEIGHT
                            ? Blocks.OAK_LOG.defaultBlockState()
                            : Blocks.OAK_LEAVES.defaultBlockState();
                }
            }
            return Blocks.AIR.defaultBlockState();
        }
    }
}
