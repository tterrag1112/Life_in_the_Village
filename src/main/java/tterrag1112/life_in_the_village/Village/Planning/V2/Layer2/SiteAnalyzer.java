package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BoundingBox;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
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
 * V2 Layer 2 — Site Scoring, Cardinal Axis Selection, and Anchor
 * Adjustment.
 *
 * <p>Order of operations: tier → inclination → primary axis →
 * anchor → anchor adjustment → spine path planning.
 *
 * <p>Cardinal axis is one of {@code X / Z}. Selection priority:
 * <ol>
 *   <li>River direction within scan area → snap to nearest cardinal.</li>
 *   <li>Coast direction within scan area → snap.</li>
 *   <li>Hill within {@link #HILL_NEAR_RADIUS} of anchor → axis is
 *       perpendicular to anchor→hill vector.</li>
 *   <li>Largest flat region's bbox dimension comparison.</li>
 * </ol>
 *
 * <p>Anchor adjustment snaps the anchor toward the dominant terrain
 * seam within {@link #ANCHOR_ADJUST_RADIUS}, with feature-specific
 * inland offsets. Falls back to original anchor if the snap target
 * is inadmissible.
 */
public final class SiteAnalyzer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SiteAnalyzer.class);
    private static final long INCLINATION_SAMPLE_SALT = 0x5A17_E105_5A1FL;
    /** Search radius for anchor-adjustment seam-snapping. */
    private static final int ANCHOR_ADJUST_RADIUS = 25;
    /** Hill consideration radius for anchor + axis-selection logic. */
    private static final int HILL_NEAR_RADIUS = 30;
    /** Inland offset for river-bank anchor snap (buildable distance). */
    private static final int RIVER_INLAND_OFFSET = 8;
    /** Inland offset for coast anchor snap. */
    private static final int COAST_INLAND_OFFSET = 6;
    /** Highground prominence required to override anchor for DEFENSIVE. */
    private static final int DEFENSIVE_PROMINENCE_THRESHOLD = 8;
    /** Spine length per direction by tier (half-spine, total = 2x).
     *  Each tier carries (min, target, max) — Layer 2 samples a half
     *  per direction so the village isn't always symmetric. Total
     *  spine target per spec: CITY 160, TOWN 100, HAMLET 40, OUTPOST 20. */
    private static final EnumMap<ViabilityTier, int[]> TIER_HALF_LENGTH_RANGE;
    static {
        TIER_HALF_LENGTH_RANGE = new EnumMap<>(ViabilityTier.class);
        TIER_HALF_LENGTH_RANGE.put(ViabilityTier.CITY,    new int[]{60, 80, 90});
        TIER_HALF_LENGTH_RANGE.put(ViabilityTier.TOWN,    new int[]{40, 50, 65});
        TIER_HALF_LENGTH_RANGE.put(ViabilityTier.HAMLET,  new int[]{15, 20, 25});
        TIER_HALF_LENGTH_RANGE.put(ViabilityTier.OUTPOST, new int[]{ 8, 10, 12});
        TIER_HALF_LENGTH_RANGE.put(ViabilityTier.UNVIABLE, new int[]{10, 10, 10});
    }
    /** Salt for the spine-length sampler so it's independent of the
     *  inclination sampler. */
    private static final long SPINE_LENGTH_SALT = 0x571E_DEC1_DEAD_BEEFL;

    private SiteAnalyzer() {}

    // =========================================================================
    // Public entry points
    // =========================================================================

    public static SiteContext analyze(V2FeatureMap fmap, Culture culture, long seed) {
        return analyze(fmap, culture, seed, null);
    }

    /**
     * Track E1 anchor detection — overload taking the
     * {@link ServerLevel} so {@link AnchorDetector} can sample
     * biomes at flat-fertile anchor centres. Existing zero-level
     * call sites can keep calling the legacy overload; biome
     * lookups silently skip when {@code level == null}.
     */
    public static SiteContext analyze(V2FeatureMap fmap, Culture culture, long seed,
                                       net.minecraft.server.level.ServerLevel level) {
        return analyzeWithDiagnostics(fmap, culture, seed, level).context;
    }

    public static Result analyzeWithDiagnostics(V2FeatureMap fmap, Culture culture,
                                                long seed) {
        return analyzeWithDiagnostics(fmap, culture, seed, null);
    }

    public static Result analyzeWithDiagnostics(V2FeatureMap fmap, Culture culture,
                                                long seed,
                                                net.minecraft.server.level.ServerLevel level) {
        TierDecision tier = computeTier(fmap);
        InclinationDecision inc = computeInclination(fmap, culture, seed, tier);
        AnchorDecision anchorDec = computeAnchor(fmap, inc.inclination);
        AxisDecision axisDec = choosePrimaryAxis(fmap, anchorDec.anchor);
        AnchorAdjustment adj = adjustAnchor(fmap, anchorDec.anchor);

        BlockPos finalAnchor = adj.adjusted != null ? adj.adjusted : anchorDec.anchor;
        int[] halfRange = TIER_HALF_LENGTH_RANGE.getOrDefault(tier.tier,
                new int[]{20, 20, 20});
        Random spineRng = new Random(seed ^ SPINE_LENGTH_SALT);
        SpinePath spinePath = SpinePathPlanner.plan(fmap, finalAnchor,
                axisDec.axis, halfRange[0], halfRange[1], halfRange[2], spineRng);

        SiteContext ctx = SiteContext.withEmptyHubs(
                finalAnchor, anchorDec.anchor, axisDec.axis, spinePath,
                tier.tier, inc.inclination, culture, seed);
        // Track E1 — detect anchors and attach to the context.
        // Anchor detection is permissive and read-only; existing
        // planner / placer / road builder behaviour is unchanged.
        java.util.List<Anchor> anchors = AnchorDetector.detect(fmap, level);
        ctx = ctx.withAnchors(anchors);
        // Track E1B — select a layout strategy from the anchor mix.
        // Selection runs AFTER anchor detection (it consumes anchors)
        // and BEFORE any downstream planner; spine planner already ran
        // above and is unchanged. The result is purely informational
        // until prompt 3 wires consumption.
        StrategySelectionResult strategy = StrategySelector.select(ctx, anchors);
        ctx = ctx.withStrategy(strategy);
        Diagnostics diag = new Diagnostics(tier, inc, anchorDec, axisDec, adj);

        LOGGER.info("variation: seed={} (drives inclination sampling, spine length,"
                + " cross-street count + position, building selection,"
                + " topo-tie shuffle)", seed);
        LOGGER.info("site: tier={} inclination={} culture={} seed={}",
                tier.tier, inc.inclination, culture.id(), seed);
        LOGGER.info("anchor: original=({},{},{}) adjusted=({},{},{}) reason={}",
                anchorDec.anchor.getX(), anchorDec.anchor.getY(), anchorDec.anchor.getZ(),
                finalAnchor.getX(), finalAnchor.getY(), finalAnchor.getZ(),
                adj.reason);
        LOGGER.info("primary axis: {} ({})", axisDec.axis, axisDec.reason);
        LOGGER.info("spine path: {} segments, totalLength={}",
                spinePath.segments().size(), spinePath.totalLength());

        return new Result(ctx, diag);
    }

    // =========================================================================
    // Step 1 — Viability tier (unchanged from the previous SiteAnalyzer)
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
    // Step 2 — Inclination (unchanged from the previous SiteAnalyzer)
    // =========================================================================

    private static InclinationDecision computeInclination(V2FeatureMap fmap,
                                                          Culture culture, long seed,
                                                          TierDecision tier) {
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
        int forestCount = forests.size();
        int forestBigCount = 0;
        for (ForestRegion f : forests) if (f.coreCells() >= 20) forestBigCount++;

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
            for (StoneRegion sr : stones) if (sr.area() >= 6) { s += 20; break; }
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
            int s = 5;
            if (flatBlocks >= 1600) s += 15;
            if (riverPresent || coastPresent) s += 10;
            raw.put(Inclination.CIVIC, Math.max(0, s));
        }
        // RESIDENTIAL
        {
            int s = 5;
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
            if (hills.isEmpty() && forests.isEmpty() && !anyWater
                    && stones.isEmpty()) s -= 10;
            raw.put(Inclination.SACRED, Math.max(0, s));
        }

        EnumMap<Inclination, Double> weighted = new EnumMap<>(Inclination.class);
        double total = 0;
        for (Inclination inc : Inclination.values()) {
            double w = raw.get(inc) * culture.planningBias().biasFor(inc);
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
            chosen = Inclination.RESIDENTIAL;
            for (Inclination inc : Inclination.values()) {
                cum += weighted.get(inc);
                if (pick < cum) { chosen = inc; break; }
            }
            reason = "seeded sample";
        }
        return new InclinationDecision(chosen, raw, weighted, reason);
    }

    // =========================================================================
    // Step 3 — Anchor (initial choice; unchanged from the previous SiteAnalyzer)
    // =========================================================================

    private static AnchorDecision computeAnchor(V2FeatureMap fmap,
                                                Inclination inclination) {
        Optional<Region> flatOpt = fmap.largestFlatRegion();
        BlockPos flatCentre = flatOpt
                .map(Region::centre)
                .orElseGet(() -> {
                    BlockPos c = fmap.centre();
                    return new BlockPos(c.getX(),
                            fmap.surfaceYAt(c.getX(), c.getZ()), c.getZ());
                });

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

        // INDUSTRIAL → flat-side near a stone region (40% interp)
        if (inclination == Inclination.INDUSTRIAL) {
            StoneRegion bestStone = null;
            for (StoneRegion s : fmap.stoneExposedRegions()) {
                if (chebDist(s.centre(), flatCentre) > HILL_NEAR_RADIUS) continue;
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

        return new AnchorDecision(flatCentre,
                flatOpt.isPresent() ? "default (flat region centroid)"
                        : "default (scan centre — no flat region)");
    }

    // =========================================================================
    // Step 4 — Primary axis (NEW)
    // =========================================================================

    private static AxisDecision choosePrimaryAxis(V2FeatureMap fmap, BlockPos anchor) {
        // 1. River direction.
        Optional<List<BlockPos>> river = fmap.riverPath();
        if (river.isPresent() && river.get().size() >= 2) {
            List<BlockPos> path = river.get();
            BlockPos a = path.get(0);
            BlockPos b = path.get(path.size() - 1);
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            CardinalAxis axis = CardinalAxis.nearestTo(dx, dz);
            return new AxisDecision(axis, "river direction (dx="
                    + dx + " dz=" + dz + " → " + axis + ")");
        }
        // 2. Coast direction.
        Optional<List<BlockPos>> coast = fmap.coastline();
        if (coast.isPresent() && coast.get().size() >= 2) {
            List<BlockPos> pts = coast.get();
            BlockPos a = pts.get(0);
            BlockPos b = pts.get(pts.size() - 1);
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            CardinalAxis axis = CardinalAxis.nearestTo(dx, dz);
            return new AxisDecision(axis, "coast direction (dx="
                    + dx + " dz=" + dz + " → " + axis + ")");
        }
        // 3. Hill within HILL_NEAR_RADIUS — spine perpendicular to
        //    anchor→hill vector.
        for (HighGround h : fmap.highGroundRegions()) {
            int hx = h.centre().getX();
            int hz = h.centre().getZ();
            int dx = hx - anchor.getX();
            int dz = hz - anchor.getZ();
            int dist = Math.max(Math.abs(dx), Math.abs(dz));
            if (dist > HILL_NEAR_RADIUS) continue;
            // Perpendicular to (dx, dz) → spine runs along whichever
            // cardinal is closer to (-dz, dx).
            CardinalAxis axis = CardinalAxis.nearestTo(-dz, dx);
            return new AxisDecision(axis, "perpendicular to hill at "
                    + h.centre().getX() + "," + h.centre().getZ() + " → " + axis);
        }
        // 4. Flat-region bbox.
        Optional<Region> flat = fmap.largestFlatRegion();
        if (flat.isPresent()) {
            Region r = flat.get();
            int width = r.maxX() - r.minX();
            int length = r.maxZ() - r.minZ();
            CardinalAxis axis = width > length ? CardinalAxis.X : CardinalAxis.Z;
            return new AxisDecision(axis, "flat bbox " + width + "x" + length
                    + " → " + axis);
        }
        // Defensive default.
        return new AxisDecision(CardinalAxis.X, "default (no terrain hint)");
    }

    // =========================================================================
    // Step 5 — Anchor adjustment (NEW)
    // =========================================================================

    private static AnchorAdjustment adjustAnchor(V2FeatureMap fmap, BlockPos anchor) {
        // 1. River.
        Optional<List<BlockPos>> river = fmap.riverPath();
        if (river.isPresent()) {
            BlockPos nearest = nearest(river.get(), anchor);
            if (nearest != null && chebDist(nearest, anchor) <= ANCHOR_ADJUST_RADIUS) {
                BlockPos target = inlandFrom(anchor, nearest, RIVER_INLAND_OFFSET);
                BlockPos snapped = withSurfaceYIfAdmissible(fmap, target);
                if (snapped != null) {
                    return new AnchorAdjustment(snapped,
                            "snap to river bank (offset " + RIVER_INLAND_OFFSET + ")");
                }
            }
        }
        // 2. Coast.
        Optional<List<BlockPos>> coast = fmap.coastline();
        if (coast.isPresent()) {
            BlockPos nearest = nearest(coast.get(), anchor);
            if (nearest != null && chebDist(nearest, anchor) <= ANCHOR_ADJUST_RADIUS) {
                BlockPos target = inlandFrom(anchor, nearest, COAST_INLAND_OFFSET);
                BlockPos snapped = withSurfaceYIfAdmissible(fmap, target);
                if (snapped != null) {
                    return new AnchorAdjustment(snapped,
                            "snap to coast (offset " + COAST_INLAND_OFFSET + ")");
                }
            }
        }
        // 3. Hill base.
        for (HighGround h : fmap.highGroundRegions()) {
            int dx = h.centre().getX() - anchor.getX();
            int dz = h.centre().getZ() - anchor.getZ();
            int dist = Math.max(Math.abs(dx), Math.abs(dz));
            if (dist > ANCHOR_ADJUST_RADIUS) continue;
            // Snap toward the hill's bbox edge nearest to anchor.
            BlockPos snapped = withSurfaceYIfAdmissible(fmap,
                    nearestPointOnBbox(h.bbox(), anchor, h.centre().getY()));
            if (snapped != null) {
                return new AnchorAdjustment(snapped,
                        "snap to hill base near (" + h.centre().getX() + ","
                                + h.centre().getZ() + ")");
            }
        }
        return new AnchorAdjustment(null, "no adjustment (no feature within "
                + ANCHOR_ADJUST_RADIUS + ")");
    }

    private static BlockPos nearestPointOnBbox(BoundingBox b, BlockPos p, int y) {
        int x = clamp(p.getX(), b.minX(), b.maxX());
        int z = clamp(p.getZ(), b.minZ(), b.maxZ());
        return new BlockPos(x, y, z);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static BlockPos withSurfaceYIfAdmissible(V2FeatureMap fmap, BlockPos pos) {
        if (!fmap.inBounds(pos.getX(), pos.getZ())) return null;
        Cell cell = fmap.cellAt(pos.getX(), pos.getZ());
        BlockCategory cat = cell.category();
        if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE) return null;
        if (cell.localSlope() > 3) return null;
        return new BlockPos(pos.getX(), cell.elevationY(), pos.getZ());
    }

    private static BlockPos inlandFrom(BlockPos anchor, BlockPos nearest, int offset) {
        int dx = anchor.getX() - nearest.getX();
        int dz = anchor.getZ() - nearest.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return anchor;
        return new BlockPos(
                nearest.getX() + (int) Math.round(dx / len * offset),
                anchor.getY(),
                nearest.getZ() + (int) Math.round(dz / len * offset));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int chebDist(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }

    private static BlockPos nearest(List<BlockPos> pts, BlockPos to) {
        if (pts.isEmpty()) return null;
        BlockPos best = pts.get(0);
        long bestD = sqDist(best, to);
        for (int i = 1; i < pts.size(); i++) {
            BlockPos p = pts.get(i);
            long d = sqDist(p, to);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private static long sqDist(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static BlockPos blendToward(BlockPos a, BlockPos b, double blend) {
        return new BlockPos(
                (int) Math.round(a.getX() + (b.getX() - a.getX()) * blend),
                a.getY(),
                (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * blend));
    }

    private static BlockPos withSurfaceY(V2FeatureMap fmap, BlockPos pos) {
        return new BlockPos(pos.getX(),
                fmap.surfaceYAt(pos.getX(), pos.getZ()), pos.getZ());
    }

    // =========================================================================
    // Diagnostic carriers
    // =========================================================================

    public record Result(SiteContext context, Diagnostics diagnostics) {}

    public record Diagnostics(TierDecision tier, InclinationDecision inclination,
                              AnchorDecision anchor, AxisDecision axis,
                              AnchorAdjustment adjustment) {}

    public record TierDecision(ViabilityTier tier, int flatBlocks, float slopeFraction) {}

    public record InclinationDecision(Inclination inclination,
                                      EnumMap<Inclination, Integer> rawScores,
                                      EnumMap<Inclination, Double> weightedScores,
                                      String reason) {}

    public record AnchorDecision(BlockPos anchor, String reason) {}

    public record AxisDecision(CardinalAxis axis, String reason) {}

    public record AnchorAdjustment(BlockPos adjusted, String reason) {}
}
