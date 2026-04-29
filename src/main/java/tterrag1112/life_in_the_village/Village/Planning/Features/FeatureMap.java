package tterrag1112.life_in_the_village.Village.Planning.Features;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The village's geometric feature inventory: hull, plaza polygons, water
 * features, cliff features, reservations.
 *
 * <h3>Phase 4 status</h3>
 * Two-pass build:
 * <ol>
 *   <li>{@link #buildPlanning(BlockPos, long, ServerLevel, List)} — runs at
 *       planning time. Uses only noise-only primitives via
 *       {@link FeatureSamplers}; results are deterministic from
 *       {@code (worldSeed, origin)}, independent of chunk-load order.</li>
 *   <li>{@link #refine(ServerLevel)} — runs at realization. Updates Y
 *       values from the live heightmap but never reshapes XZ. Returns a
 *       new map; the planning map stays immutable.</li>
 * </ol>
 *
 * <p>Recipes still don't consume from this map in Phase 4. Phase 8+
 * begins the migration.
 */
public final class FeatureMap {

    /** Distance (blocks) above which {@link #refine} logs a Y-drift warning. */
    public static final int Y_DRIFT_WARN_BLOCKS = 4;

    /** Side length (in samples) of the planning-time coarse grid. */
    private static final int GRID_SIZE = 16;
    /** Spacing (in blocks) between coarse-grid samples. */
    private static final int SAMPLE_STEP = 8;
    /** Y delta (blocks) above the origin Y for a cell to count as ridge. */
    private static final int RIDGE_THRESHOLD = 6;
    /** Minimum cluster size in coarse-grid cells for a water feature. */
    private static final int MIN_WATER_CELLS = 4;
    /** Minimum cluster size in coarse-grid cells for a cliff feature. */
    private static final int MIN_CLIFF_CELLS = 3;

    private PolygonXZ hull;
    private final List<PolygonXZ>      plazaPolygons = new ArrayList<>();
    private final List<WaterFeature>   waterFeatures = new ArrayList<>();
    private final List<CliffFeature>   cliffFeatures = new ArrayList<>();
    private final List<ReservedRegion> reservations  = new ArrayList<>();

    /**
     * Builds the planning-time feature map using only load-order-independent
     * noise primitives. Samples a {@value #GRID_SIZE}×{@value #GRID_SIZE}
     * coarse grid centered on {@code origin} at {@value #SAMPLE_STEP}-block
     * spacing, classifies each cell as water / ridge / flat via
     * {@link FeatureSamplers}, then builds polygons by clustering.
     *
     * <p>{@code worldSeed} is unused for sampling (noise primitives derive
     * their seed from the level itself) but is part of the contract so
     * future implementations can fold it into deterministic helpers
     * without an API break.
     *
     * @param origin     village origin (block position)
     * @param worldSeed  world seed; reserved for future deterministic helpers
     * @param level      server level — accessed only via {@link FeatureSamplers}
     * @param remaining  starter buildings (used for hull margin estimate)
     */
    public static FeatureMap buildPlanning(BlockPos origin,
                                           long worldSeed,
                                           ServerLevel level,
                                           List<VillageTypeData.StarterBuilding> remaining) {
        FeatureMap fm = new FeatureMap();

        int half = (GRID_SIZE * SAMPLE_STEP) / 2;
        int originX = origin.getX();
        int originZ = origin.getZ();
        int originY = FeatureSamplers.predictedSurfaceY(level, originX, originZ);

        // Sample once per grid cell. Indexing: grid[i][j] where
        // i = 0..GRID_SIZE-1 maps to X = originX - half + i*SAMPLE_STEP
        // j = 0..GRID_SIZE-1 maps to Z = originZ - half + j*SAMPLE_STEP
        int[][]   y     = new int[GRID_SIZE][GRID_SIZE];
        boolean[][] water = new boolean[GRID_SIZE][GRID_SIZE];
        boolean[][] cliff = new boolean[GRID_SIZE][GRID_SIZE];

        for (int i = 0; i < GRID_SIZE; i++) {
            for (int j = 0; j < GRID_SIZE; j++) {
                int bx = originX - half + i * SAMPLE_STEP;
                int bz = originZ - half + j * SAMPLE_STEP;
                y[i][j] = FeatureSamplers.predictedSurfaceY(level, bx, bz);
                water[i][j] = FeatureSamplers.isPredictedWater(level, bx, bz);
                if (!water[i][j]
                        && FeatureSamplers.predictedYDelta(level, originX, originZ, bx, bz)
                                >= RIDGE_THRESHOLD) {
                    cliff[i][j] = true;
                }
            }
        }

        // ── Hull ─────────────────────────────────────────────────────────
        List<BlockPos> flatPoints = new ArrayList<>();
        for (int i = 0; i < GRID_SIZE; i++) {
            for (int j = 0; j < GRID_SIZE; j++) {
                if (water[i][j] || cliff[i][j]) continue;
                int bx = originX - half + i * SAMPLE_STEP;
                int bz = originZ - half + j * SAMPLE_STEP;
                flatPoints.add(new BlockPos(bx, y[i][j], bz));
            }
        }
        if (flatPoints.size() >= 3) {
            PolygonXZ raw = PolygonXZ.convexHull(flatPoints);
            int margin = estimateHullMargin(remaining);
            fm.hull = raw.inflate(margin);
        } else {
            int r = 64;
            fm.hull = new PolygonXZ(List.of(
                new BlockPos(originX - r, originY, originZ - r),
                new BlockPos(originX + r, originY, originZ - r),
                new BlockPos(originX + r, originY, originZ + r),
                new BlockPos(originX - r, originY, originZ + r)
            ));
        }

        // ── Water features (cluster water cells via flood-fill) ──────────
        for (Cluster c : findClusters(water)) {
            if (c.cells.size() < MIN_WATER_CELLS) continue;
            int[] bbox = clusterBBoxBlocks(c, originX, originZ, half);
            int cellCount = c.cells.size();
            // Centroid in block coordinates: average of cluster cells
            long sx = 0, sz = 0; int sumY = 0;
            for (int[] cell : c.cells) {
                int bx = originX - half + cell[0] * SAMPLE_STEP;
                int bz = originZ - half + cell[1] * SAMPLE_STEP;
                sx += bx; sz += bz;
                sumY += y[cell[0]][cell[1]];
            }
            int cx = (int)(sx / cellCount);
            int cz = (int)(sz / cellCount);
            int avgY = sumY / cellCount;
            BlockPos centroid = new BlockPos(cx, avgY, cz);
            PolygonXZ outline = bboxPolygon(bbox[0], bbox[1], bbox[2], bbox[3], avgY);
            WaterKind kind = inferKindFromArea(cellCount);
            fm.waterFeatures.add(new WaterFeature(outline, avgY, kind, centroid));
        }

        // ── Cliff features (cluster ridge cells) ────────────────────────
        for (Cluster c : findClusters(cliff)) {
            if (c.cells.size() < MIN_CLIFF_CELLS) continue;
            int[] bbox = clusterBBoxBlocks(c, originX, originZ, half);
            int topY = Integer.MIN_VALUE, baseY = Integer.MAX_VALUE;
            for (int[] cell : c.cells) {
                int yy = y[cell[0]][cell[1]];
                if (yy > topY)  topY = yy;
                if (yy < baseY) baseY = yy;
            }
            // baseY in cliff features represents the surrounding terrain
            // near the cliff base; clamp to origin Y so a tall cluster's
            // own minimum doesn't replace the foot-of-cliff height.
            int finalBaseY = Math.min(baseY, originY);
            PolygonXZ rect = bboxPolygon(bbox[0], bbox[1], bbox[2], bbox[3], topY);
            List<BlockPos> walk = List.of(
                new BlockPos(bbox[0], topY, bbox[1]),
                new BlockPos(bbox[2], topY, bbox[1]),
                new BlockPos(bbox[2], topY, bbox[3]),
                new BlockPos(bbox[0], topY, bbox[3])
            );
            fm.cliffFeatures.add(new CliffFeature(rect, topY, finalBaseY, walk));
        }

        return fm;
    }

    /**
     * Realization-time refinement: returns a new map with identical XZ
     * polygons but Y values snapped to the live heightmap. Logs warnings
     * when the predicted Y drifts more than {@link #Y_DRIFT_WARN_BLOCKS}
     * from the realized Y, but never reshapes XZ.
     */
    public FeatureMap refine(ServerLevel level) {
        FeatureMap out = new FeatureMap();

        if (hull != null) {
            out.hull = refineY(hull, level);
        }
        for (PolygonXZ p : plazaPolygons) {
            out.plazaPolygons.add(refineY(p, level));
        }
        for (ReservedRegion r : reservations) {
            out.reservations.add(new ReservedRegion(refineY(r.shape(), level),
                    r.kind(), r.ownerSectorId()));
        }

        for (WaterFeature wf : waterFeatures) {
            PolygonXZ outline = refineY(wf.outline(), level);
            BlockPos cen = wf.centroid();
            int hmY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    cen.getX(), cen.getZ());
            BlockState st = level.getBlockState(
                    new BlockPos(cen.getX(), Math.max(0, hmY - 1), cen.getZ()));
            boolean isWaterBlock = st.is(Blocks.WATER) || !st.getFluidState().isEmpty();
            int waterY;
            if (isWaterBlock) {
                waterY = hmY - 1;
                if (Math.abs(waterY - wf.waterY()) > Y_DRIFT_WARN_BLOCKS) {
                    System.out.println("[FeatureMap.refine] Y drift "
                            + (waterY - wf.waterY()) + " for water centroid "
                            + cen + " (kind=" + wf.kind() + ")");
                }
            } else {
                System.out.println("[FeatureMap.refine] water centroid not water at "
                        + cen + " — keeping planning Y=" + wf.waterY());
                waterY = wf.waterY();
            }
            out.waterFeatures.add(new WaterFeature(outline, waterY, wf.kind(),
                    new BlockPos(cen.getX(), waterY, cen.getZ())));
        }

        for (CliffFeature cf : cliffFeatures) {
            PolygonXZ rect = refineY(cf.ridgeFootprint(), level);
            int cx = (rect.minX() + rect.maxX()) / 2;
            int cz = (rect.minZ() + rect.maxZ()) / 2;
            int newTop = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
            int newBase = Math.min(
                Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rect.minX(), rect.minZ()),
                         level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rect.maxX(), rect.minZ())),
                Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rect.minX(), rect.maxZ()),
                         level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, rect.maxX(), rect.maxZ()))
            );
            List<BlockPos> walk = new ArrayList<>(cf.edgeWalk().size());
            for (BlockPos w : cf.edgeWalk()) {
                int wy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        w.getX(), w.getZ());
                walk.add(new BlockPos(w.getX(), wy, w.getZ()));
            }
            if (Math.abs(newTop - cf.topY()) > Y_DRIFT_WARN_BLOCKS) {
                System.out.println("[FeatureMap.refine] Y drift "
                        + (newTop - cf.topY()) + " for cliff top at ("
                        + cx + "," + cz + ")");
            }
            out.cliffFeatures.add(new CliffFeature(rect, newTop, newBase, walk));
        }

        return out;
    }

    // ── Queries ──────────────────────────────────────────────────────────

    public PolygonXZ hull() { return hull; }
    public List<PolygonXZ>      plazaPolygons() { return Collections.unmodifiableList(plazaPolygons); }
    public List<WaterFeature>   waterFeatures() { return Collections.unmodifiableList(waterFeatures); }
    public List<CliffFeature>   cliffFeatures() { return Collections.unmodifiableList(cliffFeatures); }
    public List<ReservedRegion> reservations()  { return Collections.unmodifiableList(reservations); }

    public boolean isInsideHull(BlockPos pos) {
        return hull != null && hull.contains(pos);
    }

    public boolean isOnWater(BlockPos pos) {
        for (WaterFeature wf : waterFeatures) {
            if (wf.outline().contains(pos)) return true;
        }
        return false;
    }

    public boolean isOnCliff(BlockPos pos) {
        for (CliffFeature cf : cliffFeatures) {
            if (cf.ridgeFootprint().contains(pos)) return true;
        }
        return false;
    }

    public boolean isClearForFootprint(BlockPos centre, int w, int l) {
        int hw = w / 2, hl = l / 2;
        BlockPos[] corners = {
            centre.offset(-hw, 0, -hl), centre.offset( hw, 0, -hl),
            centre.offset( hw, 0,  hl), centre.offset(-hw, 0,  hl)
        };
        for (BlockPos c : corners) {
            if (isOnWater(c) || isOnCliff(c)) return false;
            for (ReservedRegion r : reservations) {
                if (r.shape().contains(c)) return false;
            }
        }
        return true;
    }

    public PolygonXZ.Edge nearestShoreEdge(BlockPos pos) {
        PolygonXZ.Edge best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (WaterFeature wf : waterFeatures) {
            PolygonXZ.Edge e = wf.outline().nearestEdge(pos.getX(), pos.getZ());
            double d = wf.outline().distanceSqToEdge(pos.getX(), pos.getZ());
            if (d < bestDistSq) { bestDistSq = d; best = e; }
        }
        return best;
    }

    public PolygonXZ.Edge nearestCliffEdge(BlockPos pos) {
        PolygonXZ.Edge best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (CliffFeature cf : cliffFeatures) {
            PolygonXZ.Edge e = cf.ridgeFootprint().nearestEdge(pos.getX(), pos.getZ());
            double d = cf.ridgeFootprint().distanceSqToEdge(pos.getX(), pos.getZ());
            if (d < bestDistSq) { bestDistSq = d; best = e; }
        }
        return best;
    }

    public boolean isReserved(BlockPos centre, int w, int l) {
        int hw = w / 2, hl = l / 2;
        BlockPos[] corners = {
            centre.offset(-hw, 0, -hl), centre.offset( hw, 0, -hl),
            centre.offset( hw, 0,  hl), centre.offset(-hw, 0,  hl)
        };
        for (BlockPos c : corners) {
            for (ReservedRegion r : reservations) {
                if (r.shape().contains(c)) return true;
            }
        }
        return false;
    }

    // ── Mutators ─────────────────────────────────────────────────────────

    public void addPlazaPolygon(PolygonXZ p)     { plazaPolygons.add(p); }
    public void addReservation(ReservedRegion r) { reservations.add(r); }
    public void setHull(PolygonXZ h)             { this.hull = h; }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static int estimateHullMargin(List<VillageTypeData.StarterBuilding> remaining) {
        return 16;
    }

    private static WaterKind inferKindFromArea(int cellCount) {
        if (cellCount > 32) return WaterKind.OCEAN;
        if (cellCount > 12) return WaterKind.LAKE;
        return WaterKind.RIVER;
    }

    /** Cluster of contiguous true cells from a boolean grid, found by 4-way flood-fill. */
    private static final class Cluster {
        final List<int[]> cells = new ArrayList<>();
    }

    private static List<Cluster> findClusters(boolean[][] grid) {
        boolean[][] seen = new boolean[GRID_SIZE][GRID_SIZE];
        List<Cluster> out = new ArrayList<>();
        for (int i = 0; i < GRID_SIZE; i++) {
            for (int j = 0; j < GRID_SIZE; j++) {
                if (!grid[i][j] || seen[i][j]) continue;
                Cluster c = new Cluster();
                Deque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[]{i, j});
                seen[i][j] = true;
                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    c.cells.add(p);
                    for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        int ni = p[0] + d[0], nj = p[1] + d[1];
                        if (ni < 0 || ni >= GRID_SIZE || nj < 0 || nj >= GRID_SIZE) continue;
                        if (!grid[ni][nj] || seen[ni][nj]) continue;
                        seen[ni][nj] = true;
                        stack.push(new int[]{ni, nj});
                    }
                }
                out.add(c);
            }
        }
        return out;
    }

    /** Returns {minX, minZ, maxX, maxZ} in block coordinates for a cluster. */
    private static int[] clusterBBoxBlocks(Cluster c, int originX, int originZ, int half) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] cell : c.cells) {
            int bx = originX - half + cell[0] * SAMPLE_STEP;
            int bz = originZ - half + cell[1] * SAMPLE_STEP;
            if (bx < minX) minX = bx;
            if (bx > maxX) maxX = bx;
            if (bz < minZ) minZ = bz;
            if (bz > maxZ) maxZ = bz;
        }
        // Inflate by half a sample step so the polygon covers the cell extents
        int pad = SAMPLE_STEP / 2;
        return new int[]{minX - pad, minZ - pad, maxX + pad, maxZ + pad};
    }

    private static PolygonXZ bboxPolygon(int minX, int minZ, int maxX, int maxZ, int y) {
        return new PolygonXZ(List.of(
            new BlockPos(minX, y, minZ),
            new BlockPos(maxX, y, minZ),
            new BlockPos(maxX, y, maxZ),
            new BlockPos(minX, y, maxZ)
        ));
    }

    private static PolygonXZ refineY(PolygonXZ p, ServerLevel level) {
        List<BlockPos> verts = new ArrayList<>(p.vertices().size());
        for (BlockPos v : p.vertices()) {
            int hy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    v.getX(), v.getZ());
            verts.add(new BlockPos(v.getX(), hy, v.getZ()));
        }
        return new PolygonXZ(verts);
    }
}
