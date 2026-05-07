package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.StructureSizeCache;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.BlockCategory;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.Cell;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.V2FeatureMap;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * V2 Layer 3 — Building Placement (the main loop).
 *
 * <p>For each building in topological order:
 * <ol>
 *   <li>Check {@code requires_present} dependencies — drop with
 *       {@link DropReason#DEPENDENCY_MISSING} if any are absent.</li>
 *   <li>Iterate the feature map's cells, filter by terrain
 *       (OPEN/SHORE, slope ≤ 3) and reservation overlap, score each
 *       passing cell.</li>
 *   <li>Pick the highest-scoring cell. If best score ≤ 0, drop with
 *       {@link DropReason#NO_VIABLE_CANDIDATE}.</li>
 *   <li>Pick the variant from the chosen position, fetch the
 *       variant's footprint, compute rotation, emit
 *       {@link PlacedBuilding}.</li>
 *   <li>Reserve a square AABB of {@code max(w, l) + MIN_GAP} around
 *       the chosen cell for subsequent overlap checks. Square +
 *       gap is rotation-invariant and slightly conservative.</li>
 * </ol>
 *
 * <p>If a {@code required: true} building drops, the result's
 * {@code villageViable} flag flips to false. Layer 2 should
 * ordinarily prevent this.
 *
 * <p>Use {@link #solve} for the production hand-off; use
 * {@link #solveWithDiagnostics} to capture per-placement score
 * breakdowns for the {@code /litv place} debug command.
 */
public final class PlacementSolver {

    /** Tier-derived village radius in BLOCKS, used to normalise
     *  distance terms in scoring. */
    private static final EnumMap<ViabilityTier, Integer> VILLAGE_RADIUS = new EnumMap<>(ViabilityTier.class);
    static {
        VILLAGE_RADIUS.put(ViabilityTier.CITY, 80);
        VILLAGE_RADIUS.put(ViabilityTier.TOWN, 40);
        VILLAGE_RADIUS.put(ViabilityTier.HAMLET, 20);
        VILLAGE_RADIUS.put(ViabilityTier.OUTPOST, 10);
        VILLAGE_RADIUS.put(ViabilityTier.UNVIABLE, 10);
    }

    /** Slope cap (per-cell {@code localSlope}) for candidate cells.
     *  Cells above this are filtered out before scoring. */
    private static final int MAX_SLOPE = 3;

    private PlacementSolver() {}

    public static int villageRadiusFor(ViabilityTier tier) {
        return VILLAGE_RADIUS.getOrDefault(tier, 20);
    }

    public static PlacementResult solve(SiteContext ctx, V2FeatureMap fmap,
                                        List<BuildingType> sorted, ServerLevel level) {
        return solveWithDiagnostics(ctx, fmap, sorted, level).result;
    }

    /** Variant of {@link #solve} that also returns per-placement
     *  score breakdowns for the debug command. */
    public static SolveResult solveWithDiagnostics(SiteContext ctx, V2FeatureMap fmap,
                                                   List<BuildingType> sorted,
                                                   ServerLevel level) {
        List<PlacedBuilding> placed = new ArrayList<>();
        List<DroppedBuilding> dropped = new ArrayList<>();
        List<Reservation> reservations = new ArrayList<>();
        List<PlacedDiagnostic> diagnostics = new ArrayList<>();
        boolean villageViable = true;

        StructureSizeCache sizes = new StructureSizeCache(level);
        int villageRadius = villageRadiusFor(ctx.tier());
        String culture = ctx.culture().id();

        for (BuildingType type : sorted) {
            PlacementProfile profile = PlacementDefaults.get(type);
            if (profile == null) {
                dropped.add(new DroppedBuilding(type, DropReason.NOT_SELECTED,
                        "no PlacementProfile authored"));
                continue;
            }

            // 1. Dependency check.
            EnumSet<BuildingType> placedTypes = EnumSet.noneOf(BuildingType.class);
            for (PlacedBuilding pb : placed) placedTypes.add(pb.type());
            List<BuildingType> missing = new ArrayList<>();
            for (BuildingType dep : profile.requiresPresent()) {
                if (!placedTypes.contains(dep)) missing.add(dep);
            }
            if (!missing.isEmpty()) {
                dropped.add(new DroppedBuilding(type, DropReason.DEPENDENCY_MISSING,
                        "missing: " + missing));
                if (profile.required()) villageViable = false;
                continue;
            }

            // 2-4. Generate + score candidates per-cell.
            Best best = findBest(type, profile, ctx, fmap, placed, reservations,
                    sizes, culture, villageRadius);

            if (best == null) {
                dropped.add(new DroppedBuilding(type, DropReason.NO_VIABLE_CANDIDATE,
                        "no positive-scoring cell within slope/reservation filters"));
                if (profile.required()) villageViable = false;
                continue;
            }

            // 5. Place.
            Rotation rotation = chooseFacing(best.pos, ctx.anchor());
            placed.add(new PlacedBuilding(type, best.pos, best.footprint,
                    rotation, profile.priority(), best.variantId));
            reservations.add(new Reservation(best.pos, best.squareSize));
            diagnostics.add(new PlacedDiagnostic(type, best.score));
        }

        EnumMap<BuildingType, Integer> counts = new EnumMap<>(BuildingType.class);
        for (PlacedBuilding pb : placed) counts.merge(pb.type(), 1, Integer::sum);

        PlacementResult result = new PlacementResult(
                List.copyOf(placed), List.copyOf(dropped),
                Map.copyOf(counts), villageViable);
        return new SolveResult(result, diagnostics);
    }

    // =========================================================================
    // Candidate scoring
    // =========================================================================

    private static Best findBest(BuildingType type, PlacementProfile profile,
                                 SiteContext ctx, V2FeatureMap fmap,
                                 List<PlacedBuilding> placed,
                                 List<Reservation> reservations,
                                 StructureSizeCache sizes, String culture,
                                 int villageRadius) {
        Best best = null;
        int gridSize = fmap.gridSize();
        for (int i = 0; i < gridSize; i++) {
            for (int j = 0; j < gridSize; j++) {
                Cell cell = fmap.cell(i, j);
                BlockCategory cat = cell.category();
                if (cat != BlockCategory.OPEN && cat != BlockCategory.SHORE) continue;
                if (cell.localSlope() > MAX_SLOPE) continue;

                BlockPos pos = fmap.cellWorldPos(i, j);

                // Per-candidate variant pick (cheap; cache hits).
                String variantId = VariantPicker.pick(type, pos, ctx.anchor(), villageRadius);
                StructureSizeCache.FootprintInfo info = sizes.get(culture, Style.RURAL,
                        type, variantId, /*level*/ 1, Rotation.NONE);
                Footprint fp = new Footprint(info.width(), info.length());
                int squareSize = Math.max(fp.width(), fp.length())
                        + StructureSizeCache.MIN_GAP;

                if (overlapsAnyReservation(pos, squareSize, reservations)) continue;

                ScoreBreakdown score = scorePosition(pos, type, profile, ctx,
                        fmap, placed, villageRadius);
                if (score.total() <= 0) continue;

                if (best == null || score.total() > best.score.total()) {
                    best = new Best(pos, fp, squareSize, variantId, score);
                }
            }
        }
        return best;
    }

    private static ScoreBreakdown scorePosition(BlockPos pos, BuildingType type,
                                                PlacementProfile profile, SiteContext ctx,
                                                V2FeatureMap fmap, List<PlacedBuilding> placed,
                                                int villageRadius) {
        double terrain = 0;
        for (Map.Entry<TerrainFactor, Double> e : profile.terrainWeights().entrySet()) {
            terrain += e.getValue() * terrainFactorValue(e.getKey(), pos, fmap, villageRadius);
        }
        double adjacency = 0;
        for (Map.Entry<AdjacencyFactor, Double> e : profile.adjacencyWeights().entrySet()) {
            adjacency += e.getValue() * adjacencyFactorValue(
                    e.getKey(), pos, type, ctx, placed, villageRadius);
        }
        double dist = euclidean(pos, ctx.anchor());
        double normDist = Math.min(1.0, dist / Math.max(1, villageRadius));
        double centrality = Math.max(0, 1 - Math.abs(profile.centrality() - normDist));
        return new ScoreBreakdown(terrain, adjacency, centrality);
    }

    private static double terrainFactorValue(TerrainFactor f, BlockPos pos,
                                             V2FeatureMap fmap, int villageRadius) {
        Cell cell = fmap.cellAt(pos.getX(), pos.getZ());
        int cellSize = fmap.cellSize();
        return switch (f) {
            case FLAT -> 1.0 - Math.min(1.0, cell.localSlope() / 4.0);
            case NEAR_WATER -> distFactor(cell.distToWater(), cellSize, villageRadius);
            case NEAR_FOREST -> distFactor(cell.distToForest(), cellSize, villageRadius);
            case NEAR_STONE -> distFactor(cell.distToStone(), cellSize, villageRadius);
            case NEAR_COAST -> coastFactor(pos, fmap, villageRadius);
        };
    }

    private static double distFactor(int distCells, int cellSize, int villageRadius) {
        if (distCells == Integer.MAX_VALUE) return 0.0;
        double distBlocks = distCells * (double) cellSize;
        return 1.0 / (1.0 + distBlocks / Math.max(1, villageRadius));
    }

    private static double coastFactor(BlockPos pos, V2FeatureMap fmap, int villageRadius) {
        Optional<List<BlockPos>> coast = fmap.coastline();
        if (coast.isEmpty() || coast.get().isEmpty()) return 0.0;
        int minDist = Integer.MAX_VALUE;
        for (BlockPos c : coast.get()) {
            int d = Math.max(Math.abs(pos.getX() - c.getX()),
                    Math.abs(pos.getZ() - c.getZ()));
            if (d < minDist) minDist = d;
        }
        return 1.0 / (1.0 + minDist / (double) Math.max(1, villageRadius));
    }

    private static double adjacencyFactorValue(AdjacencyFactor f, BlockPos pos,
                                               BuildingType type, SiteContext ctx,
                                               List<PlacedBuilding> placed,
                                               int villageRadius) {
        return switch (f) {
            case NEAR_ANCHOR, NEAR_CIVIC_CENTRE ->
                    1.0 / (1.0 + euclidean(pos, ctx.anchor()) / Math.max(1, villageRadius));
            case NEAR_MAIN_ROAD ->
                    1.0 / (1.0 + perpDistToSpine(pos, ctx) / Math.max(1, villageRadius));
            case FAR_FROM_ANCHOR, FAR_FROM_CIVIC_CENTRE -> {
                double d = euclidean(pos, ctx.anchor());
                yield d / (d + villageRadius);
            }
            case FAR_FROM_SAME_TYPE -> {
                double nearest = Double.MAX_VALUE;
                for (PlacedBuilding pb : placed) {
                    if (pb.type() != type) continue;
                    double d = euclidean(pos, pb.centre());
                    if (d < nearest) nearest = d;
                }
                if (nearest == Double.MAX_VALUE) yield 1.0;  // first of its type
                yield nearest / (nearest + villageRadius);
            }
        };
    }

    private static double perpDistToSpine(BlockPos pos, SiteContext ctx) {
        BlockPos a = ctx.anchor();
        Vec3 d = ctx.primarySpineDirection();
        double vx = pos.getX() - a.getX();
        double vz = pos.getZ() - a.getZ();
        // Perpendicular distance from pos to the infinite line through
        // anchor along (d.x, d.z): |v × d| where d is unit XZ.
        return Math.abs(vx * d.z - vz * d.x);
    }

    private static double euclidean(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // =========================================================================
    // Reservation overlap (AABB rectangles, square + MIN_GAP)
    // =========================================================================

    private static boolean overlapsAnyReservation(BlockPos pos, int squareSize,
                                                  List<Reservation> reservations) {
        int half = squareSize / 2;
        int aMinX = pos.getX() - half, aMaxX = pos.getX() + half;
        int aMinZ = pos.getZ() - half, aMaxZ = pos.getZ() + half;
        for (Reservation r : reservations) {
            int rHalf = r.squareSize() / 2;
            int bMinX = r.centre().getX() - rHalf, bMaxX = r.centre().getX() + rHalf;
            int bMinZ = r.centre().getZ() - rHalf, bMaxZ = r.centre().getZ() + rHalf;
            if (aMinX <= bMaxX && aMaxX >= bMinX
                    && aMinZ <= bMaxZ && aMaxZ >= bMinZ) return true;
        }
        return false;
    }

    // =========================================================================
    // Rotation
    // =========================================================================

    /** Mirrors {@code PlanContext.chooseFacing} (V1) so V2's
     *  rotation conventions don't diverge from existing decoration
     *  code. Building at {@code pos} faces {@code target}. */
    private static Rotation chooseFacing(BlockPos pos, BlockPos target) {
        int dx = target.getX() - pos.getX();
        int dz = target.getZ() - pos.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Rotation.COUNTERCLOCKWISE_90 : Rotation.CLOCKWISE_90;
        }
        return dz > 0 ? Rotation.NONE : Rotation.CLOCKWISE_180;
    }

    // =========================================================================
    // Diagnostic carriers
    // =========================================================================

    public record SolveResult(PlacementResult result, List<PlacedDiagnostic> diagnostics) {}

    public record PlacedDiagnostic(BuildingType type, ScoreBreakdown score) {}

    public record ScoreBreakdown(double terrain, double adjacency, double centrality) {
        public double total() { return terrain + adjacency + centrality; }
    }

    private record Best(BlockPos pos, Footprint footprint, int squareSize,
                        String variantId, ScoreBreakdown score) {}

    private record Reservation(BlockPos centre, int squareSize) {}
}
