package tterrag1112.life_in_the_village.Kingdom.Settlement;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.AtlasSampler;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.Optional;
import java.util.Random;
import java.util.function.LongFunction;

/**
 * Track V1 — the deterministic village-placement primitive.
 *
 * <p>This is the <b>Tier-0</b> ("potential") layer of the village-first
 * generation pipeline (doc {@code 16-VILLAGE-FIRST-GENERATION.md} sec.1/3): a
 * <b>pure function</b> {@code (placementRegion, worldSeed, on-demand digest
 * sample) -> Optional<VillageCandidate>}. It is the foundation everything else
 * consumes -- V2 (claim overlay) enumerates it, the map can evaluate it on
 * demand.
 *
 * <h3>The cost-tier contract (THE load-bearing rule)</h3>
 * This class does <b>no</b> Tier-2 work. It never scans columns, never forces
 * the persisted atlas fill, never validates a footprint, never touches V2. It
 * reads exactly ONE load-free on-demand atlas digest sample per region
 * evaluation (via the injected {@code digestSampler}, which in production is a
 * thin wrapper over {@link AtlasSampler#sampleCell} -- ~100us/cell, no chunk
 * load, no persisted fill). Pushing any heavier work into this method is the
 * documented relapse (sec.7) that recreated the laggy iterations -- don't.
 *
 * <h3>Placement math (vanilla's structure-placement pattern)</h3>
 * Adapted from vanilla {@code RandomSpreadStructurePlacement}, proven at
 * infinite-world scale with zero lag and perfect locatability:
 * <ul>
 *   <li>The world is divided into square <b>placement regions</b> of
 *       {@link #SPACING_CHUNKS} chunks. A region is identified by its
 *       integer (regionX, regionZ).</li>
 *   <li>A seed-salted {@link Random} per region (mixing the world seed +
 *       region coords + a fixed {@link #PLACEMENT_SALT}, exactly as vanilla's
 *       structure seed) picks ONE candidate chunk within the region by two
 *       {@code nextInt(SPACING - SEPARATION)} draws. The
 *       {@code (SPACING - SEPARATION)} bound is what guarantees a minimum
 *       {@link #SEPARATION_CHUNKS}-chunk gap between any two adjacent regions'
 *       candidates -- replicated from vanilla, no explicit neighbour check
 *       needed.</li>
 *   <li>The candidate block position is the centre of the chosen chunk.</li>
 * </ul>
 *
 * <h3>Lenient digest gate</h3>
 * The candidate cell's digest is sampled load-free and accepted <b>unless
 * genuinely impossible</b> -- open ocean / no land ({@link BiomeCategory#OCEAN}
 * or {@link BiomeCategory#VOID}, i.e. {@link BiomeCategory#isUnbuildable()}).
 * Everything else is accepted because V2 adapts arbitrary terrain at
 * realization (that is its entire reason to exist). A strict gate produced the
 * sparse-placement / pier-rejection failures; lenience produces the dense
 * world Garrett wants.
 *
 * <h3>Purity</h3>
 * Same {@code (regionX, regionZ, worldSeed)} -> same {@link VillageCandidate},
 * every call, order-independent (given a deterministic digest sampler, which
 * the noise-based generator is). This is the correctness property -- it kills
 * the timing / async-fill dependence that made earlier iterations
 * unpredictable, and it is what the V1 unit test proves.
 */
public final class VillagePlacement {

    private VillagePlacement() {}

    // =========================================================================
    // Tunable density knobs (the ONE density knob, per sec.3)
    // =========================================================================

    /**
     * Placement-region size in <b>chunks</b>. Vanilla village spacing is ~34
     * chunks; Garrett wants a much denser world (~20-30 villages per kingdom,
     * mostly TOWN-and-below), so this is far tighter. See the density math in
     * the PROGRESS entry: at spacing 8 (128 blocks/region) a typical kingdom
     * claim (~60-120 64-block cells, ~245k-490k block^2) overlaps ~15-30
     * placement regions, yielding ~15-28 owned villages -- Garrett's 20-30
     * target. This is the single knob to turn for global density -- it is
     * deterministic, so tuning is reproducible.
     */
    public static final int SPACING_CHUNKS = 48;

    /**
     * Minimum separation in <b>chunks</b> between candidates of adjacent
     * regions. The per-region candidate is drawn within
     * {@code [0, SPACING - SEPARATION)} chunks of the region origin, so the
     * worst-case gap between two adjacent regions' candidates is
     * {@code SEPARATION} chunks -- exactly vanilla's mechanism. Must be
     * {@code < SPACING}.
     */
    public static final int SEPARATION_CHUNKS = 8;

