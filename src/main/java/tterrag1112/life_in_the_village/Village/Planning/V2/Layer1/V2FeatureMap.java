package tterrag1112.life_in_the_village.Village.Planning.V2.Layer1;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * V2 Layer 1 — Terrain Feature Map.
 *
 * <p>Greenfield reimplementation that scans actual block state at a
 * fixed cell resolution. Independent of V1 {@code FeatureMap} (no
 * shared code, no inheritance). Produced once per candidate village
 * site at the start of placement; subsequent layers query it without
 * touching the world.
 *
 * <h3>Coordinate convention</h3>
 * <p>Cells are indexed {@code cells[i][j]} with {@code i} along world
 * X and {@code j} along world Z. The grid covers
 * {@code [centre - radius, centre + radius)} blocks on each axis,
 * with cell size {@link #CELL_SIZE} blocks. Cell {@code (i, j)}'s
 * world-space centre is
 * {@code (centre.x - radius + cellSize/2 + i*cellSize,
 *        ?,
 *        centre.z - radius + cellSize/2 + j*cellSize)}.
 *
 * <h3>Scope</h3>
 * Layer 1 is terrain-only. No biome inference, no culture, no
 * building. Aggregate queries (river, coastline, peaks, etc.) are
 * lazily computed on first call and cached.
 *
 * <h3>Performance</h3>
 * At radius 150, cellSize 2 → 22500 cells. ~30 block reads per cell
 * for forest detection plus heightmap + 8-neighbour slope. Target
 * ≤2s on the server thread; the debug command is the only caller and
 * runs synchronously.
 */
public final class V2FeatureMap {

    public static final int CELL_SIZE = 2;
    /** Forest column scan height above the surface y. */
    private static final int FOREST_COLUMN_HEIGHT = 30;
    /** Min log+leaf count in the column to classify as FOREST. */
    private static final int FOREST_MIN_TREE_BLOCKS = 3;
    /** Vertical tolerance for SHORE classification: a SHORE cell's
     *  surface y must be within this many blocks of the neighbour's
     *  observed water y. */
    private static final int SHORE_Y_TOLERANCE = 2;
    /** River aspect-ratio threshold: bbox.long / bbox.short ≥ this
     *  classifies a water component as a river rather than a lake. */
    private static final double RIVER_ASPECT_RATIO = 3.0;
    /** Coastline area threshold: a water component is a coastline
     *  source iff its area is at least this fraction of the scan
     *  area AND it touches the scan edge. */
    private static final double COASTLINE_AREA_FRACTION = 0.10;
    /** High ground: a cell qualifies if its elevation exceeds the 25th
     *  percentile of the scan elevations by at least this many blocks. */
    private static final int HIGHGROUND_PROMINENCE_THRESHOLD = 8;
    /** High ground: connected components below this cell count are
     *  treated as bumps and dropped. */
    private static final int HIGHGROUND_MIN_AREA = 4;
    /** Forest dilation radius (cells, chebyshev) — merges nearby tree
     *  clusters into single regions. */
    private static final int FOREST_DILATION_RADIUS = 3;
    /** Forest min area after dilation; smaller components are isolated
     *  trees / tiny groves and are dropped. */
    private static final int FOREST_MIN_AREA = 8;
    /** Stone-exposed dilation radius (cells, chebyshev) — tighter than
     *  forest because stone patches are more localised. */
    private static final int STONE_DILATION_RADIUS = 2;
    /** Stone-exposed min area after dilation; smaller components are
     *  natural variation / cave ceilings and are dropped. */
    private static final int STONE_MIN_AREA = 6;

    private final BlockPos centre;
    private final int radius;
    private final Cell[][] cells;
    private final int gridSize;
    private final long scanTimeMs;

    // Lazily computed aggregate caches. Each is null until first query;
    // queries populate the corresponding field. Optional.empty() is
    // used for "computed and absent" so subsequent calls don't recompute.
    private Optional<Region> largestFlatRegion;
    private Optional<List<BlockPos>> riverPath;
    private Optional<List<BlockPos>> coastline;
    private List<HighGround> highGroundRegions;
    private List<ForestRegion> forestRegions;
    private List<StoneRegion> stoneExposedRegions;

    private V2FeatureMap(BlockPos centre, int radius, Cell[][] cells, long scanMs) {
        this.centre = centre;
        this.radius = radius;
        this.cells = cells;
        this.gridSize = cells.length;
        this.scanTimeMs = scanMs;
    }

    // =========================================================================
    // Public scan entry point
    // =========================================================================

    /**
     * Scan the world around {@code centre} and produce a feature map.
     *
     * @param level  the live level (must be on the server)
     * @param centre village candidate centre
     * @param radius scan radius in blocks. Grid side = {@code 2*radius/CELL_SIZE}.
     */
    public static V2FeatureMap scan(Level level, BlockPos centre, int radius) {
        long t0 = System.currentTimeMillis();
        int gridSize = (2 * radius) / CELL_SIZE;
        Cell[][] cells = new Cell[gridSize][gridSize];

        // Pass 1: per-cell elevation + category (without slope/SHORE,
        // which need neighbour data).
        int[][] elev = new int[gridSize][gridSize];
        BlockCategory[][] cat0 = new BlockCategory[gridSize][gridSize];
        int[][] waterY = new int[gridSize][gridSize];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i < gridSize; i++) {
            int wx = cellWorldX(centre, radius, i);
            for (int j = 0; j < gridSize; j++) {
                int wz = cellWorldZ(centre, radius, j);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                elev[i][j] = y;
                cursor.set(wx, y, wz);
                BlockState surface = level.getBlockState(cursor);
                CategoryProbe probe = classifySurface(level, cursor, surface, wx, y, wz);
                cat0[i][j] = probe.category;
                waterY[i][j] = probe.waterY;
            }
        }

        // Pass 2: slope (8-neighbour max delta) and SHORE upgrade
        // (cells adjacent to water with surface y near the water y).
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                int y = elev[i][j];
                int maxSlope = 0;
                int neighbourWaterY = -1;
                boolean hasWaterNeighbour = false;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || nj < 0 || ni >= gridSize || nj >= gridSize) continue;
                        int dy = Math.abs(y - elev[ni][nj]);
                        if (dy > maxSlope) maxSlope = dy;
                        if (cat0[ni][nj] == BlockCategory.WATER) {
                            hasWaterNeighbour = true;
                            // Use the lowest seen neighbour water y in
                            // case of stepped pools — most permissive.
                            int nwy = waterY[ni][nj];
                            if (neighbourWaterY < 0 || nwy < neighbourWaterY) {
                                neighbourWaterY = nwy;
                            }
                        }
                    }
                }

                BlockCategory finalCat = cat0[i][j];
                int finalWaterY = waterY[i][j];
                if (finalCat != BlockCategory.WATER && finalCat != BlockCategory.STRUCTURE
                        && hasWaterNeighbour
                        && Math.abs(y - neighbourWaterY) <= SHORE_Y_TOLERANCE) {
                    finalCat = BlockCategory.SHORE;
                    finalWaterY = neighbourWaterY;
                }

                cells[i][j] = new Cell(y, maxSlope, finalCat, finalWaterY);
            }
        }

        // Pass 3: distance fields (multi-source BFS in cell space).
        bfsFill(cells, gridSize, BlockCategory.WATER, Cell::setDistToWater);
        bfsFill(cells, gridSize, BlockCategory.FOREST, Cell::setDistToForest);
        bfsFill(cells, gridSize, BlockCategory.STONE_EXPOSED, Cell::setDistToStone);

        long t1 = System.currentTimeMillis();
        return new V2FeatureMap(centre, radius, cells, t1 - t0);
    }

    private static int cellWorldX(BlockPos centre, int radius, int i) {
        return centre.getX() - radius + CELL_SIZE / 2 + i * CELL_SIZE;
    }

    private static int cellWorldZ(BlockPos centre, int radius, int j) {
        return centre.getZ() - radius + CELL_SIZE / 2 + j * CELL_SIZE;
    }

    // =========================================================================
    // Surface classification
    // =========================================================================

    /** Carries category + observed water y back from {@link #classifySurface}. */
    private record CategoryProbe(BlockCategory category, int waterY) {}

    private static CategoryProbe classifySurface(Level level, BlockPos.MutableBlockPos cursor,
                                                 BlockState surface, int wx, int surfaceY, int wz) {
        // STRUCTURE wins outright if surface is a culture-style block.
        if (isStructureBlock(surface)) {
            return new CategoryProbe(BlockCategory.STRUCTURE, -1);
        }

        // WATER: surface y from MOTION_BLOCKING_NO_LEAVES is the block
        // BELOW the lowest air, so the water surface is at surfaceY+1.
        // Probe one block higher first; if that's water, this column is
        // water.
        cursor.set(wx, surfaceY + 1, wz);
        BlockState above = level.getBlockState(cursor);
        if (isWaterFluid(above)) {
            return new CategoryProbe(BlockCategory.WATER, surfaceY + 1);
        }
        if (isWaterFluid(surface)) {
            return new CategoryProbe(BlockCategory.WATER, surfaceY);
        }

        // FOREST: surface is a log, OR ≥FOREST_MIN_TREE_BLOCKS log/leaf
        // blocks in the column above.
        boolean surfaceIsLog = surface.is(BlockTags.LOGS);
        if (surfaceIsLog || hasTreeColumn(level, cursor, wx, surfaceY, wz)) {
            return new CategoryProbe(BlockCategory.FOREST, -1);
        }

        // STONE_EXPOSED: surface is natural overworld stone tag.
        if (isNaturalStone(surface)) {
            return new CategoryProbe(BlockCategory.STONE_EXPOSED, -1);
        }

        return new CategoryProbe(BlockCategory.OPEN, -1);
    }

    private static boolean isWaterFluid(BlockState state) {
        if (state.is(Blocks.WATER)) return true;
        return state.getFluidState().is(FluidTags.WATER);
    }

    private static boolean isStructureBlock(BlockState state) {
        if (state.is(BlockTags.PLANKS)) return true;
        if (state.is(BlockTags.STONE_BRICKS)) return true;
        if (state.is(Blocks.COBBLESTONE)) return true;
        if (state.is(Blocks.MOSSY_COBBLESTONE)) return true;
        if (state.is(Blocks.BRICKS)) return true;
        return false;
    }

    private static boolean isNaturalStone(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    private static boolean hasTreeColumn(Level level, BlockPos.MutableBlockPos cursor,
                                         int wx, int surfaceY, int wz) {
        int hits = 0;
        int top = Math.min(level.getMaxY(), surfaceY + FOREST_COLUMN_HEIGHT);
        for (int y = surfaceY + 1; y <= top; y++) {
            cursor.set(wx, y, wz);
            BlockState st = level.getBlockState(cursor);
            if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
                hits++;
                if (hits >= FOREST_MIN_TREE_BLOCKS) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // BFS distance field
    // =========================================================================

    @FunctionalInterface
    private interface DistSetter {
        void set(Cell cell, int distance);
    }

    private static void bfsFill(Cell[][] cells, int gridSize,
                                BlockCategory source, DistSetter setter) {
        Deque<int[]> queue = new ArrayDeque<>();
        int[][] dist = new int[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                if (cells[i][j].category() == source) {
                    dist[i][j] = 0;
                    queue.add(new int[]{i, j});
                } else {
                    dist[i][j] = Integer.MAX_VALUE;
                }
            }
        }
        while (!queue.isEmpty()) {
            int[] node = queue.poll();
            int i = node[0], j = node[1];
            int d = dist[i][j];
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = i + di, nj = j + dj;
                    if (ni < 0 || nj < 0 || ni >= gridSize || nj >= gridSize) continue;
                    if (dist[ni][nj] > d + 1) {
                        dist[ni][nj] = d + 1;
                        queue.add(new int[]{ni, nj});
                    }
                }
            }
        }
        // Push results into Cell objects.
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                setter.set(cells[i][j], dist[i][j]);
            }
        }
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    public BlockPos centre() { return centre; }
    public int radius() { return radius; }
    public int cellSize() { return CELL_SIZE; }
    public int gridSize() { return gridSize; }
    public long scanTimeMs() { return scanTimeMs; }
    public Cell cell(int i, int j) { return cells[i][j]; }

    /** Returns the world-space {@link BlockPos} of cell (i, j)'s centre. */
    public BlockPos cellWorldPos(int i, int j) {
        return new BlockPos(
                cellWorldX(centre, radius, i),
                cells[i][j].elevationY(),
                cellWorldZ(centre, radius, j));
    }

    /** Returns the cell index along the X axis containing world {@code worldX},
     *  clamped to the grid range. */
    public int worldXToCellI(int worldX) {
        int i = (worldX - (centre.getX() - radius)) / CELL_SIZE;
        return Math.max(0, Math.min(gridSize - 1, i));
    }

    /** Returns the cell index along the Z axis containing world {@code worldZ},
     *  clamped to the grid range. */
    public int worldZToCellJ(int worldZ) {
        int j = (worldZ - (centre.getZ() - radius)) / CELL_SIZE;
        return Math.max(0, Math.min(gridSize - 1, j));
    }

    /** Returns the cell containing world {@code (worldX, worldZ)},
     *  clamped to grid edges if the position is outside the scan area. */
    public Cell cellAt(int worldX, int worldZ) {
        return cells[worldXToCellI(worldX)][worldZToCellJ(worldZ)];
    }

    /** Convenience: surface y at the cell containing {@code (worldX, worldZ)}. */
    public int surfaceYAt(int worldX, int worldZ) {
        return cellAt(worldX, worldZ).elevationY();
    }

    /** Returns counts of cells per category, in declaration order. */
    public int[] categoryCounts() {
        int[] counts = new int[BlockCategory.values().length];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                counts[cells[i][j].category().ordinal()]++;
            }
        }
        return counts;
    }

    // =========================================================================
    // Aggregate queries (lazy)
    // =========================================================================

    /**
     * Largest connected component of cells with category ∈
     * {OPEN, SHORE} and {@code localSlope ≤ 1}. Empty if no such
     * cell exists.
     */
    public Optional<Region> largestFlatRegion() {
        if (largestFlatRegion != null) return largestFlatRegion;
        boolean[][] mask = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                BlockCategory c = cells[i][j].category();
                mask[i][j] = (c == BlockCategory.OPEN || c == BlockCategory.SHORE)
                        && cells[i][j].localSlope() <= 1;
            }
        }
        List<Region> regions = connectedComponents(mask);
        Region best = null;
        for (Region r : regions) {
            if (best == null || r.area() > best.area()) best = r;
        }
        largestFlatRegion = Optional.ofNullable(best);
        return largestFlatRegion;
    }

    /**
     * Ordered cell-centre {@link BlockPos}es along the longest river
     * detected in the scan. A water component qualifies as a river
     * iff its bounding box aspect ratio ≥ {@link #RIVER_ASPECT_RATIO}.
     * Empty if no qualifying component exists.
     */
    public Optional<List<BlockPos>> riverPath() {
        if (riverPath != null) return riverPath;
        boolean[][] waterMask = waterMask();
        List<List<int[]>> components = connectedComponentCells(waterMask);
        List<int[]> bestRiver = null;
        int bestLength = 0;
        for (List<int[]> comp : components) {
            int[] bbox = bboxOf(comp);
            int extX = bbox[2] - bbox[0] + 1;
            int extZ = bbox[3] - bbox[1] + 1;
            int longSide = Math.max(extX, extZ);
            int shortSide = Math.max(1, Math.min(extX, extZ));
            if ((double) longSide / shortSide < RIVER_ASPECT_RATIO) continue;
            if (longSide > bestLength) {
                bestLength = longSide;
                bestRiver = comp;
            }
        }
        if (bestRiver == null) {
            riverPath = Optional.empty();
            return riverPath;
        }
        List<BlockPos> path = traceLinearComponent(bestRiver, waterMask);
        riverPath = Optional.of(path);
        return riverPath;
    }

    /**
     * Boundary cells of the largest water component that touches the
     * scan edge AND has area ≥ {@link #COASTLINE_AREA_FRACTION} of the
     * total scan area. The boundary cells are the non-water cells
     * 8-connected to that component.
     */
    public Optional<List<BlockPos>> coastline() {
        if (coastline != null) return coastline;
        boolean[][] waterMask = waterMask();
        List<List<int[]>> components = connectedComponentCells(waterMask);
        int totalArea = gridSize * gridSize;
        int areaThreshold = (int) Math.round(COASTLINE_AREA_FRACTION * totalArea);
        List<int[]> seaComp = null;
        for (List<int[]> comp : components) {
            if (comp.size() < areaThreshold) continue;
            if (!touchesEdge(comp, gridSize)) continue;
            if (seaComp == null || comp.size() > seaComp.size()) seaComp = comp;
        }
        if (seaComp == null) {
            coastline = Optional.empty();
            return coastline;
        }
        // Boundary: any non-water cell 8-connected to a cell in seaComp.
        boolean[][] inSea = new boolean[gridSize][gridSize];
        for (int[] c : seaComp) inSea[c[0]][c[1]] = true;
        List<BlockPos> boundary = new ArrayList<>();
        boolean[][] picked = new boolean[gridSize][gridSize];
        for (int[] c : seaComp) {
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = c[0] + di, nj = c[1] + dj;
                    if (ni < 0 || nj < 0 || ni >= gridSize || nj >= gridSize) continue;
                    if (inSea[ni][nj] || picked[ni][nj]) continue;
                    picked[ni][nj] = true;
                    boundary.add(cellWorldPos(ni, nj));
                }
            }
        }
        coastline = Optional.of(boundary);
        return coastline;
    }

    /**
     * Regions of elevated terrain ("hills"). Built by:
     * <ol>
     *   <li>Computing a baseline = 25th percentile of all cell
     *       elevations in the scan (robust against a few high points
     *       dragging the median up).</li>
     *   <li>Marking cells with {@code elevationY > baseline +
     *       HIGHGROUND_PROMINENCE_THRESHOLD} as highground.</li>
     *   <li>Taking 8-connected components.</li>
     *   <li>Dropping components smaller than {@link #HIGHGROUND_MIN_AREA}
     *       cells (these are bumps, not hills).</li>
     * </ol>
     */
    public List<HighGround> highGroundRegions() {
        if (highGroundRegions != null) return highGroundRegions;

        int n = gridSize * gridSize;
        int[] elevs = new int[n];
        int k = 0;
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                elevs[k++] = cells[i][j].elevationY();
            }
        }
        Arrays.sort(elevs);
        int baseline = elevs[n / 4];

        boolean[][] mask = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                mask[i][j] = cells[i][j].elevationY()
                        > baseline + HIGHGROUND_PROMINENCE_THRESHOLD;
            }
        }

        List<List<int[]>> comps = connectedComponentCells(mask);
        List<HighGround> out = new ArrayList<>();
        for (List<int[]> comp : comps) {
            if (comp.size() < HIGHGROUND_MIN_AREA) continue;

            int[] peakCell = comp.get(0);
            int peakY = cells[peakCell[0]][peakCell[1]].elevationY();
            for (int[] c : comp) {
                int y = cells[c[0]][c[1]].elevationY();
                if (y > peakY) { peakY = y; peakCell = c; }
            }
            BlockPos peak = cellWorldPos(peakCell[0], peakCell[1]);

            long si = 0, sj = 0;
            for (int[] c : comp) { si += c[0]; sj += c[1]; }
            int ci = (int) (si / comp.size());
            int cj = (int) (sj / comp.size());
            BlockPos centroid = cellWorldPos(ci, cj);

            int[] bb = bboxOf(comp);
            BoundingBox bbox = bboxToWorld(bb);

            int prominence = peakY - baseline;
            out.add(new HighGround(peak, centroid, comp.size(), bbox, prominence));
        }
        highGroundRegions = out;
        return highGroundRegions;
    }

    /**
     * Coherent forest patches. Built by dilating {@code FOREST} cells
     * by {@link #FOREST_DILATION_RADIUS} cells (chebyshev) so nearby
     * tree clusters merge, then taking 8-connected components and
     * dropping any with fewer than {@link #FOREST_MIN_AREA} cells.
     */
    public List<ForestRegion> forestRegions() {
        if (forestRegions != null) return forestRegions;

        boolean[][] core = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                core[i][j] = cells[i][j].category() == BlockCategory.FOREST;
            }
        }
        boolean[][] dilated = dilate(core, FOREST_DILATION_RADIUS);

        List<List<int[]>> comps = connectedComponentCells(dilated);
        List<ForestRegion> out = new ArrayList<>();
        for (List<int[]> comp : comps) {
            if (comp.size() < FOREST_MIN_AREA) continue;

            long si = 0, sj = 0;
            int coreCells = 0;
            for (int[] c : comp) {
                si += c[0]; sj += c[1];
                if (core[c[0]][c[1]]) coreCells++;
            }
            int ci = (int) (si / comp.size());
            int cj = (int) (sj / comp.size());
            BlockPos centroid = cellWorldPos(ci, cj);

            int[] bb = bboxOf(comp);
            BoundingBox bbox = bboxToWorld(bb);

            out.add(new ForestRegion(centroid, comp.size(), coreCells, bbox));
        }
        forestRegions = out;
        return forestRegions;
    }

    /**
     * Coherent regions of exposed natural stone. Built by dilating
     * {@code STONE_EXPOSED} cells by {@link #STONE_DILATION_RADIUS}
     * cells (smaller than forest — stone is more localised), then
     * taking 8-connected components and dropping any with fewer than
     * {@link #STONE_MIN_AREA} cells.
     *
     * <p>{@code avgSlope} captures the mean local slope across the
     * core (un-dilated) cells so future cliff classification can
     * distinguish flat exposed bedrock from cliff faces without
     * re-scanning.
     */
    public List<StoneRegion> stoneExposedRegions() {
        if (stoneExposedRegions != null) return stoneExposedRegions;

        boolean[][] core = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                core[i][j] = cells[i][j].category() == BlockCategory.STONE_EXPOSED;
            }
        }
        boolean[][] dilated = dilate(core, STONE_DILATION_RADIUS);

        List<List<int[]>> comps = connectedComponentCells(dilated);
        List<StoneRegion> out = new ArrayList<>();
        for (List<int[]> comp : comps) {
            if (comp.size() < STONE_MIN_AREA) continue;

            long si = 0, sj = 0;
            int coreCells = 0;
            long slopeSum = 0;
            for (int[] c : comp) {
                si += c[0]; sj += c[1];
                if (core[c[0]][c[1]]) {
                    coreCells++;
                    slopeSum += cells[c[0]][c[1]].localSlope();
                }
            }
            int ci = (int) (si / comp.size());
            int cj = (int) (sj / comp.size());
            BlockPos centroid = cellWorldPos(ci, cj);

            int[] bb = bboxOf(comp);
            BoundingBox bbox = bboxToWorld(bb);

            int avgSlope = coreCells > 0 ? (int) (slopeSum / coreCells) : 0;
            out.add(new StoneRegion(centroid, comp.size(), coreCells, bbox, avgSlope));
        }
        stoneExposedRegions = out;
        return stoneExposedRegions;
    }

    /**
     * Dilate {@code mask} by {@code radius} cells (chebyshev) — every
     * cell within that range of a true cell becomes true. Implemented
     * as a stamping pass over the source true cells, which is cheap
     * when {@code mask} is sparse.
     */
    private boolean[][] dilate(boolean[][] mask, int radius) {
        boolean[][] out = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                if (!mask[i][j]) continue;
                int i0 = Math.max(0, i - radius);
                int i1 = Math.min(gridSize - 1, i + radius);
                int j0 = Math.max(0, j - radius);
                int j1 = Math.min(gridSize - 1, j + radius);
                for (int ii = i0; ii <= i1; ii++) {
                    for (int jj = j0; jj <= j1; jj++) {
                        out[ii][jj] = true;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Convert a cell-space bbox {@code {minI, minJ, maxI, maxJ}} into
     * a world-space {@link BoundingBox} covering the full block extent
     * of those cells.
     */
    private BoundingBox bboxToWorld(int[] cellBbox) {
        int worldMinX = cellWorldX(centre, radius, cellBbox[0]) - CELL_SIZE / 2;
        int worldMinZ = cellWorldZ(centre, radius, cellBbox[1]) - CELL_SIZE / 2;
        int worldMaxX = cellWorldX(centre, radius, cellBbox[2]) + CELL_SIZE / 2 - 1;
        int worldMaxZ = cellWorldZ(centre, radius, cellBbox[3]) + CELL_SIZE / 2 - 1;
        return new BoundingBox(worldMinX, worldMinZ, worldMaxX, worldMaxZ);
    }

    // =========================================================================
    // Connected-component helpers
    // =========================================================================

    private boolean[][] waterMask() {
        boolean[][] mask = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                mask[i][j] = cells[i][j].category() == BlockCategory.WATER;
            }
        }
        return mask;
    }

    private List<Region> connectedComponents(boolean[][] mask) {
        List<List<int[]>> comps = connectedComponentCells(mask);
        List<Region> out = new ArrayList<>(comps.size());
        for (List<int[]> comp : comps) {
            int[] bbox = bboxOf(comp);
            // Centroid in cell space (rounded), then convert to world.
            long sumI = 0, sumJ = 0;
            for (int[] c : comp) { sumI += c[0]; sumJ += c[1]; }
            int ci = (int) (sumI / comp.size());
            int cj = (int) (sumJ / comp.size());
            BlockPos centroid = cellWorldPos(ci, cj);
            int worldMinX = cellWorldX(centre, radius, bbox[0]) - CELL_SIZE / 2;
            int worldMinZ = cellWorldZ(centre, radius, bbox[1]) - CELL_SIZE / 2;
            int worldMaxX = cellWorldX(centre, radius, bbox[2]) + CELL_SIZE / 2 - 1;
            int worldMaxZ = cellWorldZ(centre, radius, bbox[3]) + CELL_SIZE / 2 - 1;
            out.add(new Region(centroid, comp.size(),
                    worldMinX, worldMinZ, worldMaxX, worldMaxZ));
        }
        return out;
    }

    /** 8-connected components of {@code mask}. Returns lists of (i, j) cell coords. */
    private List<List<int[]>> connectedComponentCells(boolean[][] mask) {
        boolean[][] visited = new boolean[gridSize][gridSize];
        List<List<int[]>> out = new ArrayList<>();
        for (int i0 = 0; i0 < gridSize; i0++) {
            for (int j0 = 0; j0 < gridSize; j0++) {
                if (!mask[i0][j0] || visited[i0][j0]) continue;
                List<int[]> comp = new ArrayList<>();
                Deque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[]{i0, j0});
                visited[i0][j0] = true;
                while (!stack.isEmpty()) {
                    int[] node = stack.pop();
                    comp.add(node);
                    for (int di = -1; di <= 1; di++) {
                        for (int dj = -1; dj <= 1; dj++) {
                            if (di == 0 && dj == 0) continue;
                            int ni = node[0] + di, nj = node[1] + dj;
                            if (ni < 0 || nj < 0 || ni >= gridSize || nj >= gridSize) continue;
                            if (visited[ni][nj] || !mask[ni][nj]) continue;
                            visited[ni][nj] = true;
                            stack.push(new int[]{ni, nj});
                        }
                    }
                }
                out.add(comp);
            }
        }
        return out;
    }

    /** Returns {minI, minJ, maxI, maxJ} in cell space. */
    private static int[] bboxOf(List<int[]> comp) {
        int minI = Integer.MAX_VALUE, minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE, maxJ = Integer.MIN_VALUE;
        for (int[] c : comp) {
            if (c[0] < minI) minI = c[0];
            if (c[1] < minJ) minJ = c[1];
            if (c[0] > maxI) maxI = c[0];
            if (c[1] > maxJ) maxJ = c[1];
        }
        return new int[]{minI, minJ, maxI, maxJ};
    }

    private static boolean touchesEdge(List<int[]> comp, int gridSize) {
        for (int[] c : comp) {
            if (c[0] == 0 || c[1] == 0
                    || c[0] == gridSize - 1 || c[1] == gridSize - 1) return true;
        }
        return false;
    }

    /**
     * Trace a near-linear component end-to-end. Picks a degree-1
     * endpoint (a cell with only one component-neighbour); if none
     * exists, picks the cell with smallest (i+j) as a stable start.
     * Walks greedily to the unvisited neighbour furthest from the
     * start, building an ordered path. Suitable for rivers; branchy
     * components yield a single branch.
     */
    private List<BlockPos> traceLinearComponent(List<int[]> comp, boolean[][] mask) {
        boolean[][] inComp = new boolean[gridSize][gridSize];
        for (int[] c : comp) inComp[c[0]][c[1]] = true;

        int[] start = null;
        for (int[] c : comp) {
            if (countNeighbours(c[0], c[1], inComp) == 1) {
                start = c;
                break;
            }
        }
        if (start == null) {
            int[] best = comp.get(0);
            for (int[] c : comp) {
                if (c[0] + c[1] < best[0] + best[1]) best = c;
            }
            start = best;
        }

        List<BlockPos> path = new ArrayList<>();
        boolean[][] visited = new boolean[gridSize][gridSize];
        int[] cur = start;
        visited[cur[0]][cur[1]] = true;
        path.add(cellWorldPos(cur[0], cur[1]));
        while (true) {
            int[] next = null;
            int bestDist2 = -1;
            for (int di = -1; di <= 1; di++) {
                for (int dj = -1; dj <= 1; dj++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = cur[0] + di, nj = cur[1] + dj;
                    if (ni < 0 || nj < 0 || ni >= gridSize || nj >= gridSize) continue;
                    if (!inComp[ni][nj] || visited[ni][nj]) continue;
                    int ddi = ni - start[0];
                    int ddj = nj - start[1];
                    int d2 = ddi * ddi + ddj * ddj;
                    if (d2 > bestDist2) {
                        bestDist2 = d2;
                        next = new int[]{ni, nj};
                    }
                }
            }
            if (next == null) break;
            visited[next[0]][next[1]] = true;
            path.add(cellWorldPos(next[0], next[1]));
            cur = next;
        }
        return path;
    }

    private static int countNeighbours(int i, int j, boolean[][] mask) {
        int count = 0;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                int ni = i + di, nj = j + dj;
                if (ni < 0 || nj < 0 || ni >= mask.length || nj >= mask[0].length) continue;
                if (mask[ni][nj]) count++;
            }
        }
        return count;
    }
}
