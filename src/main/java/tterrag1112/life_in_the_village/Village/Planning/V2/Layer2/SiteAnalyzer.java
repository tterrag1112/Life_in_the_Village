package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.ForestRegion;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.HighGround;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Region;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.StoneRegion;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * V2 Layer 2 — Site Scoring & Anchor Selection.
 *
 * <p>Reads a {@link V2FeatureMap} from Layer 1 and produces a
 * {@link SiteContext} for Layer 3. Order of operations is fixed:
 * tier → inclination → anchor → spine. Each step depends on the
 * previous.
 *
 * <p>Use {@link #analyze} for the production hand-off; use
 * {@link #analyzeWithDiagnostics} to capture the "chosen by"
 * annotations for the debug command.
 */
public final class SiteAnalyzer {

    /** Salt mixed with the master seed for inclination sampling. */
    private static final long INCLINATION_SAMPLE_SALT = 0x5A17_E105_5A1FL;
    /** Salt mixed with the master seed for the seed-chosen spine direction. */
    private static final long SPINE_DIR_SALT = 0x5917_ED1A_5A1FL;
    /** "Near terrain feature" threshold used by anchor selection rules. */
    private static final int ANCHOR_NEAR_RADIUS = 30;
    /** "On terrain feature" threshold used by spine selection rules. */
    private static final int SPINE_ON_FEATURE_RADIUS = 5;
    /** Inland step from the coast nearest-point when anchoring near coast. */
    private static final int COAST_INLAND_STEP = 5;
    /** Highground prominence required to override anchor for DEFENSIVE. */
    private static final int DEFENSIVE_PROMINENCE_THRESHOLD = 8;

    private SiteAnalyzer() {}

    // =========================================================================
    // Public entry points
    // =========================================================================

    public static SiteContext analyze(V2FeatureMap fmap, Culture culture, long seed) {
        return analyzeWithDiagnostics(fmap, culture, seed).context;
    }

    /**
     * Analyse {@code fmap} and return both the {@link SiteContext}
     * and a {@link Diagnostics} bundle describing how each decision
     * was reached. Used by the {@code /litv site} debug command.
     */
    public static Result analyzeWithDiagnostics(V2FeatureMap fmap, Culture culture, long seed) {
        // 1. Viability tier.
        TierDecision tier = computeTier(fmap);

        // 2. Inclination.
        InclinationDecision inc = computeInclination(fmap, culture, seed, tier);

        // 3. Anchor.
        AnchorDecision anchor = computeAnchor(fmap, inc.inclination);

        // 4. Spine direction.
        SpineDecision spine = computeSpine(fmap, anchor.anchor, inc.inclination, seed);

        SiteContext ctx = new SiteContext(
                anchor.anchor, spine.direction,
                tier.tier, inc.inclination, culture, seed);
        Diagnostics diag = new Diagnostics(tier, inc, anchor, spine);
        return new Result(ctx, diag);
    }

    // =========================================================================
    // Step 1 — Viability tier
    // =========================================================================

    private static TierDecision computeTier(V2FeatureMap fmap) {
        Optional<Region> flatOpt = fmap.largestFlatRegion();
        int cellSize = fmap.cellSize();
        int cellArea = flatOpt.map(Region::area).orElse(0);
        int flatBlocks = cellArea * cellSize * cellSize;

        int totalCells = fmap.gridSize() * fmap.gridSize();
        int steepCells = 0;
        for (int i = 0; i < fmap.gridSize(); i++) {
            for (int j = 0; j < fmap.gridSize(); j++) {
                if (fmap.cell(i, j).localSlope() > 2) steepCells++;
            }
        }
        float slopeFraction = totalCells == 0 ? 0f
                : (float) steepCells / totalCells;

        if (flatBlocks < ViabilityTier.OUTPOST.minFlatRegionBlocks) {
            return new TierDecision(ViabilityTier.UNVIABLE, flatBlocks, slopeFraction);
        }
        for (ViabilityTier t : new ViabilityTier[]{
                ViabilityTier.CITY, ViabilityTier.TOWN,
                ViabilityTier.HAMLET, ViabilityTier.OUTPOST}) {
            if (flatBlocks >= t.minFlatRegionBlocks
                    && slopeFraction <= t.maxSlopeFraction) {
                return new TierDecision(t, flatBlocks, slopeFraction);
            }
        }
        return new TierDecision(ViabilityTier.UNVIABLE, flatBlocks, slopeFraction);
    }

    // =========================================================================
    // Step 2 — Inclination
    // =========================================================================

    private static InclinationDecision computeInclination(
            V2FeatureMap fmap, Culture culture, long seed, TierDecision tier) {

        int totalCells = fmap.gridSize() * fmap.gridSize();
        int flatBlocks = tier.flatBlocks;
        boolean riverPresent = fmap.riverPath().isPresent();
        boolean coastPresent = fmap.coastline().isPresent();
        List<HighGround> hills = fmap.highGroundRegions();
        List<ForestRegion> forests = fmap.forestRegions();
        List<StoneRegion> stones = fmap.stoneExposedRegions();

        int waterCells = 0;
        for (int i = 0; i < fmap.gridSize(); i++) {
            for (int j = 0; j < fmap.gridSize(); j++) {
                if (fmap.cell(i, j).category() == BlockCategory.WATER) waterCells++;
            }
        }
        boolean anyWater = waterCells > 0 || riverPresent || coastPresent;

        int hillTotalArea = 0;
        int bestHillProminence = 0;
        for (HighGround h : hills) {
            hillTotalArea += h.area();
            if (h.prominence() > bestHillProminence) bestHillProminence = h.prominence();
        }

        int bestStoneArea = 0;
        for (StoneRegion s : stones) {
            if (s.coreCells() > bestStoneArea) bestStoneArea = s.coreCells();
        }

        int forestCount = forests.size();
        int forestBigCount = 0;
        for (ForestRegion f : forests) {
            if (f.coreCells() >= 20) forestBigCount++;
        }

        // V1 scoring rules (see prompt). Each clause is conditional;
        // sum, floor at 0 per inclination.
        EnumMap<Inclination, Integer> raw = new EnumMap<>(Inclination.class);
        for (Inclination inc : Inclination.values()) raw.put(inc, 0);

        // AGRICULTURAL
        {
            int s = 0;
            if (flatBlocks >= 1600) s += 20;
            if (riverPresent || coastPresent) s += 10;
            s += Math.min(20, 5 * forestCount);
            if (hillTotalArea > totalCells * 0.3) s -= 10;
            if (!anyWater) s -= 5;
            raw.put(Inclination.AGRICULTURAL, Math.max(0, s));
        }
        // INDUSTRIAL
        {
            int s = 0;
            for (StoneRegion sr : stones) {
                if (sr.area() >= 6) { s += 20; break; }
            }
            s += Math.min(30, 15 * hills.size());
            s += Math.min(15, 5 * forestCount);
            if (riverPresent) s += 10;
            if (stones.isEmpty() && hills.isEmpty()) s -= 10;
            raw.put(Inclination.INDUSTRIAL, Math.max(0, s));
        }
        // DEFENSIVE
        {
            int s = 0;
            if (bestHillProminence >= 12) s += 25;
            if (riverPresent) s += 10;
            if (coastPresent) s += 10;
            float slopeFraction = tier.slopeFraction;
            if (hills.isEmpty() && slopeFraction < 0.05f) s -= 15;
            raw.put(Inclination.DEFENSIVE, Math.max(0, s));
        }
        // CIVIC
        {
            int s = 5;  // baseline
            if (flatBlocks >= 1600) s += 15;
            if (riverPresent || coastPresent) s += 10;
            raw.put(Inclination.CIVIC, Math.max(0, s));
        }
        // RESIDENTIAL
        {
            int s = 5;  // baseline
            if (flatBlocks >= 400) s += 15;
            if (!forests.isEmpty()) s += 5;
            if (anyWater) s += 5;
            raw.put(Inclination.RESIDENTIAL, Math.max(0, s));
        }
        // SACRED
        {
            int s = 0;
            if (!hills.isEmpty()) s += 15;
            if (forestBigCount > 0) s += 15;
            if (coastPresent) s += 10;
            if (hills.isEmpty() && forests.isEmpty()
                    && !anyWater && stones.isEmpty()) s -= 10;
            raw.put(Inclination.SACRED, Math.max(0, s));
        }

        // Apply culture bias.
        EnumMap<Inclination, Double> weighted = new EnumMap<>(Inclination.class);
        double total = 0;
        for (Inclination inc : Inclination.values()) {
            double w = raw.get(inc) * culture.biasFor(inc);
            weighted.put(inc, w);
            total += w;
        }

        Inclination chosen;
        String reason;
        if (total <= 0) {
            chosen = Inclination.RESIDENTIAL;
            reason = "fallback (all scores zero)";
        } else {
            Random rng = new Random(seed ^ INCLINATION_SAMPLE_SALT);
            double pick = rng.nextDouble() * total;
            double cum = 0;
            chosen = Inclination.RESIDENTIAL;  // initial; replaced below
            for (Inclination inc : Inclination.values()) {
                cum += weighted.get(inc);
                if (pick < cum) { chosen = inc; break; }
            }
            reason = "seeded sample";
        }
        return new InclinationDecision(chosen, raw, weighted, reason);
    }

    // =========================================================================
    // Step 3 — Anchor
    // =========================================================================

    private static AnchorDecision computeAnchor(V2FeatureMap fmap, Inclination inclination) {
        Optional<Region> flatOpt = fmap.largestFlatRegion();
        // If no flat region (UNVIABLE territory), fall back to scan centre.
        BlockPos flatCentre = flatOpt
                .map(Region::centre)
                .orElseGet(() -> {
                    BlockPos c = fmap.centre();
                    return new BlockPos(c.getX(),
                            fmap.surfaceYAt(c.getX(), c.getZ()),
                            c.getZ());
                });

        // 1a. DEFENSIVE → highest peak (prominence ≥ threshold).
        if (inclination == Inclination.DEFENSIVE) {
            HighGround best = null;
            for (HighGround h : fmap.highGroundRegions()) {
                if (h.prominence() < DEFENSIVE_PROMINENCE_THRESHOLD) continue;
                if (best == null || h.prominence() > best.prominence()) best = h;
            }
            if (best != null) {
                return new AnchorDecision(best.peak(),
                        "DEFENSIVE: highest peak (prominence "
                                + best.prominence() + ")");
            }
        }

        // 1b. INDUSTRIAL → flat-side point near a stone region.
        if (inclination == Inclination.INDUSTRIAL) {
            StoneRegion bestStone = null;
            for (StoneRegion s : fmap.stoneExposedRegions()) {
                if (chebDist(s.centre(), flatCentre) > ANCHOR_NEAR_RADIUS) continue;
                if (bestStone == null || s.avgSlope() > bestStone.avgSlope()) bestStone = s;
            }
            if (bestStone != null) {
                BlockPos blended = blendToward(flatCentre, bestStone.centre(), 0.4);
                BlockPos anchored = withSurfaceY(fmap, blended);
                return new AnchorDecision(anchored,
                        "INDUSTRIAL: flat side toward stone (avgSlope "
                                + bestStone.avgSlope() + ")");
            }
        }

        // 2. River-near-flat → river point closest to flatCentre.
        Optional<List<BlockPos>> river = fmap.riverPath();
        if (river.isPresent() && !river.get().isEmpty()) {
            BlockPos nearest = nearestPoint(river.get(), flatCentre);
            if (chebDist(nearest, flatCentre) <= ANCHOR_NEAR_RADIUS) {
                return new AnchorDecision(withSurfaceY(fmap, nearest),
                        "river-near-flat (riverbank)");
            }
        }

        // 3. Coast-near-flat → 5 blocks inland from nearest coast point.
        Optional<List<BlockPos>> coast = fmap.coastline();
        if (coast.isPresent() && !coast.get().isEmpty()) {
            BlockPos nearest = nearestPoint(coast.get(), flatCentre);
            if (chebDist(nearest, flatCentre) <= ANCHOR_NEAR_RADIUS) {
                Vec3 inland = direction(nearest, flatCentre);
                BlockPos shifted = new BlockPos(
                        nearest.getX() + (int) Math.round(inland.x * COAST_INLAND_STEP),
                        nearest.getY(),
                        nearest.getZ() + (int) Math.round(inland.z * COAST_INLAND_STEP));
                return new AnchorDecision(withSurfaceY(fmap, shifted),
                        "coast-near-flat (" + COAST_INLAND_STEP + " blocks inland)");
            }
        }

        // 4. Default → flat centre.
        return new AnchorDecision(flatCentre,
                flatOpt.isPresent() ? "default (flat region centroid)"
                        : "default (scan centre — no flat region)");
    }

    // =========================================================================
    // Step 4 — Primary spine direction
    // =========================================================================

    private static SpineDecision computeSpine(V2FeatureMap fmap, BlockPos anchor,
                                              Inclination inclination, long seed) {
        // a. On river → local river tangent.
        Optional<List<BlockPos>> river = fmap.riverPath();
        if (river.isPresent() && river.get().size() >= 2) {
            int idx = nearestIndex(river.get(), anchor);
            BlockPos closest = river.get().get(idx);
            if (chebDist(closest, anchor) <= SPINE_ON_FEATURE_RADIUS) {
                return new SpineDecision(localTangent(river.get(), idx),
                        "river tangent");
            }
        }

        // b. On coast → local coast tangent.
        Optional<List<BlockPos>> coast = fmap.coastline();
        if (coast.isPresent() && coast.get().size() >= 2) {
            int idx = nearestIndex(coast.get(), anchor);
            BlockPos closest = coast.get().get(idx);
            if (chebDist(closest, anchor) <= SPINE_ON_FEATURE_RADIUS) {
                return new SpineDecision(localTangent(coast.get(), idx),
                        "coast tangent");
            }
        }

        // c. On highground peak → seed-chosen direction (no clear feature).
        for (HighGround h : fmap.highGroundRegions()) {
            if (chebDist(h.peak(), anchor) <= SPINE_ON_FEATURE_RADIUS) {
                return new SpineDecision(seededDirection(seed),
                        "seeded (anchor on peak)");
            }
        }

        // d. DEFENSIVE + highground dominant → ridge tangent.
        if (inclination == Inclination.DEFENSIVE) {
            List<HighGround> hills = fmap.highGroundRegions();
            int totalCells = fmap.gridSize() * fmap.gridSize();
            int hillArea = 0;
            HighGround nearest = null;
            int bestDist = Integer.MAX_VALUE;
            for (HighGround h : hills) {
                hillArea += h.area();
                int d = chebDist(h.centre(), anchor);
                if (d < bestDist) { bestDist = d; nearest = h; }
            }
            if (nearest != null && hillArea > totalCells * 0.2) {
                BlockPos a = new BlockPos(nearest.bbox().minX(), 0,
                        nearest.bbox().minZ());
                BlockPos b = new BlockPos(nearest.bbox().maxX(), 0,
                        nearest.bbox().maxZ());
                Vec3 ridge = direction(a, b);
                return new SpineDecision(ridge, "DEFENSIVE: ridge tangent");
            }
        }

        // e. Default → seed-chosen.
        return new SpineDecision(seededDirection(seed), "seeded (default)");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int chebDist(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }

    private static BlockPos nearestPoint(List<BlockPos> points, BlockPos target) {
        BlockPos best = points.get(0);
        long bestD2 = squaredXZ(best, target);
        for (int i = 1; i < points.size(); i++) {
            BlockPos p = points.get(i);
            long d2 = squaredXZ(p, target);
            if (d2 < bestD2) { bestD2 = d2; best = p; }
        }
        return best;
    }

    private static int nearestIndex(List<BlockPos> points, BlockPos target) {
        int bestI = 0;
        long bestD2 = squaredXZ(points.get(0), target);
        for (int i = 1; i < points.size(); i++) {
            long d2 = squaredXZ(points.get(i), target);
            if (d2 < bestD2) { bestD2 = d2; bestI = i; }
        }
        return bestI;
    }

    private static long squaredXZ(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Unit vector from {@code from} toward {@code to} in the XZ plane. */
    private static Vec3 direction(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return new Vec3(1, 0, 0);
        return new Vec3(dx / len, 0, dz / len);
    }

    /** Tangent at point {@code idx} along an ordered point list, computed
     *  from neighbours. */
    private static Vec3 localTangent(List<BlockPos> path, int idx) {
        int prev = Math.max(0, idx - 1);
        int next = Math.min(path.size() - 1, idx + 1);
        if (prev == next) return new Vec3(1, 0, 0);
        return direction(path.get(prev), path.get(next));
    }

    private static Vec3 seededDirection(long seed) {
        Random rng = new Random(seed ^ SPINE_DIR_SALT);
        double angle = rng.nextDouble() * 2 * Math.PI;
        return new Vec3(Math.cos(angle), 0, Math.sin(angle));
    }

    /** Linearly interpolate {@code blend} of the way from {@code a} toward {@code b}. */
    private static BlockPos blendToward(BlockPos a, BlockPos b, double blend) {
        return new BlockPos(
                (int) Math.round(a.getX() + (b.getX() - a.getX()) * blend),
                a.getY(),
                (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * blend));
    }

    /** Replace {@code pos.y} with the surface y at its xz, looked up from
     *  {@code fmap}. */
    private static BlockPos withSurfaceY(V2FeatureMap fmap, BlockPos pos) {
        return new BlockPos(pos.getX(),
                fmap.surfaceYAt(pos.getX(), pos.getZ()), pos.getZ());
    }

    // =========================================================================
    // Diagnostic carriers
    // =========================================================================

    public record Result(SiteContext context, Diagnostics diagnostics) {}

    public record Diagnostics(TierDecision tier, InclinationDecision inclination,
                              AnchorDecision anchor, SpineDecision spine) {}

    public record TierDecision(ViabilityTier tier, int flatBlocks, float slopeFraction) {}

    public record InclinationDecision(
            Inclination inclination,
            EnumMap<Inclination, Integer> rawScores,
            EnumMap<Inclination, Double> weightedScores,
            String reason) {}

    public record AnchorDecision(BlockPos anchor, String reason) {}

    public record SpineDecision(Vec3 direction, String reason) {}
}