    /**
     * Fixed salt mixed into the per-region RNG seed, mirroring vanilla's
     * structure salt. A distinct salt keeps village placement decorrelated
     * from any other seed-salted system. Arbitrary fixed constant -- changing
     * it reshuffles all placements (acceptable in dev; version it if saves
     * must survive a change).
     */
    public static final long PLACEMENT_SALT = 0x51F4E564C47L;

    /**
     * Track V5 -- the frontier-density knob. Frontier (unclaimed-land)
     * villages are placed SPARSER than settled (claimed) land: only every
     * {@code FRONTIER_SPACING_MULTIPLIER}-th placement region along each axis
     * is a frontier region. Frontier density is therefore
     * {@code 1 / (multiplier * multiplier)} of the dense kingdom grid -- at
     * the default {@code 3} that is one frontier village per 9 dense regions
     * (Garrett wants frontier less dense than settled land; turn this up for
     * even sparser frontier, down toward 1 for near-kingdom density).
     *
     * <h3>Why a multiplier-against-the-grid, not a separate grid</h3>
     * This is the order-independence / absorption property (doc 16 sec.2/7).
     * A frontier region is a deterministic SUBSET of the SAME dense placement
     * grid V2 enumerates -- see {@link #isFrontierRegion}. The frontier village
     * at region R lands at the EXACT {@link #evaluateRegion} candidate the
     * kingdom grid would place there. So when region R later falls inside a
     * kingdom claim, the kingdom simply OWNS the already-placed candidate
     * (ownership derived from the claim, culture upgraded from {@code default})
     * with NO duplicate and NO position change -- the frontier village is
     * absorbed, not re-placed. A separate frontier grid would risk a different
     * position on absorption (flicker / dedup bugs); the subset rule makes
     * absorption an identity.
     *
     * <p>Must be {@code >= 1}. {@code 1} = every region is a frontier region
     * (frontier as dense as kingdoms; useful only for testing).
     */
    public static final int FRONTIER_SPACING_MULTIPLIER = 3;

    /** Culture assigned to frontier (unclaimed) villages (doc 16 sec.2/3). */
    public static final String FRONTIER_CULTURE = "default";

    private static final int CHUNK_BLOCKS = 16;

    // =========================================================================
    // The digest a region evaluation reads (load-free, on demand)
    // =========================================================================

    /**
     * A load-free, on-demand atlas digest sample for one cell -- exactly the
     * fields {@link AtlasSampler#sampleCell} exposes without the persisted
     * atlas fill. The V1 gate keys on {@link #category}; the role/size
     * derivation reads the relief / water flags.
     *
     * <p>In production, {@link #fromCell(AtlasCell)} wraps a freshly sampled
     * {@link AtlasCell}. In the unit test a synthetic sampler builds these
     * directly, so the primitive is testable with no {@code ServerLevel}.
     */
    public record DigestSample(
            BiomeCategory category,
            int relief,         // maxY - minY
            int centerY,
            boolean freshwater, // own/adjacent river or swamp
            boolean coast       // adjacent to ocean
    ) {
        /** Builds a digest sample from a fully-sampled atlas cell. */
        public static DigestSample fromCell(AtlasCell cell) {
            return new DigestSample(cell.category(), cell.slope(), cell.centerY(),
                    cell.isFreshwater(), cell.isCoast());
        }

        /** The lenient gate: reject ONLY genuinely impossible terrain. */
        public boolean isImpossible() {
            // OCEAN / VOID are the only hard rejects (open ocean / no land).
            // centerY <= 0 guards a degenerate/unsampled column. Everything
            // else -- steep, swamp, desert, coast -- is accepted; V2 adapts.
            return category.isUnbuildable() || centerY <= 0;
        }
    }

    // =========================================================================
    // The candidate
    // =========================================================================

    /**
     * A deterministically-placed village candidate. Carries the exact estimate
     * {@link #pos} (chunk-centre block position), the {@link #cellKey} of the
     * atlas cell it lands in, and a deterministically-derived {@link #role}
     * label + {@link #sizeBand}. Role is a LABEL per sec.3 (village type is
     * near-vestigial in V2); do not over-invest.
     *
     * <p>No persistence, no terrain scan, no V2 call -- this is a pure data
     * carrier produced by {@link #evaluateRegion}.
     */
    public record VillageCandidate(
            int regionX,
            int regionZ,
            BlockPos pos,
            long cellKey,
            String role,
            VillageSizeTier sizeBand
    ) {}

    // =========================================================================
    // The pure function
    // =========================================================================

