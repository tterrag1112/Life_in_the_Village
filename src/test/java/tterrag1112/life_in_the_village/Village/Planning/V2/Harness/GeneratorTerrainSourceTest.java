package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.GeneratorTerrainSource;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track C1 — validates {@link GeneratorTerrainSource} end-to-end through
 * the production classifier {@link V2FeatureMap#scan(
 * tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.TerrainSource,
 * BlockPos, int)}.
 *
 * <p><b>Why a harness test (not a {@code /litv} command):</b> the C0
 * spike proved (and {@link GeneratorSamplingBenchmarkTest} demonstrates)
 * that a REAL vanilla overworld {@link NoiseBasedChunkGenerator} +
 * {@link RandomState} can be constructed headlessly via
 * {@code VanillaRegistries.createLookup()} — no FML, no server, no world,
 * no chunks. So the live generator IS constructible in the harness; an
 * in-game command is unnecessary for validation (it would only add the
 * installed/modded generator's cost, not new coverage).
 *
 * <p><b>The §4-trap regression this guards:</b> a generator-backed source
 * that fed raw {@code getBaseColumn} states to the classifier would see
 * all land as {@code STONE_EXPOSED} and find <b>zero</b> {@code FOREST}.
 * This test scans a large region of real overworld terrain and asserts
 * the classifier produces <b>non-degenerate</b> output: some FOREST, some
 * WATER, plenty of OPEN, and crucially that the map is NOT all-STONE.
 *
 * <p>Bootstrap mirrors {@link HeadlessHarnessTest} / {@code net.minecraft.data.Main}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class GeneratorTerrainSourceTest {

    @BeforeAll
    public void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void scansToNonDegenerateClassification() {
        // ── Construct the real overworld generator, headlessly (C0 §2/§7) ─────
        long seed = Long.getLong("c1.surveytest.seed", 8675309L);
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        Holder<NoiseGeneratorSettings> settings = registries
                .lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(registries
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        ChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
        RandomState randomState = RandomState.create(settings.value(),
                registries.lookupOrThrow(Registries.NOISE), seed);
        NoiseSettings ns = settings.value().noiseSettings();
        LevelHeightAccessor heightAccessor =
                LevelHeightAccessor.create(ns.minY(), ns.height());

        // ── A large region maximises the chance of seeing varied biomes
        //    (forest + ocean/river) in one scan. radius 300 / CELL_SIZE 2
        //    -> 300x300 = 90 000 cells; still a fast headless scan. ────────────
        int radius = 300;

        // Aggregate category counts across several disjoint centres so a
        // single uniform-biome patch can't produce a false negative.
        int[] totals = new int[BlockCategory.values().length];
        long t0 = System.nanoTime();
        int scans = 0;
        for (int[] c : new int[][] {
                {0, 0}, {4000, 1500}, {-3000, 5000}, {8000, -2000} }) {
            GeneratorTerrainSource source =
                    new GeneratorTerrainSource(generator, randomState, heightAccessor);
            V2FeatureMap map = V2FeatureMap.scan(source,
                    new BlockPos(c[0], 0, c[1]), radius);
            int[] counts = map.categoryCounts();
            for (int i = 0; i < counts.length; i++) totals[i] += counts[i];
            scans++;
            // Spot-check the seam contracts on this source.
            assertContracts(source, generator, randomState, heightAccessor, c[0], c[1]);
        }
        long dtMs = (System.nanoTime() - t0) / 1_000_000L;

        int water   = totals[BlockCategory.WATER.ordinal()];
        int shore   = totals[BlockCategory.SHORE.ordinal()];
        int forest  = totals[BlockCategory.FOREST.ordinal()];
        int stone   = totals[BlockCategory.STONE_EXPOSED.ordinal()];
        int open    = totals[BlockCategory.OPEN.ordinal()];
        int total   = water + shore + forest + stone
                + totals[BlockCategory.STRUCTURE.ordinal()] + open;

        System.out.printf(Locale.ROOT,
                "C1 GeneratorTerrainSource: %d scans (r=%d), %d cells, %d ms%n",
                scans, radius, total, dtMs);
        System.out.printf(Locale.ROOT,
                "  WATER=%d SHORE=%d FOREST=%d STONE=%d OPEN=%d%n",
                water, shore, forest, stone, open);

        // ── Non-degenerate assertions (the §4-trap guard) ────────────────────
        assertTrue(total > 0, "scan produced no cells");
        // The trap symptom would be: stone == nearly everything, forest == 0.
        assertTrue(stone < total,
                "classifier saw ALL terrain as STONE_EXPOSED — the §4 "
                        + "pre-surface-rule trap (raw stone fed to the classifier)");
        assertTrue(forest > 0,
                "no FOREST classified across " + scans + " large scans — "
                        + "biome-driven tree-column synthesis is not tripping "
                        + "V2FeatureMap.hasTreeColumn");
        assertTrue(water + shore > 0,
                "no WATER/SHORE classified — the WORLD_SURFACE_WG vs "
                        + "OCEAN_FLOOR_WG water detector or water synthesis failed");
        assertTrue(open > 0, "no OPEN (grass/sand) land classified");
    }

    /** Spot-checks the {@link GeneratorTerrainSource} method contracts at
     *  one column, comparing against direct generator queries. */
    private void assertContracts(GeneratorTerrainSource source,
                                 ChunkGenerator generator,
                                 RandomState randomState,
                                 LevelHeightAccessor heightAccessor,
                                 int x, int z) {
        // height() must equal getBaseHeight(WORLD_SURFACE_WG) - 1 (the
        // LiveTerrainSource -1 convention).
        int expected = generator.getBaseHeight(x, z,
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                heightAccessor, randomState) - 1;
        assertTrue(source.height(x, z) == expected,
                "height() must match getBaseHeight(WORLD_SURFACE_WG)-1");

        // maxY() must match the height accessor's ceiling.
        assertTrue(source.maxY() == heightAccessor.getMaxY(),
                "maxY() must equal heightAccessor.getMaxY()");

        // biomeAt() is never null on the vanilla path.
        Holder<?> biome = source.biomeAt(new BlockPos(x, source.height(x, z) + 1, z));
        assertNotNull(biome, "biomeAt() returned null on the vanilla path");

        // blockAt() at the surface must be a real (non-air) block.
        assertTrue(!source.blockAt(x, source.height(x, z), z).isAir(),
                "surface block must be solid (or water), not air");
    }
}
