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
    /** Peak prominence: a peak's elevation must exceed the highest
     *  neighbour by at least this many blocks. */
    private static final int PEAK_MIN_PROMINENCE = 3;
    /** River aspect-ratio threshold: bbox.long / bbox.short ≥ this
     *  classifies a water component as a river rather than a lake. */
    private static final double RIVER_ASPECT_RATIO = 3.0;
    /** Coastline area threshold: a water component is a coastline
     *  source iff its area is at least this fraction of the scan
     *  area AND it touches the scan edge. */
    private static final double COASTLINE_AREA_FRACTION = 0.10;

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
    private List<BlockPos> peakPoints;
    private List<List<BlockPos>> forestEdges;
    private List<Region> stoneExposedRegions;

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
     * Cells whose elevation strictly exceeds all 8 neighbours by at
     * least {@link #PEAK_MIN_PROMINENCE} blocks.
     */
    public List<BlockPos> peakPoints() {
        if (peakPoints != null) return peakPoints;
        List<BlockPos> peaks = new ArrayList<>();
        for (int i = 1; i < gridSize - 1; i++) {
            for (int j = 1; j < gridSize - 1; j++) {
                int y = cells[i][j].elevationY();
                int maxN = Integer.MIN_VALUE;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        int ny = cells[i + di][j + dj].elevationY();
                        if (ny > maxN) maxN = ny;
                    }
                }
                if (y - maxN >= PEAK_MIN_PROMINENCE) {
                    peaks.add(cellWorldPos(i, j));
                }
            }
        }
        peakPoints = peaks;
        return peakPoints;
    }

    /**
     * Forest-edge cell groups. A cell qualifies as an edge cell if it
     * is FOREST and has at least one non-FOREST 8-neighbour. Groups
     * are 8-connected components of edge cells.
     */
    public List<List<BlockPos>> forestEdges() {
        if (forestEdges != null) return forestEdges;
        boolean[][] edgeMask = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                if (cells[i][j].category() != BlockCategory.FOREST) continue;
                boolean hasNonForest = false;
                for (int di = -1; di <= 1 && !hasNonForest; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = i + di, nj = j + dj;
                        if (ni < 0 || nj < 0 || ni >= gridSize || nj >= gridSize) continue;
                        if (cells[ni][nj].category() != BlockCategory.FOREST) {
                            hasNonForest = true;
                            break;
                        }
                    }
                }
                edgeMask[i][j] = hasNonForest;
            }
        }
        List<List<int[]>> comps = connectedComponentCells(edgeMask);
        List<List<BlockPos>> result = new ArrayList<>(comps.size());
        for (List<int[]> comp : comps) {
            List<BlockPos> chain = new ArrayList<>(comp.size());
            for (int[] c : comp) chain.add(cellWorldPos(c[0], c[1]));
            result.add(chain);
        }
        forestEdges = result;
        return forestEdges;
    }

    /** Connected components of STONE_EXPOSED cells. */
    public List<Region> stoneExposedRegions() {
        if (stoneExposedRegions != null) return stoneExposedRegions;
        boolean[][] mask = new boolean[gridSize][gridSize];
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                mask[i][j] = cells[i][j].category() == BlockCategory.STONE_EXPOSED;
            }
        }
        stoneExposedRegions = connectedComponents(mask);
        return stoneExposedRegions;
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