    /**
     * Evaluates one placement region. Pure: same inputs -> same output.
     *
     * @param regionX        placement-region X (a block at world X maps to
     *                       region {@code floorDiv(blockX, SPACING_CHUNKS*16)}).
     * @param regionZ        placement-region Z.
     * @param worldSeed      the world seed.
     * @param digestSampler  load-free digest sampler keyed by packed cell key
     *                       ({@link AtlasCell#packKey}). In production a wrapper
     *                       over {@link AtlasSampler#sampleCell}; in tests a
     *                       synthetic function. Called exactly ONCE.
     * @return the region's candidate, or empty if the gate rejects the cell.
     */
    public static Optional<VillageCandidate> evaluateRegion(
            int regionX, int regionZ, long worldSeed,
            LongFunction<DigestSample> digestSampler) {

        // -- Vanilla-style per-region seed salt ------------------------------
        // Mirrors RandomSpreadStructurePlacement: a Random seeded from
        // (worldSeed, regionX, regionZ, salt). The exact magic multipliers are
        // vanilla's region constants; the result is a stable per-region stream.
        Random rng = regionRandom(worldSeed, regionX, regionZ);

        // -- Pick the candidate chunk within the region ----------------------
        // Two nextInt(SPACING - SEPARATION) draws bound the offset so adjacent
        // regions can never place closer than SEPARATION chunks.
        int span = SPACING_CHUNKS - SEPARATION_CHUNKS;
        int offX = rng.nextInt(span);
        int offZ = rng.nextInt(span);

        int chunkX = regionX * SPACING_CHUNKS + offX;
        int chunkZ = regionZ * SPACING_CHUNKS + offZ;

        // Candidate block position: chunk centre.
        int blockX = chunkX * CHUNK_BLOCKS + (CHUNK_BLOCKS / 2);
        int blockZ = chunkZ * CHUNK_BLOCKS + (CHUNK_BLOCKS / 2);

        // -- The single load-free digest read --------------------------------
        long cellKey = AtlasCell.packKey(
                blockX >> AtlasCell.CELL_SHIFT,
                blockZ >> AtlasCell.CELL_SHIFT);
        DigestSample digest = digestSampler.apply(cellKey);

        // -- Lenient gate: reject only the genuinely impossible --------------
        if (digest == null || digest.isImpossible()) {
            return Optional.empty();
        }

        // -- Deterministic role + size band from region seed + digest --------
        String role = deriveRole(rng, digest);
        VillageSizeTier sizeBand = deriveSizeBand(rng);

        int y = Math.max(digest.centerY(), 0);
        BlockPos pos = new BlockPos(blockX, y, blockZ);
        return Optional.of(new VillageCandidate(
                regionX, regionZ, pos, cellKey, role, sizeBand));
    }

    /**
     * Production convenience: sample one cell's digest load-free via
     * {@link AtlasSampler#sampleCell} and evaluate the region. Used by the V2
     * claim-overlay enumeration and the on-demand map.
     *
     * <p>Reads ONLY the load-free digest -- does NOT touch the persisted atlas
     * fill or the {@code WorldAtlas} SavedData.
     */
    public static Optional<VillageCandidate> evaluateRegion(
            net.minecraft.server.level.ServerLevel level,
            int regionX, int regionZ) {
        long worldSeed = level.getSeed();
        return evaluateRegion(regionX, regionZ, worldSeed, cellKey -> {
            int cx = AtlasCell.unpackX(cellKey);
            int cz = AtlasCell.unpackZ(cellKey);
            // Load-free on-demand digest sample (C0 sec.1/5): noise-only, no fill.
            AtlasCell cell = AtlasSampler.sampleCell(level, cx, cz);
            return DigestSample.fromCell(cell);
        });
    }

    // =========================================================================
    // Region <-> block helpers
    // =========================================================================

    /** Placement-region X containing the given world block X. */
    public static int regionForBlockX(int blockX) {
        return Math.floorDiv(blockX, SPACING_CHUNKS * CHUNK_BLOCKS);
    }

    /** Placement-region Z containing the given world block Z. */
    public static int regionForBlockZ(int blockZ) {
        return Math.floorDiv(blockZ, SPACING_CHUNKS * CHUNK_BLOCKS);
    }

    // =========================================================================
    // Track V5 -- frontier placement (the deterministic subset rule)
    // =========================================================================

