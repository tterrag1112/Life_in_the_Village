package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.ForestRegion;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.HighGround;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.StoneRegion;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track E1 anchor detection — scans a {@link V2FeatureMap} and
 * produces a permissive list of {@link Anchor}s.
 *
 * <h3>Per-type heuristics</h3>
 * Each type's detector reads the existing V2FeatureMap primitives
 * (cells, regions, high ground, stone regions, forest regions)
 * and emits anchors with quality ∈ [0, 1]. Quality 1.0 means
 * "ideal example"; below {@link Anchor#QUALITY_THRESHOLD} (0.2)
 * the anchor is dropped.
 *
 * <h3>De-duplication (CC's call (d))</h3>
 * Same-type anchors merge when their extents overlap by ≥50% OR
 * their centres are within {@value #DEDUP_CENTRE_DISTANCE} blocks.
 * Cross-type anchors never merge. Merging picks the higher-quality
 * survivor and unions the extents.
 *
 * <h3>Determinism</h3>
 * Detection is a pure function of the feature map. Iteration order
 * is grid-row-major so the same scan produces the same anchor list
 * every time.
 *
 * <h3>Biome hint (CC's call (3))</h3>
 * When a {@link ServerLevel} is available, the detector samples
 * {@code level.getBiome(centre)} once per {@code FLAT_FERTILE}
 * anchor for the {@code biomeHint} metadata. Other types skip
 * biome lookups.
 *
 * <p>{@link AnchorType#CROSSROADS} detection is reserved — returns
 * an empty list until prompt-2+ wires the cross-village road graph.
 */
public final class AnchorDetector {

    // ── tunables ─────────────────────────────────────────────────────────

    /** Dedup: centres within this many blocks merge same-type anchors. */
    public static final int DEDUP_CENTRE_DISTANCE = 8;
    /** Dedup: extents with this much mutual overlap fraction merge. */
    public static final double DEDUP_OVERLAP_FRACTION = 0.50;

    /** flat_fertile: cell counts saturating to quality = 1.0. */
    public static final int FLAT_FERTILE_SATURATION_CELLS = 200;
    public static final int FLAT_FERTILE_MIN_CELLS = 8;

    /** defensible_peak: prominence saturating to quality factor = 1.0. */
    public static final int PEAK_PROMINENCE_SATURATION = 30;
    public static final int PEAK_MIN_STEEP_SIDES = 3;
    public static final int PEAK_DROP_THRESHOLD = 5;

    /** defensible_ring: minimum flat top + perimeter completeness. */
    public static final int RING_PERIMETER_DROP = 6;
    public static final int RING_MIN_TOP_CELLS = 6;
    public static final double RING_PERIMETER_COMPLETENESS = 0.80;

    /** ridge_line / valley_floor: linear run thresholds. */
    public static final int RIDGE_MIN_RUN_LENGTH = 5;       // cells
    public static final int RIDGE_PROMINENCE = 4;           // height above neighbors
    public static final int RIDGE_LENGTH_SATURATION = 32;   // cells

    /** cliff_face: avgSlope cutoffs. */
    public static final int CLIFF_AVG_SLOPE_MIN = 4;
    public static final int CLIFF_AVG_SLOPE_SATURATION = 8;
    public static final int CLIFF_AREA_SATURATION = 200;

    /** water_edge / forest_edge: segment-length scaling. */
    public static final int EDGE_LENGTH_SATURATION = 64;    // cells

    /** river_bend: minimum local curvature in degrees. */
    public static final double BEND_MIN_ANGLE_DEG = 45.0;

    /** natural_clearing: wall completeness threshold. */
    public static final double CLEARING_WALL_COMPLETENESS = 0.75;
    public static final int CLEARING_MIN_CELLS = 8;
    public static final int CLEARING_AREA_SATURATION = 200;

    /** island: surround completeness threshold. */
    public static final double ISLAND_SURROUND_COMPLETENESS = 0.85;
    public static final int ISLAND_AREA_SATURATION = 100;

    /** valley_floor: depression depth + width thresholds. */
    public static final int VALLEY_DEPTH = 4;

    private AnchorDetector() {}

    // =========================================================================
    // Public entry
    // =========================================================================

    /**
     * Detects all anchor types and returns them sorted by quality
     * descending, type-grouped. {@code level} may be null — when
     * null, biome lookups are skipped (biomeHint omitted from
     * metadata).
     */
    public static List<Anchor> detect(V2FeatureMap fmap, ServerLevel level) {
        return detect(fmap, level == null ? null
                : new tterrag1112.life_in_the_village.Village.Planning.V2.Layer1
                        .LiveTerrainSource(level));
    }

    /**
     * Track E1 — {@link TerrainSource}-flavoured overload used by the
     * headless harness. {@code source} may be null — when null, biome
     * lookups are skipped exactly as in the {@link ServerLevel}
     * overload's null path.
     */
    public static List<Anchor> detect(V2FeatureMap fmap,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.TerrainSource source) {
        if (fmap == null) return List.of();
        List<Anchor> all = new ArrayList<>();

        Map<AnchorType, List<Anchor>> byType = new LinkedHashMap<>();
        addType(byType, AnchorType.FLAT_FERTILE,     detectFlatFertile(fmap, source));
        addType(byType, AnchorType.DEFENSIBLE_PEAK,  detectDefensiblePeak(fmap));
        addType(byType, AnchorType.DEFENSIBLE_RING,  detectDefensibleRing(fmap));
        addType(byType, AnchorType.RIDGE_LINE,       detectRidgeLine(fmap));
        addType(byType, AnchorType.VALLEY_FLOOR,     detectValleyFloor(fmap));
        addType(byType, AnchorType.CLIFF_FACE,       detectCliffFace(fmap));
        addType(byType, AnchorType.WATER_EDGE,       detectWaterEdge(fmap));
        addType(byType, AnchorType.RIVER_BEND,       detectRiverBend(fmap));
        addType(byType, AnchorType.ISLAND,           detectIsland(fmap));
        addType(byType, AnchorType.FOREST_EDGE,      detectForestEdge(fmap));
        addType(byType, AnchorType.NATURAL_CLEARING, detectNaturalClearing(fmap));
        // CROSSROADS reserved; detector returns empty.

        // De-dup per type, sort by quality desc, then assign sequential ids.
        int idx = 0;
        for (Map.Entry<AnchorType, List<Anchor>> e : byType.entrySet()) {
            List<Anchor> raw = e.getValue();
            List<Anchor> deduped = dedupeSameType(raw);
            deduped.sort(Comparator.comparingDouble(Anchor::quality).reversed());
            for (Anchor a : deduped) {
                all.add(new Anchor("a" + idx++, a.type(), a.centre(),
                        a.quality(), a.extent(), a.dirX(), a.dirZ(), a.metadata()));
            }
        }
        return all;
    }

    private static void addType(Map<AnchorType, List<Anchor>> byType,
                                AnchorType type, List<Anchor> anchors) {
        byType.computeIfAbsent(type, k -> new ArrayList<>()).addAll(anchors);
    }

    // =========================================================================
    // Topographic detectors
    // =========================================================================

    /**
     * flat_fertile: connected components of {@code OPEN}/{@code SHORE}
     * cells with {@code localSlope ≤ 1}. Quality = area_ratio ×
     * flatness_factor, where area_ratio = cellCount /
     * {@link #FLAT_FERTILE_SATURATION_CELLS} clamped to 1, and
     * flatness_factor = 1 − avgLocalSlope / 2.
     */
    private static List<Anchor> detectFlatFertile(V2FeatureMap fmap,
            tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.TerrainSource source) {
        int n = fmap.gridSize();
        boolean[][] visited = new boolean[n][n];
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (visited[i][j]) continue;
                Cell c = fmap.cell(i, j);
                if (!isFlatLand(c)) continue;
                Component comp = floodFill(fmap, visited, i, j,
                        AnchorDetector::isFlatLand);
                if (comp.cellCount < FLAT_FERTILE_MIN_CELLS) continue;
                double areaRatio = Math.min(1.0,
                        (double) comp.cellCount / FLAT_FERTILE_SATURATION_CELLS);
                double avgSlope = comp.slopeSum / Math.max(1, comp.cellCount);
                double flatnessFactor = Math.max(0.0, 1.0 - avgSlope / 2.0);
                double quality = areaRatio * flatnessFactor;
                if (quality < Anchor.QUALITY_THRESHOLD) continue;
                AnchorExtent extent = comp.extent(fmap);
                BlockPos centre = comp.centre(fmap);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("usableCellCount", comp.cellCount);
                meta.put("averageFlatness", round3(1.0 - avgSlope / 2.0));
                if (source != null) {
                    try {
                        var holder = source.biomeAt(centre);
                        if (holder != null) {
                            meta.put("biomeHint",
                                    holder.unwrapKey()
                                            .map(k -> k.identifier().toString())
                                            .orElse("unknown"));
                        }
                    } catch (Throwable t) {
                        // Biome lookup failed (e.g. unloaded chunk); skip.
                    }
                }
                out.add(new Anchor("", AnchorType.FLAT_FERTILE, centre, quality,
                        extent, 0.0, 0.0, meta));
            }
        }
        return out;
    }

    private static boolean isFlatLand(Cell c) {
        if (c == null) return false;
        if (c.localSlope() > 1) return false;
        return c.category() == BlockCategory.OPEN
                || c.category() == BlockCategory.SHORE;
    }

    /**
     * defensible_peak: each {@link HighGround} region whose peak
     * has ≥{@link #PEAK_MIN_STEEP_SIDES} of 4 cardinal-quadrant
     * cells dropping by ≥{@link #PEAK_DROP_THRESHOLD} within
     * radius 4 cells. Quality = prominence/30 × steepSideFraction.
     */
    private static List<Anchor> detectDefensiblePeak(V2FeatureMap fmap) {
        List<Anchor> out = new ArrayList<>();
        for (HighGround hg : fmap.highGroundRegions()) {
            BlockPos peak = hg.peak();
            int pi = fmap.worldXToCellI(peak.getX());
            int pj = fmap.worldZToCellJ(peak.getZ());
            int steepSides = countSteepCardinalDrops(fmap, pi, pj,
                    /*radius*/ 4, PEAK_DROP_THRESHOLD);
            if (steepSides < PEAK_MIN_STEEP_SIDES) continue;
            double prominenceFactor = Math.min(1.0,
                    (double) hg.prominence() / PEAK_PROMINENCE_SATURATION);
            double steepFraction = steepSides / 4.0;
            double quality = prominenceFactor * steepFraction;
            if (quality < Anchor.QUALITY_THRESHOLD) continue;
            AnchorExtent extent = bboxExtent(hg.bbox().minX(), hg.bbox().minZ(),
                    hg.bbox().maxX(), hg.bbox().maxZ());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("relativeElevation", hg.prominence());
            meta.put("steepSides", steepSides);
            meta.put("topCellCount", hg.area());
            out.add(new Anchor("", AnchorType.DEFENSIBLE_PEAK, peak, quality,
                    extent, 0.0, 0.0, meta));
        }
        return out;
    }

    /** Counts the cardinal directions (N/S/E/W) whose cell at {@code radius} drops ≥{@code threshold}. */
    private static int countSteepCardinalDrops(V2FeatureMap fmap, int ci, int cj,
                                                int radius, int threshold) {
        int n = fmap.gridSize();
        Cell here = fmap.cell(ci, cj);
        if (here == null) return 0;
        int peakY = here.elevationY();
        int count = 0;
        int[][] dirs = {{-radius, 0}, {radius, 0}, {0, -radius}, {0, radius}};
        for (int[] d : dirs) {
            int ni = ci + d[0], nj = cj + d[1];
            if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
            Cell other = fmap.cell(ni, nj);
            if (other == null) continue;
            if (peakY - other.elevationY() >= threshold) count++;
        }
        return count;
    }

    /**
     * defensible_ring: each {@link HighGround} region whose
     * core cells are mostly flat (localSlope ≤ 2) AND whose
     * perimeter cells drop by ≥{@link #RING_PERIMETER_DROP} on
     * ≥{@link #RING_PERIMETER_COMPLETENESS} of the perimeter.
     * Quality = topAreaRatio × perimeterDropCompleteness.
     */
    private static List<Anchor> detectDefensibleRing(V2FeatureMap fmap) {
        List<Anchor> out = new ArrayList<>();
        int n = fmap.gridSize();
        for (HighGround hg : fmap.highGroundRegions()) {
            int x0 = fmap.worldXToCellI(hg.bbox().minX());
            int x1 = fmap.worldXToCellI(hg.bbox().maxX());
            int z0 = fmap.worldZToCellJ(hg.bbox().minZ());
            int z1 = fmap.worldZToCellJ(hg.bbox().maxZ());
            int flatTopCells = 0;
            int perimeterCells = 0;
            int perimeterDrops = 0;
            int hgFloor = hg.peak().getY() - hg.prominence();
            for (int i = x0; i <= x1; i++) {
                for (int j = z0; j <= z1; j++) {
                    if (i < 0 || i >= n || j < 0 || j >= n) continue;
                    Cell c = fmap.cell(i, j);
                    if (c == null) continue;
                    if (c.elevationY() < hgFloor + 4) continue;
                    if (c.localSlope() <= 2) flatTopCells++;
                    boolean isPerimeter = (i == x0 || i == x1 || j == z0 || j == z1);
                    if (isPerimeter) {
                        perimeterCells++;
                        // Outside neighbour drop check.
                        int oi = i + (i == x0 ? -1 : i == x1 ? 1 : 0);
                        int oj = j + (j == z0 ? -1 : j == z1 ? 1 : 0);
                        if (oi >= 0 && oi < n && oj >= 0 && oj < n) {
                            Cell out2 = fmap.cell(oi, oj);
                            if (out2 != null
                                    && c.elevationY() - out2.elevationY() >= RING_PERIMETER_DROP) {
                                perimeterDrops++;
                            }
                        }
                    }
                }
            }
            if (flatTopCells < RING_MIN_TOP_CELLS) continue;
            if (perimeterCells == 0) continue;
            double completeness = (double) perimeterDrops / perimeterCells;
            if (completeness < RING_PERIMETER_COMPLETENESS) continue;
            double topAreaRatio = Math.min(1.0,
                    (double) flatTopCells / FLAT_FERTILE_SATURATION_CELLS);
            double quality = topAreaRatio * completeness;
            if (quality < Anchor.QUALITY_THRESHOLD) continue;
            AnchorExtent extent = bboxExtent(hg.bbox().minX(), hg.bbox().minZ(),
                    hg.bbox().maxX(), hg.bbox().maxZ());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("relativeElevation", hg.prominence());
            meta.put("flatTopCells", flatTopCells);
            meta.put("perimeterCompleteness", round3(completeness));
            out.add(new Anchor("", AnchorType.DEFENSIBLE_RING, hg.centre(), quality,
                    extent, 0.0, 0.0, meta));
        }
        return out;
    }

    /**
     * ridge_line: cells that are locally maximal along one axis
     * (both perpendicular neighbours lower by ≥{@link #RIDGE_PROMINENCE})
     * chained into linear runs. Detection scans both N-S and E-W
     * sweep directions; runs ≥{@link #RIDGE_MIN_RUN_LENGTH} cells
     * become anchors. Orientation = sweep axis as unit vector.
     */
    private static List<Anchor> detectRidgeLine(V2FeatureMap fmap) {
        return detectLinearRuns(fmap,
                /*high=*/ true,
                AnchorType.RIDGE_LINE,
                AnchorDetector::ridgeMetadata);
    }

    /**
     * valley_floor: cells that are locally minimal along one axis
     * (both perpendicular neighbours higher by ≥{@link #VALLEY_DEPTH})
     * chained into linear runs.
     */
    private static List<Anchor> detectValleyFloor(V2FeatureMap fmap) {
        return detectLinearRuns(fmap,
                /*high=*/ false,
                AnchorType.VALLEY_FLOOR,
                AnchorDetector::valleyMetadata);
    }

    private interface MetadataFn {
        Map<String, Object> build(int lengthCells, int prominence);
    }

    private static Map<String, Object> ridgeMetadata(int lengthCells, int prominence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lengthCells", lengthCells);
        m.put("prominence", prominence);
        return m;
    }

    private static Map<String, Object> valleyMetadata(int lengthCells, int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lengthCells", lengthCells);
        m.put("depth", depth);
        return m;
    }

    private static List<Anchor> detectLinearRuns(V2FeatureMap fmap, boolean high,
                                                  AnchorType type, MetadataFn metaFn) {
        int n = fmap.gridSize();
        List<Anchor> out = new ArrayList<>();
        int threshold = high ? RIDGE_PROMINENCE : VALLEY_DEPTH;
        // Sweep N-S (along Z, perp X) — runs are cells where
        // E-W neighbours drop/rise sufficiently.
        out.addAll(sweepRuns(fmap, n, /*sweepAlongZ*/ true, high, threshold, type, metaFn));
        out.addAll(sweepRuns(fmap, n, /*sweepAlongZ*/ false, high, threshold, type, metaFn));
        return out;
    }

    private static List<Anchor> sweepRuns(V2FeatureMap fmap, int n, boolean sweepAlongZ,
                                           boolean high, int threshold,
                                           AnchorType type, MetadataFn metaFn) {
        List<Anchor> out = new ArrayList<>();
        // For sweepAlongZ=true: for each i (X-column), find runs along j (Z).
        // For sweepAlongZ=false: for each j (Z-row), find runs along i (X).
        int outerMax = sweepAlongZ ? n : n;
        int innerMax = sweepAlongZ ? n : n;
        for (int outer = 0; outer < outerMax; outer++) {
            int runStart = -1;
            int runProm = 0;
            for (int inner = 0; inner < innerMax; inner++) {
                int i = sweepAlongZ ? outer : inner;
                int j = sweepAlongZ ? inner : outer;
                int prom = perpendicularProminence(fmap, i, j, sweepAlongZ, high);
                boolean qualifies = high ? prom >= threshold : prom <= -threshold;
                if (qualifies) {
                    if (runStart < 0) runStart = inner;
                    runProm = Math.max(runProm, Math.abs(prom));
                } else if (runStart >= 0) {
                    int runLen = inner - runStart;
                    if (runLen >= RIDGE_MIN_RUN_LENGTH) {
                        out.add(buildLinearAnchor(fmap, outer, runStart, inner - 1,
                                sweepAlongZ, runLen, runProm, type, metaFn));
                    }
                    runStart = -1;
                    runProm = 0;
                }
            }
            if (runStart >= 0) {
                int runLen = innerMax - runStart;
                if (runLen >= RIDGE_MIN_RUN_LENGTH) {
                    out.add(buildLinearAnchor(fmap, outer, runStart, innerMax - 1,
                            sweepAlongZ, runLen, runProm, type, metaFn));
                }
            }
        }
        return out;
    }

    /** Perpendicular prominence: cell vs perpendicular neighbours. Positive = peak; negative = trough. */
    private static int perpendicularProminence(V2FeatureMap fmap, int i, int j,
                                                boolean sweepAlongZ, boolean high) {
        int n = fmap.gridSize();
        Cell here = fmap.cell(i, j);
        if (here == null) return 0;
        // Perpendicular neighbours: if sweeping along Z, perpendicular is X (i±1).
        int dn = sweepAlongZ ? 1 : 0;
        int di = sweepAlongZ ? 1 : 0;
        int leftI  = sweepAlongZ ? i - 1 : i;
        int leftJ  = sweepAlongZ ? j     : j - 1;
        int rightI = sweepAlongZ ? i + 1 : i;
        int rightJ = sweepAlongZ ? j     : j + 1;
        if (leftI < 0 || rightI >= n || leftJ < 0 || rightJ >= n) return 0;
        Cell left  = fmap.cell(leftI,  leftJ);
        Cell right = fmap.cell(rightI, rightJ);
        if (left == null || right == null) return 0;
        // Min of two drops = guaranteed minimum prominence; for valleys,
        // min of two rises = guaranteed minimum depth (returned negative).
        int leftDelta  = here.elevationY() - left.elevationY();
        int rightDelta = here.elevationY() - right.elevationY();
        if (high) return Math.min(leftDelta, rightDelta);
        return Math.max(leftDelta, rightDelta); // for valleys, this is negative
    }

    private static Anchor buildLinearAnchor(V2FeatureMap fmap, int outer,
                                             int runStart, int runEndIncl,
                                             boolean sweepAlongZ,
                                             int lengthCells, int prominence,
                                             AnchorType type, MetadataFn metaFn) {
        // Compute centre cell.
        int midInner = (runStart + runEndIncl) / 2;
        int ci = sweepAlongZ ? outer : midInner;
        int cj = sweepAlongZ ? midInner : outer;
        BlockPos centre = fmap.cellWorldPos(ci, cj);
        // Compute extent: cells span [(outer, runStart) .. (outer, runEndIncl)]
        // for sweepAlongZ, or [(runStart, outer) .. (runEndIncl, outer)] otherwise.
        BlockPos a = sweepAlongZ
                ? fmap.cellWorldPos(outer, runStart)
                : fmap.cellWorldPos(runStart, outer);
        BlockPos b = sweepAlongZ
                ? fmap.cellWorldPos(outer, runEndIncl)
                : fmap.cellWorldPos(runEndIncl, outer);
        AnchorExtent extent = bboxExtent(a.getX(), a.getZ(), b.getX(), b.getZ());
        double dirX = sweepAlongZ ? 0.0 : 1.0;
        double dirZ = sweepAlongZ ? 1.0 : 0.0;
        double lengthFactor = Math.min(1.0, (double) lengthCells / RIDGE_LENGTH_SATURATION);
        double prominenceFactor = Math.min(1.0,
                (double) prominence / (RIDGE_PROMINENCE * 4));
        double quality = lengthFactor * prominenceFactor;
        return new Anchor("", type, centre, quality, extent, dirX, dirZ,
                metaFn.build(lengthCells, prominence));
    }

    // =========================================================================
    // Hydrology / vegetation / stone detectors
    // =========================================================================

    /**
     * cliff_face: each {@link StoneRegion} with
     * {@code avgSlope ≥ }{@link #CLIFF_AVG_SLOPE_MIN}. Quality =
     * (area/200 clamped) × (avgSlope/{@link #CLIFF_AVG_SLOPE_SATURATION} clamped).
     * Orientation: principal axis of the region's bounding box
     * (whichever is longer; ties → E-W).
     */
    private static List<Anchor> detectCliffFace(V2FeatureMap fmap) {
        List<Anchor> out = new ArrayList<>();
        for (StoneRegion sr : fmap.stoneExposedRegions()) {
            if (sr.avgSlope() < CLIFF_AVG_SLOPE_MIN) continue;
            double areaFactor = Math.min(1.0,
                    (double) sr.area() / CLIFF_AREA_SATURATION);
            double slopeFactor = Math.min(1.0,
                    (double) sr.avgSlope() / CLIFF_AVG_SLOPE_SATURATION);
            double quality = areaFactor * slopeFactor;
            if (quality < Anchor.QUALITY_THRESHOLD) continue;
            int xExt = sr.bbox().maxX() - sr.bbox().minX();
            int zExt = sr.bbox().maxZ() - sr.bbox().minZ();
            double dirX = xExt >= zExt ? 1.0 : 0.0;
            double dirZ = xExt >= zExt ? 0.0 : 1.0;
            AnchorExtent extent = bboxExtent(sr.bbox().minX(), sr.bbox().minZ(),
                    sr.bbox().maxX(), sr.bbox().maxZ());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("avgSlope", sr.avgSlope());
            meta.put("area", sr.area());
            meta.put("coreCells", sr.coreCells());
            out.add(new Anchor("", AnchorType.CLIFF_FACE, sr.centre(), quality,
                    extent, dirX, dirZ, meta));
        }
        return out;
    }

    /**
     * water_edge: cells with {@code WATER} category adjacent to
     * flat-landside (localSlope ≤ 2, OPEN/SHORE). Boundary cells
     * grouped into connected segments via flood-fill on
     * water-adjacent landside flat cells. Orientation: best-fit
     * direction of segment cells via simple X vs Z spread.
     * Quality = landside-flat-ratio × min(segmentLength/64, 1).
     */
    private static List<Anchor> detectWaterEdge(V2FeatureMap fmap) {
        int n = fmap.gridSize();
        boolean[][] visited = new boolean[n][n];
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (visited[i][j]) continue;
                if (!isWaterEdgeLandside(fmap, i, j)) continue;
                Component comp = floodFill(fmap, visited, i, j,
                        cell -> false /* per-cell predicate skipped; gated below */,
                        (fmap2, ci, cj) -> isWaterEdgeLandside(fmap2, ci, cj));
                if (comp.cellCount < 4) continue;
                int xExt = comp.maxI - comp.minI + 1;
                int zExt = comp.maxJ - comp.minJ + 1;
                double dirX = xExt >= zExt ? 1.0 : 0.0;
                double dirZ = xExt >= zExt ? 0.0 : 1.0;
                int segmentLen = Math.max(xExt, zExt);
                double lengthFactor = Math.min(1.0,
                        (double) segmentLen / EDGE_LENGTH_SATURATION);
                double flatRatio = comp.flatCount / Math.max(1.0, (double) comp.cellCount);
                double quality = lengthFactor * flatRatio;
                if (quality < Anchor.QUALITY_THRESHOLD) continue;
                AnchorExtent extent = comp.extent(fmap);
                BlockPos centre = comp.centre(fmap);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("boundaryLength", segmentLen);
                meta.put("landsideCellCount", comp.cellCount);
                meta.put("flatRatio", round3(flatRatio));
                out.add(new Anchor("", AnchorType.WATER_EDGE, centre, quality,
                        extent, dirX, dirZ, meta));
            }
        }
        return out;
    }

    private static boolean isWaterEdgeLandside(V2FeatureMap fmap, int i, int j) {
        int n = fmap.gridSize();
        Cell c = fmap.cell(i, j);
        if (c == null) return false;
        if (c.category() != BlockCategory.OPEN
                && c.category() != BlockCategory.SHORE) return false;
        if (c.localSlope() > 2) return false;
        // Has at least one WATER neighbour.
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] d : dirs) {
            int ni = i + d[0], nj = j + d[1];
            if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
            Cell nb = fmap.cell(ni, nj);
            if (nb != null && nb.category() == BlockCategory.WATER) return true;
        }
        return false;
    }

    /**
     * river_bend: water_edge candidates where the segment's
     * principal-direction reversal exceeds {@link #BEND_MIN_ANGLE_DEG}.
     * v1 heuristic: a segment whose XZ aspect ratio is near 1.0
     * (square-ish bounding box but length ≥ 8 cells) signals a
     * bend — straight river segments have skinny rectangles. Future
     * refinement: actual midpoint-angle computation.
     */
    private static List<Anchor> detectRiverBend(V2FeatureMap fmap) {
        int n = fmap.gridSize();
        boolean[][] visited = new boolean[n][n];
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (visited[i][j]) continue;
                if (!isWaterEdgeLandside(fmap, i, j)) continue;
                Component comp = floodFill(fmap, visited, i, j,
                        cell -> false,
                        (fmap2, ci, cj) -> isWaterEdgeLandside(fmap2, ci, cj));
                if (comp.cellCount < 8) continue;
                int xExt = comp.maxI - comp.minI + 1;
                int zExt = comp.maxJ - comp.minJ + 1;
                double aspectRatio = (double) Math.min(xExt, zExt)
                        / Math.max(xExt, zExt);
                if (aspectRatio < 0.5) continue; // skinny → straight river
                double bendFactor = aspectRatio; // closer to 1 = more bent
                double lengthFactor = Math.min(1.0,
                        (double) Math.max(xExt, zExt) / EDGE_LENGTH_SATURATION);
                double quality = bendFactor * lengthFactor;
                if (quality < Anchor.QUALITY_THRESHOLD) continue;
                // Orientation: average of X / Z spreads (more diagonal-ish).
                double mag = Math.sqrt((double) xExt * xExt + (double) zExt * zExt);
                double dirX = xExt / mag;
                double dirZ = zExt / mag;
                AnchorExtent extent = comp.extent(fmap);
                BlockPos centre = comp.centre(fmap);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("aspectRatio", round3(aspectRatio));
                meta.put("landsideCellCount", comp.cellCount);
                out.add(new Anchor("", AnchorType.RIVER_BEND, centre, quality,
                        extent, dirX, dirZ, meta));
            }
        }
        return out;
    }

    /**
     * island: connected OPEN/SHORE components that touch zero
     * scan-boundary cells AND are surrounded by WATER on ≥
     * {@link #ISLAND_SURROUND_COMPLETENESS} of their perimeter.
     */
    private static List<Anchor> detectIsland(V2FeatureMap fmap) {
        int n = fmap.gridSize();
        boolean[][] visited = new boolean[n][n];
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (visited[i][j]) continue;
                Cell c = fmap.cell(i, j);
                if (c == null) continue;
                if (c.category() != BlockCategory.OPEN
                        && c.category() != BlockCategory.SHORE) continue;
                Component comp = floodFill(fmap, visited, i, j,
                        cell -> cell != null
                                && (cell.category() == BlockCategory.OPEN
                                    || cell.category() == BlockCategory.SHORE));
                if (comp.touchesBoundary(n)) continue;
                if (comp.cellCount < 4) continue;
                // Surround completeness: of perimeter cells, what fraction
                // borders WATER on the outside?
                int perim = 0, waterBorder = 0;
                int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                for (int[] cell : comp.cells) {
                    for (int[] d : dirs) {
                        int ni = cell[0] + d[0], nj = cell[1] + d[1];
                        if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                        Cell nb = fmap.cell(ni, nj);
                        if (nb == null) continue;
                        if (nb.category() == BlockCategory.OPEN
                                || nb.category() == BlockCategory.SHORE) continue;
                        perim++;
                        if (nb.category() == BlockCategory.WATER) waterBorder++;
                    }
                }
                if (perim == 0) continue;
                double surround = (double) waterBorder / perim;
                if (surround < ISLAND_SURROUND_COMPLETENESS) continue;
                double areaFactor = Math.min(1.0,
                        (double) comp.cellCount / ISLAND_AREA_SATURATION);
                double quality = areaFactor * surround;
                if (quality < Anchor.QUALITY_THRESHOLD) continue;
                AnchorExtent extent = comp.extent(fmap);
                BlockPos centre = comp.centre(fmap);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("cellCount", comp.cellCount);
                meta.put("surroundCompleteness", round3(surround));
                out.add(new Anchor("", AnchorType.ISLAND, centre, quality,
                        extent, 0.0, 0.0, meta));
            }
        }
        return out;
    }

    /**
     * forest_edge: boundary cells between {@link ForestRegion}
     * cores and adjacent OPEN/SHORE flat ground. Per forest
     * region, the bounding-box perimeter cells facing open ground
     * become a segment.
     */
    private static List<Anchor> detectForestEdge(V2FeatureMap fmap) {
        int n = fmap.gridSize();
        List<Anchor> out = new ArrayList<>();
        for (ForestRegion fr : fmap.forestRegions()) {
            int x0 = fmap.worldXToCellI(fr.bbox().minX());
            int x1 = fmap.worldXToCellI(fr.bbox().maxX());
            int z0 = fmap.worldZToCellJ(fr.bbox().minZ());
            int z1 = fmap.worldZToCellJ(fr.bbox().maxZ());
            int edgeCells = 0;
            int clearAdj = 0;
            int xExt = x1 - x0 + 1;
            int zExt = z1 - z0 + 1;
            // Walk the bbox perimeter; count edge cells with at least
            // one open-flat neighbour.
            for (int i = x0; i <= x1; i++) {
                for (int j = z0; j <= z1; j++) {
                    if (i < 0 || i >= n || j < 0 || j >= n) continue;
                    boolean isPerimeter = (i == x0 || i == x1 || j == z0 || j == z1);
                    if (!isPerimeter) continue;
                    Cell c = fmap.cell(i, j);
                    if (c == null) continue;
                    if (c.category() != BlockCategory.FOREST) continue;
                    edgeCells++;
                    // Does any neighbour reach open flat?
                    int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                    for (int[] d : dirs) {
                        int ni = i + d[0], nj = j + d[1];
                        if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                        Cell nb = fmap.cell(ni, nj);
                        if (nb == null) continue;
                        if ((nb.category() == BlockCategory.OPEN
                                || nb.category() == BlockCategory.SHORE)
                                && nb.localSlope() <= 2) {
                            clearAdj++;
                            break;
                        }
                    }
                }
            }
            if (edgeCells == 0) continue;
            double lengthFactor = Math.min(1.0,
                    (double) Math.max(xExt, zExt) / EDGE_LENGTH_SATURATION);
            double clearRatio = (double) clearAdj / edgeCells;
            double quality = lengthFactor * clearRatio;
            if (quality < Anchor.QUALITY_THRESHOLD) continue;
            double dirX = xExt >= zExt ? 1.0 : 0.0;
            double dirZ = xExt >= zExt ? 0.0 : 1.0;
            AnchorExtent extent = bboxExtent(fr.bbox().minX(), fr.bbox().minZ(),
                    fr.bbox().maxX(), fr.bbox().maxZ());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("boundaryLength", Math.max(xExt, zExt));
            meta.put("clearAdjacentCount", clearAdj);
            meta.put("forestArea", fr.area());
            out.add(new Anchor("", AnchorType.FOREST_EDGE, fr.centre(), quality,
                    extent, dirX, dirZ, meta));
        }
        return out;
    }

    /**
     * natural_clearing: connected OPEN/SHORE components surrounded
     * by FOREST on ≥{@link #CLEARING_WALL_COMPLETENESS} of their
     * perimeter. Touches the scan boundary disqualifies the
     * component (open ground extending past scan is "the world,"
     * not a clearing).
     */
    private static List<Anchor> detectNaturalClearing(V2FeatureMap fmap) {
        int n = fmap.gridSize();
        boolean[][] visited = new boolean[n][n];
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (visited[i][j]) continue;
                Cell c = fmap.cell(i, j);
                if (c == null) continue;
                if (c.category() != BlockCategory.OPEN
                        && c.category() != BlockCategory.SHORE) continue;
                Component comp = floodFill(fmap, visited, i, j,
                        cell -> cell != null
                                && (cell.category() == BlockCategory.OPEN
                                    || cell.category() == BlockCategory.SHORE));
                if (comp.cellCount < CLEARING_MIN_CELLS) continue;
                if (comp.touchesBoundary(n)) continue;
                int perim = 0, forestBorder = 0;
                int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                for (int[] cell : comp.cells) {
                    for (int[] d : dirs) {
                        int ni = cell[0] + d[0], nj = cell[1] + d[1];
                        if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                        Cell nb = fmap.cell(ni, nj);
                        if (nb == null) continue;
                        if (nb.category() == BlockCategory.OPEN
                                || nb.category() == BlockCategory.SHORE) continue;
                        perim++;
                        if (nb.category() == BlockCategory.FOREST) forestBorder++;
                    }
                }
                if (perim == 0) continue;
                double wallCompleteness = (double) forestBorder / perim;
                if (wallCompleteness < CLEARING_WALL_COMPLETENESS) continue;
                double areaFactor = Math.min(1.0,
                        (double) comp.cellCount / CLEARING_AREA_SATURATION);
                double quality = areaFactor * wallCompleteness;
                if (quality < Anchor.QUALITY_THRESHOLD) continue;
                AnchorExtent extent = comp.extent(fmap);
                BlockPos centre = comp.centre(fmap);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("clearingArea", comp.cellCount);
                meta.put("wallCompleteness", round3(wallCompleteness));
                out.add(new Anchor("", AnchorType.NATURAL_CLEARING, centre, quality,
                        extent, 0.0, 0.0, meta));
            }
        }
        return out;
    }

    // =========================================================================
    // Flood-fill + helpers
    // =========================================================================

    /** Mutable connected-component accumulator used by all flood-fills. */
    private static final class Component {
        int cellCount;
        int slopeSum;
        int flatCount;
        int minI = Integer.MAX_VALUE, maxI = Integer.MIN_VALUE;
        int minJ = Integer.MAX_VALUE, maxJ = Integer.MIN_VALUE;
        long sumElevationY;
        final List<int[]> cells = new ArrayList<>();

        void add(V2FeatureMap fmap, int i, int j) {
            Cell c = fmap.cell(i, j);
            if (c == null) return;
            cellCount++;
            slopeSum += c.localSlope();
            if (c.localSlope() <= 1) flatCount++;
            sumElevationY += c.elevationY();
            if (i < minI) minI = i;
            if (i > maxI) maxI = i;
            if (j < minJ) minJ = j;
            if (j > maxJ) maxJ = j;
            cells.add(new int[] {i, j});
        }

        boolean touchesBoundary(int n) {
            return minI == 0 || maxI == n - 1 || minJ == 0 || maxJ == n - 1;
        }

        BlockPos centre(V2FeatureMap fmap) {
            int ci = (minI + maxI) / 2;
            int cj = (minJ + maxJ) / 2;
            return fmap.cellWorldPos(ci, cj);
        }

        AnchorExtent extent(V2FeatureMap fmap) {
            BlockPos nw = fmap.cellWorldPos(minI, minJ);
            BlockPos se = fmap.cellWorldPos(maxI, maxJ);
            int w = Math.max(1, se.getX() - nw.getX() + fmap.cellSize());
            int l = Math.max(1, se.getZ() - nw.getZ() + fmap.cellSize());
            return new AnchorExtent(nw.getX(), nw.getZ(), w, l);
        }
    }

    @FunctionalInterface
    private interface CellPredicate { boolean test(Cell c); }

    @FunctionalInterface
    private interface CoordPredicate { boolean test(V2FeatureMap fmap, int i, int j); }

    private static Component floodFill(V2FeatureMap fmap, boolean[][] visited,
                                        int si, int sj, CellPredicate pred) {
        return floodFill(fmap, visited, si, sj, pred, null);
    }

    /**
     * BFS flood-fill. If {@code coordPred} is supplied it overrides
     * {@code cellPred} (used by water_edge / river_bend where the
     * "is this cell qualifying" check needs the full fmap, not just
     * the cell).
     */
    private static Component floodFill(V2FeatureMap fmap, boolean[][] visited,
                                        int si, int sj, CellPredicate cellPred,
                                        CoordPredicate coordPred) {
        int n = fmap.gridSize();
        Component comp = new Component();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {si, sj});
        visited[si][sj] = true;
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int i = cur[0], j = cur[1];
            comp.add(fmap, i, j);
            int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] d : dirs) {
                int ni = i + d[0], nj = j + d[1];
                if (ni < 0 || ni >= n || nj < 0 || nj >= n) continue;
                if (visited[ni][nj]) continue;
                boolean ok;
                if (coordPred != null) ok = coordPred.test(fmap, ni, nj);
                else                   ok = cellPred.test(fmap.cell(ni, nj));
                if (!ok) continue;
                visited[ni][nj] = true;
                queue.add(new int[] {ni, nj});
            }
        }
        return comp;
    }

    private static AnchorExtent bboxExtent(int minX, int minZ, int maxX, int maxZ) {
        int w = Math.max(1, maxX - minX + 1);
        int l = Math.max(1, maxZ - minZ + 1);
        return new AnchorExtent(minX, minZ, w, l);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // =========================================================================
    // De-duplication
    // =========================================================================

    /**
     * Same-type dedup. Merges anchors when their extents overlap
     * by ≥ {@link #DEDUP_OVERLAP_FRACTION} OR their centres are
     * within {@link #DEDUP_CENTRE_DISTANCE} blocks. Survivor is the
     * higher-quality anchor; survivor's extent is unioned with the
     * losers'.
     */
    private static List<Anchor> dedupeSameType(List<Anchor> in) {
        if (in.size() < 2) return new ArrayList<>(in);
        // Greedy: sort by quality desc; for each survivor, mark
        // overlapping losers as merged into it.
        List<Anchor> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingDouble(Anchor::quality).reversed());
        boolean[] killed = new boolean[sorted.size()];
        List<Anchor> result = new ArrayList<>();
        for (int a = 0; a < sorted.size(); a++) {
            if (killed[a]) continue;
            Anchor survivor = sorted.get(a);
            AnchorExtent unionExt = survivor.extent();
            for (int b = a + 1; b < sorted.size(); b++) {
                if (killed[b]) continue;
                Anchor other = sorted.get(b);
                if (shouldMerge(survivor, other)) {
                    unionExt = unionExtents(unionExt, other.extent());
                    killed[b] = true;
                }
            }
            // Update extent if it changed.
            if (unionExt != survivor.extent()) {
                survivor = new Anchor(survivor.id(), survivor.type(),
                        survivor.centre(), survivor.quality(), unionExt,
                        survivor.dirX(), survivor.dirZ(), survivor.metadata());
            }
            result.add(survivor);
        }
        return result;
    }

    private static boolean shouldMerge(Anchor a, Anchor b) {
        BlockPos ac = a.centre(), bc = b.centre();
        long dx = ac.getX() - bc.getX();
        long dz = ac.getZ() - bc.getZ();
        if (dx * dx + dz * dz <= (long) DEDUP_CENTRE_DISTANCE * DEDUP_CENTRE_DISTANCE) {
            return true;
        }
        return a.extent().overlapFraction(b.extent()) >= DEDUP_OVERLAP_FRACTION
                || b.extent().overlapFraction(a.extent()) >= DEDUP_OVERLAP_FRACTION;
    }

    private static AnchorExtent unionExtents(AnchorExtent a, AnchorExtent b) {
        int minX = Math.min(a.originX(), b.originX());
        int minZ = Math.min(a.originZ(), b.originZ());
        int maxX = Math.max(a.originX() + a.width() - 1,
                b.originX() + b.width() - 1);
        int maxZ = Math.max(a.originZ() + a.length() - 1,
                b.originZ() + b.length() - 1);
        return new AnchorExtent(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
    }
}
