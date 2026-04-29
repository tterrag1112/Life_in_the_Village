package tterrag1112.life_in_the_village.Village.Planning.Features;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainAnalyzer;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.VillageTypeData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The village's geometric feature inventory: hull, plaza polygons, water
 * features, cliff features, reservations.
 *
 * <h3>Phase 3 status</h3>
 * Single planning-pass build via {@link #buildPlanning}; recipes still
 * read directly from {@link TerrainProfile}. Phase 4 introduces the
 * two-pass refine, Phase 8+ migrates recipes to consume from this map,
 * Phase 19 adds persistence.
 */
public final class FeatureMap {

    private PolygonXZ hull;
    private final List<PolygonXZ>      plazaPolygons = new ArrayList<>();
    private final List<WaterFeature>   waterFeatures = new ArrayList<>();
    private final List<CliffFeature>   cliffFeatures = new ArrayList<>();
    private final List<ReservedRegion> reservations  = new ArrayList<>();

    /**
     * Builds the planning-time feature map. Hull is the convex hull of
     * {@link TerrainProfile#flatCandidates} inflated by an estimated
     * margin; water and cliff features are lifted directly from the
     * terrain analyzer's reports.
     */
    public static FeatureMap buildPlanning(BlockPos origin,
                                           TerrainProfile terrain,
                                           List<VillageTypeData.StarterBuilding> remaining) {
        FeatureMap fm = new FeatureMap();

        if (!terrain.flatCandidates().isEmpty()) {
            PolygonXZ raw = PolygonXZ.convexHull(terrain.flatCandidates());
            int margin = estimateHullMargin(remaining);
            fm.hull = raw.inflate(margin);
        } else {
            int r = 64;
            fm.hull = new PolygonXZ(List.of(
                origin.offset(-r, 0, -r),
                origin.offset( r, 0, -r),
                origin.offset( r, 0,  r),
                origin.offset(-r, 0,  r)
            ));
        }

        if (terrain.hasWater()) {
            TerrainAnalyzer.WaterBodyInfo wb = terrain.waterBody();
            PolygonXZ outline = circleApprox(wb.centre(), Math.max(1, wb.radius()), 12);
            WaterKind kind = inferKind(wb);
            fm.waterFeatures.add(new WaterFeature(
                outline, wb.centre().getY(), kind, wb.centre()));
        }

        for (TerrainAnalyzer.RidgeInfo ridge : terrain.ridges()) {
            PolygonXZ rect = rectanglePolygon(ridge.min(), ridge.max());
            List<BlockPos> walk = List.of(
                ridge.min(),
                new BlockPos(ridge.max().getX(), ridge.min().getY(), ridge.min().getZ()),
                ridge.max(),
                new BlockPos(ridge.min().getX(), ridge.min().getY(), ridge.max().getZ())
            );
            fm.cliffFeatures.add(new CliffFeature(
                rect, ridge.max().getY(), ridge.min().getY(), walk));
        }

        return fm;
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

    /**
     * Phase 3 size heuristic: uses the analyzer's {@code sampleCount}
     * (number of water surface blocks sampled) as a proxy for area, since
     * {@link TerrainAnalyzer.WaterBodyInfo} does not expose an explicit
     * area field. Phase 4+ may consume biome data for a better classifier.
     */
    private static WaterKind inferKind(TerrainAnalyzer.WaterBodyInfo wb) {
        int s = wb.sampleCount();
        if (s > 1000) return WaterKind.OCEAN;
        if (s > 200)  return WaterKind.LAKE;
        return WaterKind.RIVER;
    }

    private static PolygonXZ circleApprox(BlockPos centre, int radius, int sides) {
        List<BlockPos> verts = new ArrayList<>(sides);
        for (int i = 0; i < sides; i++) {
            double a = 2 * Math.PI * i / sides;
            int x = centre.getX() + (int) Math.round(Math.cos(a) * radius);
            int z = centre.getZ() + (int) Math.round(Math.sin(a) * radius);
            verts.add(new BlockPos(x, centre.getY(), z));
        }
        return new PolygonXZ(verts);
    }

    private static PolygonXZ rectanglePolygon(BlockPos min, BlockPos max) {
        return new PolygonXZ(List.of(
            new BlockPos(min.getX(), min.getY(), min.getZ()),
            new BlockPos(max.getX(), min.getY(), min.getZ()),
            new BlockPos(max.getX(), min.getY(), max.getZ()),
            new BlockPos(min.getX(), min.getY(), max.getZ())
        ));
    }
}