    /**
     * Whether placement region (rx, rz) is a <b>frontier region</b> -- a
     * deterministic SUBSET of the dense kingdom grid sampled at the sparser
     * {@link #FRONTIER_SPACING_MULTIPLIER}. A frontier region is every
     * {@code multiplier}-th region along each axis:
     * {@code floorMod(rx, M) == 0 && floorMod(rz, M) == 0}.
     *
     * <p>This is a PURE function of (rx, rz) and the compile-time constant --
     * no seed, no digest, no atlas. It is the absorption-clean subset rule
     * (see {@link #FRONTIER_SPACING_MULTIPLIER}): because frontier regions are
     * a subset of the same dense grid, the frontier candidate at a region is
     * the IDENTICAL candidate the kingdom grid enumerates there, so a region
     * that later falls in a claim absorbs its frontier village with no
     * duplicate and no reposition.
     *
     * <p>{@code floorMod} (not {@code %}) so the subset is symmetric across the
     * origin into negative regions -- order-independent everywhere.
     */
    public static boolean isFrontierRegion(int rx, int rz) {
        int m = FRONTIER_SPACING_MULTIPLIER;
        if (m <= 1) return true; // every region is frontier (testing knob)
        return Math.floorMod(rx, m) == 0 && Math.floorMod(rz, m) == 0;
    }

    /**
     * Evaluates a region for a <b>frontier</b> candidate: the V1
     * {@link #evaluateRegion} candidate, but ONLY when {@link #isFrontierRegion}
     * holds (the sparser frontier subset). Non-frontier regions return empty
     * here even on buildable land -- those regions only place a village if a
     * kingdom claims them (the dense grid). Pure / load-free (Tier-0): same
     * single injected digest read as {@link #evaluateRegion}.
     */
    public static Optional<VillageCandidate> evaluateFrontierRegion(
            int regionX, int regionZ, long worldSeed,
            LongFunction<DigestSample> digestSampler) {
        if (!isFrontierRegion(regionX, regionZ)) return Optional.empty();
        return evaluateRegion(regionX, regionZ, worldSeed, digestSampler);
    }

    /**
     * Production convenience for {@link #evaluateFrontierRegion}: load-free
     * digest via {@link AtlasSampler#sampleCell} (Tier-0, no chunk load, no
     * persisted-atlas fill). Used by the V5 frontier enumerator + the on-demand
     * map.
     */
    public static Optional<VillageCandidate> evaluateFrontierRegion(
            net.minecraft.server.level.ServerLevel level,
            int regionX, int regionZ) {
        if (!isFrontierRegion(regionX, regionZ)) return Optional.empty();
        return evaluateRegion(level, regionX, regionZ);
    }

    /**
     * The vanilla-pattern per-region RNG. Package-visible for the unit test to
     * assert determinism of the salt itself.
     */
    static Random regionRandom(long worldSeed, int regionX, int regionZ) {
        // Vanilla RandomSpreadStructurePlacement uses these exact region
        // multipliers when seeding its WorldgenRandom; we reuse them so the
        // spread has the same well-distributed, low-correlation character.
        long seed = (long) regionX * 341873128712L
                  + (long) regionZ * 132897987541L
                  + worldSeed
                  + PLACEMENT_SALT;
        return new Random(seed);
    }

    // =========================================================================
    // Deterministic role / size derivation (light labels, sec.3)
    // =========================================================================

    /**
     * Derives a role label from the digest character + a region-RNG draw.
     * Light terrain-flavoured labelling -- coastal/river/mountain/forest bias
     * -- with a generic fallback. NOT authoritative for composition (V2's
     * roster comes from terrain-sampled inclination, not this); a label for
     * the map + future type-aware work.
     */
    private static String deriveRole(Random rng, DigestSample d) {
        if (d.coast())                                     return "harbor";
        if (d.freshwater())                                return "river_market";
        if (d.category() == BiomeCategory.MOUNTAIN
                || d.relief() > AtlasCell.STEEP_THRESHOLD) return "mining";
        if (d.category() == BiomeCategory.FOREST)          return "forestry";
        if (d.category() == BiomeCategory.DESERT)          return "caravan";
        // Generic settlements: slight farming bias on plains.
        return rng.nextInt(3) == 0 ? "trade" : "farming";
    }

    /**
     * Derives a size band deterministically from the region RNG. Skews toward
     * smaller settlements (Garrett wants "mostly TOWN and below"): mostly
     * VILLAGE/HAMLET, some TOWN, rare CITY. Stage-0 estimate; realization
     * refines to truth.
     */
    private static VillageSizeTier deriveSizeBand(Random rng) {
        int roll = rng.nextInt(100);
        if (roll < 45) return VillageSizeTier.HAMLET;
        if (roll < 85) return VillageSizeTier.VILLAGE;
        if (roll < 97) return VillageSizeTier.TOWN;
        return VillageSizeTier.CITY;
    }
}
