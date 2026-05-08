package tterrag1112.life_in_the_village.Village.Farms;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Decoration.Parks.GardenPlot;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * B2.5 — Layer 4 post-pass that scans the {@link V2FeatureMap} for
 * arable cells outside placed building / park footprints, picks a
 * sector centre near farmhouses, builds a 4-vertex terrain-trimmed
 * polygon at the doc-10 tier-scaled radius, and allocates
 * {@link FarmPlot}s per farmhouse.
 *
 * <h3>Sector size — doc 10 tier-only (per user direction)</h3>
 * <ul>
 *   <li>HAMLET — 15 blocks radius</li>
 *   <li>VILLAGE — 20</li>
 *   <li>TOWN — 25</li>
 *   <li>CITY — 30</li>
 * </ul>
 * The culture's {@code farmPriority} adds modest slack (±20%); the
 * doc-10 base radius governs the centre value.
 *
 * <h3>Plot allocation — prompt's per-farmhouse N (per user direction)</h3>
 * <ul>
 *   <li>HAMLET — 2 plots / farmhouse</li>
 *   <li>VILLAGE — 2-3</li>
 *   <li>TOWN — 3-4</li>
 *   <li>CITY — 4-6</li>
 * </ul>
 *
 * <h3>Skipping</h3>
 * Skips entirely when the village's inclination is not
 * {@link Inclination#AGRICULTURAL}, when there are no farmhouses, or
 * when no candidate centre passes the arable threshold. (CIVIC /
 * INDUSTRIAL / DEFENSIVE villages don't grow farms.)
 */
public final class FarmSectorPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(FarmSectorPlanner.class);

    /** Cells with combined arable-score &gt;= this become candidate
     *  centres. */
    private static final double ARABLE_THRESHOLD = 0.55;

    /** Max slope cells the sector tolerates inside its polygon. */
    private static final int MAX_INTERIOR_SLOPE = 3;

    /** Block-buffer between the sector polygon and a building or
     *  park footprint. */
    private static final int OBSTACLE_BUFFER = 3;

    /** Minimum plot footprint side; drives the lattice sub-cell size. */
    private static final int MIN_PLOT_SIDE = 6;

    private static final long FARM_RNG_SALT = 0x4641524D5F503531L;

    private FarmSectorPlanner() {}

    /**
     * Plans a {@link FarmSector} (zero or one per village in B2.5)
     * plus the {@link FarmPlot}s allocated to each farmhouse.
     * Persists both into {@code data}; the renderer + FarmerGoal
     * read from {@code VillageSavedData} downstream.
     */
    public static FarmSector plan(V2FeatureMap fmap,
                                  Collection<Building> placedBuildings,
                                  Collection<GardenPlot> reservedParks,
                                  Culture culture,
                                  Inclination inclination,
                                  VillageSizeTier tier,
                                  UUID villageId,
                                  long seed,
                                  long createdTick,
                                  VillageSavedData data) {
        if (fmap == null || tier == null || data == null) return null;
        if (inclination != Inclination.AGRICULTURAL) {
            LOGGER.debug("FarmSectorPlanner: village {} not AGRICULTURAL; skipping", villageId);
            return null;
        }
        List<Building> farmhouses = collectFarmhouses(placedBuildings);
        if (farmhouses.isEmpty()) return null;

        Random rng = new Random(seed ^ FARM_RNG_SALT);

        int radius = tierRadius(tier, priorityOf(culture));

        // Pick a sector centre — best arable cell within reach of
        // the farmhouse cluster, masked against buildings + parks.
        BlockPos farmhouseCentre = centroid(farmhouses);
        BlockPos sectorCentre = pickArableCentre(
                fmap, farmhouseCentre, placedBuildings, reservedParks, radius);
        if (sectorCentre == null) {
            LOGGER.debug("FarmSectorPlanner: village {} no arable centre passed "
                    + "threshold; skipping", villageId);
            return null;
        }

        // Build the sector polygon — 4-vertex terrain-trimmed
        // rectangle around the centre.
        Polygon bounds = buildPolygon(fmap, sectorCentre, radius,
                placedBuildings, reservedParks);

        // Allocate plots within the polygon.
        UUID sectorId = UUID.nameUUIDFromBytes(
                ("farm-sector/" + villageId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<FarmPlot> plots = allocatePlots(
                fmap, bounds, farmhouses, tier, culture, sectorId, rng);
        for (FarmPlot p : plots) data.addFarmPlot(p);

        FarmSector sector = new FarmSector(
                sectorId, villageId, bounds,
                plots.stream().map(FarmPlot::getId).toList(),
                farmhouses.stream().map(Building::getId).toList(),
                List.of(),  // tool shed positions filled by renderer
                createdTick);
        data.addFarmSector(sector);

        LOGGER.info("FarmSectorPlanner: village {} reserved sector {} "
                + "(radius={}, {} plots across {} farmhouses)",
                villageId, sectorId, radius, plots.size(), farmhouses.size());
        return sector;
    }

    // ── Sector size ─────────────────────────────────────────────────────

    /** Doc 10 tier-only base radius, with up to ±20% slack from the
     *  culture's {@code farmPriority}. */
    static int tierRadius(VillageSizeTier tier, double farmPriority) {
        int base = switch (tier) {
            case HAMLET  -> 15;
            case VILLAGE -> 20;
            case TOWN    -> 25;
            case CITY    -> 30;
        };
        // Map priority [0..1] to slack [-0.2..+0.2].
        double slack = (farmPriority - 0.5) * 0.4;
        return Math.max(8, (int) Math.round(base * (1.0 + slack)));
    }

    /** Plot count for a single farmhouse at this tier. RNG draws
     *  inside the per-tier band so spawns vary slightly seed to seed. */
    static int plotsPerFarmhouse(VillageSizeTier tier, Random rng) {
        return switch (tier) {
            case HAMLET  -> 2;
            case VILLAGE -> 2 + rng.nextInt(2);  // 2 or 3
            case TOWN    -> 3 + rng.nextInt(2);  // 3 or 4
            case CITY    -> 4 + rng.nextInt(3);  // 4..6
        };
    }

    private static double priorityOf(Culture culture) {
        if (culture == null) return 0.7;
        return culture.planningBias().farmPriority();
    }

    // ── Farmhouse collection + centroid ────────────────────────────────

    private static List<Building> collectFarmhouses(
            Collection<Building> placedBuildings) {
        List<Building> out = new ArrayList<>();
        if (placedBuildings == null) return out;
        for (Building b : placedBuildings) {
            if (b != null && b.getType() == BuildingType.FARMHOUSE) {
                out.add(b);
            }
        }
        return out;
    }

    private static BlockPos centroid(List<Building> farmhouses) {
        long sumX = 0, sumZ = 0;
        for (Building b : farmhouses) {
            BlockPos p = b.getShape().getOrigin();
            sumX += p.getX();
            sumZ += p.getZ();
        }
        int n = farmhouses.size();
        return new BlockPos((int) (sumX / n), 0, (int) (sumZ / n));
    }

    // ── Centre selection ───────────────────────────────────────────────

    /** Walks cells outward from {@code farmhouseCentre}, scoring each
     *  for arable suitability while skipping cells inside (or
     *  obstacle-buffered around) buildings and parks. Returns the
     *  highest-scoring cell within {@code 2 × radius} of the
     *  farmhouse centroid, or null on miss. */
    private static BlockPos pickArableCentre(V2FeatureMap fmap,
                                             BlockPos farmhouseCentre,
                                             Collection<Building> buildings,
                                             Collection<GardenPlot> parks,
                                             int radius) {
        BlockPos best = null;
        double bestScore = ARABLE_THRESHOLD;
        int searchRadius = radius * 2;
        int step = Math.max(1, fmap.cellSize());
        for (int dx = -searchRadius; dx <= searchRadius; dx += step) {
            for (int dz = -searchRadius; dz <= searchRadius; dz += step) {
                int x = farmhouseCentre.getX() + dx;
                int z = farmhouseCentre.getZ() + dz;
                if (!fmap.inBounds(x, z)) continue;
                if (insideObstacle(x, z, buildings, parks)) continue;
                Cell c = fmap.cellAt(x, z);
                double score = arableScore(c, fmap.cellSize());
                if (score > bestScore) {
                    bestScore = score;
                    best = new BlockPos(x, c.elevationY(), z);
                }
            }
        }
        return best;
    }

    /** Composite arable score: rewards OPEN cells with low slope and
     *  reasonable water proximity. Mirrors B2.4's park scoring with
     *  agricultural weights — water closer to the centre matters
     *  more for irrigation than for parks. */
    static double arableScore(Cell cell, int cellSize) {
        if (cell == null) return 0.0;
        BlockCategory cat = cell.category();
        if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE) return 0.0;
        if (cell.localSlope() > MAX_INTERIOR_SLOPE) return 0.0;
        double slopeScore = Math.max(0.0, 1.0 - cell.localSlope() / 4.0);
        double waterScore = bfsScore(cell.distToWater(), cellSize, 24);
        double forestScore = bfsScore(cell.distToForest(), cellSize, 32);
        // Forest proximity is mildly desirable (orchard / shade) but
        // the cell itself must be OPEN/SHORE so dense forest is filtered.
        return Math.min(1.0,
                0.5 * slopeScore + 0.35 * waterScore + 0.15 * forestScore);
    }

    private static double bfsScore(int distCells, int cellSize, int falloffBlocks) {
        if (distCells == Integer.MAX_VALUE) return 0.0;
        double db = distCells * (double) cellSize;
        return Math.max(0.0, 1.0 - db / Math.max(1, falloffBlocks));
    }

    // ── Polygon construction ───────────────────────────────────────────

    private static Polygon buildPolygon(V2FeatureMap fmap, BlockPos centre,
                                        int radius,
                                        Collection<Building> buildings,
                                        Collection<GardenPlot> parks) {
        // Start with a square; trim each corner inward if it lands on
        // an obstacle or steep slope. This produces simple 4-vertex
        // terrain-trimmed rectangles per the user direction.
        int[] cornerOffsetsX = {-radius, +radius, +radius, -radius};
        int[] cornerOffsetsZ = {-radius, -radius, +radius, +radius};
        List<BlockPos> verts = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            int cx = centre.getX() + cornerOffsetsX[i];
            int cz = centre.getZ() + cornerOffsetsZ[i];
            // Pull each corner toward the centre until it lands on
            // valid arable terrain.
            int attempts = 0;
            while ((insideObstacle(cx, cz, buildings, parks)
                    || !cellOk(fmap, cx, cz)) && attempts < radius) {
                cx -= cornerOffsetsX[i] / Math.max(1, radius);
                cz -= cornerOffsetsZ[i] / Math.max(1, radius);
                attempts++;
            }
            // Sample surface Y from the FeatureMap for the final corner.
            int y = fmap.inBounds(cx, cz) ? fmap.cellAt(cx, cz).elevationY()
                    : centre.getY();
            verts.add(new BlockPos(cx, y, cz));
        }
        return new Polygon(verts);
    }

    private static boolean cellOk(V2FeatureMap fmap, int x, int z) {
        if (!fmap.inBounds(x, z)) return false;
        Cell c = fmap.cellAt(x, z);
        BlockCategory cat = c.category();
        return (cat == BlockCategory.OPEN || cat == BlockCategory.SHORE)
                && c.localSlope() <= MAX_INTERIOR_SLOPE;
    }

    // ── Plot allocation ────────────────────────────────────────────────

    /** Allocates plots inside the sector polygon. Uses a grid of
     *  cells inside the polygon's bounding box; each grid cell that
     *  passes the inside-test becomes a candidate. Plots are
     *  rectangles MIN_PLOT_SIDE on a side, centred on grid points,
     *  one per farmhouse-quota draw round-robin. */
    private static List<FarmPlot> allocatePlots(V2FeatureMap fmap,
                                                Polygon bounds,
                                                List<Building> farmhouses,
                                                VillageSizeTier tier,
                                                Culture culture,
                                                UUID sectorId,
                                                Random rng) {
        List<FarmPlot> out = new ArrayList<>();
        if (bounds == null || bounds.vertices().isEmpty() || farmhouses.isEmpty()) {
            return out;
        }

        int totalQuota = 0;
        int[] perFarmhouseQuota = new int[farmhouses.size()];
        for (int i = 0; i < farmhouses.size(); i++) {
            int q = plotsPerFarmhouse(tier, rng);
            perFarmhouseQuota[i] = q;
            totalQuota += q;
        }
        if (totalQuota == 0) return out;

        // Walk the bounding box at MIN_PLOT_SIDE step.
        int[] bbox = boundingBox(bounds);
        int minX = bbox[0] + MIN_PLOT_SIDE / 2;
        int minZ = bbox[1] + MIN_PLOT_SIDE / 2;
        int maxX = bbox[2] - MIN_PLOT_SIDE / 2;
        int maxZ = bbox[3] - MIN_PLOT_SIDE / 2;
        if (maxX <= minX || maxZ <= minZ) return out;

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = minX; x <= maxX; x += MIN_PLOT_SIDE + 1) {
            for (int z = minZ; z <= maxZ; z += MIN_PLOT_SIDE + 1) {
                BlockPos at = new BlockPos(x, 0, z);
                if (!Polygon.contains(bounds, at)) continue;
                if (!cellOk(fmap, x, z)) continue;
                candidates.add(at.atY(fmap.cellAt(x, z).elevationY()));
            }
        }
        if (candidates.isEmpty()) return out;

        // Round-robin: assign each candidate to the next farmhouse
        // with quota remaining. Order randomised for variation.
        java.util.Collections.shuffle(candidates, rng);
        int farmhouseIdx = 0;
        int placed = 0;
        for (BlockPos pos : candidates) {
            if (placed >= totalQuota) break;
            // Find next farmhouse with remaining quota.
            int safety = farmhouses.size();
            while (perFarmhouseQuota[farmhouseIdx] <= 0 && safety-- > 0) {
                farmhouseIdx = (farmhouseIdx + 1) % farmhouses.size();
            }
            if (perFarmhouseQuota[farmhouseIdx] <= 0) break;
            Building fh = farmhouses.get(farmhouseIdx);

            // Build a 4-vertex polygon for this plot.
            int half = MIN_PLOT_SIDE / 2;
            Polygon plotPoly = new Polygon(List.of(
                    new BlockPos(pos.getX() - half, pos.getY(), pos.getZ() - half),
                    new BlockPos(pos.getX() + half, pos.getY(), pos.getZ() - half),
                    new BlockPos(pos.getX() + half, pos.getY(), pos.getZ() + half),
                    new BlockPos(pos.getX() - half, pos.getY(), pos.getZ() + half)));

            // Pick crop type via FarmCropPicker.
            Cell c = fmap.cellAt(pos.getX(), pos.getZ());
            boolean sloped = c != null && c.localSlope() >= 2;
            boolean forestEdge = c != null && c.distToForest() <= 2;
            boolean pasture = FarmCropPicker.rollPasture(culture, rng);
            FarmPlot.CropType crop = FarmCropPicker.pick(
                    culture, sloped, forestEdge, pasture, rng);

            UUID plotId = UUID.randomUUID();
            FarmPlot plot = new FarmPlot(
                    plotId,
                    "farm-" + plotId.toString().substring(0, 8),
                    pos, half,
                    crop,
                    crop == FarmPlot.CropType.PASTURE
                            ? FarmPlot.PlotSubtype.ANIMAL_PEN
                            : FarmPlot.PlotSubtype.CROP_FIELD);
            plot.setFarmhouseId(fh.getId());
            plot.setSectorId(sectorId);
            plot.setPolygon(plotPoly);
            out.add(plot);

            perFarmhouseQuota[farmhouseIdx]--;
            farmhouseIdx = (farmhouseIdx + 1) % farmhouses.size();
            placed++;
        }
        return out;
    }

    private static int[] boundingBox(Polygon p) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos v : p.vertices()) {
            minX = Math.min(minX, v.getX());
            maxX = Math.max(maxX, v.getX());
            minZ = Math.min(minZ, v.getZ());
            maxZ = Math.max(maxZ, v.getZ());
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    // ── Obstacle detection ─────────────────────────────────────────────

    private static boolean insideObstacle(int x, int z,
                                          Collection<Building> buildings,
                                          Collection<GardenPlot> parks) {
        if (buildings != null) {
            for (Building b : buildings) {
                BlockPos min = b.getShape().getMin();
                BlockPos max = b.getShape().getMax();
                if (x >= min.getX() - OBSTACLE_BUFFER
                        && x <= max.getX() + OBSTACLE_BUFFER
                        && z >= min.getZ() - OBSTACLE_BUFFER
                        && z <= max.getZ() + OBSTACLE_BUFFER) return true;
            }
        }
        if (parks != null) {
            for (GardenPlot p : parks) {
                if (p.bounds().overlaps(
                        x - OBSTACLE_BUFFER, z - OBSTACLE_BUFFER,
                        x + OBSTACLE_BUFFER, z + OBSTACLE_BUFFER)) return true;
            }
        }
        return false;
    }
}
