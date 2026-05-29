package tterrag1112.life_in_the_village.Village.Markets.Complex;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.MarketComplexSpec;

import java.util.List;
import java.util.Optional;

/**
 * Plans a bounded rectangular "market complex" region around a placed
 * MARKET building (merchant arc Phase 2a). Pure: no world access, no
 * persistence, no logging side effects — the V2 spawn adapter calls
 * {@link #plan} post-building-loop and renders the result.
 *
 * <p>Unlike {@code FarmComplexPlanner} (terrain flood-fill), the market
 * region is a deterministic rectangle scaled off the building footprint
 * plus a configured margin, so it is recomputable and need not be
 * persisted in 2a (see {@code MERCHANT_PROGRESS.md} Phase 2a). Collision
 * is checked here against caller-supplied obstacle rectangles (other
 * buildings' footprints, sibling complex regions); the pad
 * <em>shrinks uniformly</em> to {@code minPadMargin} and then
 * <em>skips</em> rather than overlap a neighbour — markets are central,
 * so a stomp would be far worse than a missing pad.
 */
public final class MarketComplexPlanner {

    /** Extra ring (blocks) kept clear around each obstacle so the pad
     *  never abuts a neighbour's wall. */
    private static final int OBSTACLE_BUFFER = 1;

    private MarketComplexPlanner() {}

    /** Planner input. {@code obstacles} must already exclude the market's
     *  own footprint. */
    public record Input(
            BlockPos marketCentre,
            int footprintWidth,
            int footprintLength,
            int padY,
            String culture,
            BuildingType buildingType,
            List<Polygon.AABB> obstacles) {
        public Input {
            obstacles = obstacles == null ? List.of() : List.copyOf(obstacles);
            if (buildingType == null) buildingType = BuildingType.MARKET;
        }
    }

    public enum Status {
        SUCCESS,
        NO_SPEC_REGISTERED,
        /** No collision-free pad of at least {@code minPadMargin} fits —
         *  the building still works, it just gets no pad. */
        NO_REGION
    }

    /** Planner output. The region/buildingBounds polygons are
     *  axis-aligned rectangles in world XZ at {@code padY}. */
    public record PlanResult(
            Status status,
            String detail,
            Polygon region,
            Polygon buildingBounds,
            int padY,
            int chosenMargin,
            @Nullable String padBlockId) {

        public boolean success() { return status == Status.SUCCESS; }

        public static PlanResult fail(Status s, String detail) {
            return new PlanResult(s, detail, null, null, 0, 0, null);
        }
    }

    public static PlanResult plan(Input in) {
        Optional<MarketComplexSpec> maybeSpec =
                MarketComplexRegistry.get(in.culture(), in.buildingType());
        if (maybeSpec.isEmpty()) {
            return PlanResult.fail(Status.NO_SPEC_REGISTERED,
                    "No MarketComplexSpec for (culture=" + in.culture()
                            + ", type=" + in.buildingType() + ").");
        }
        MarketComplexSpec spec = maybeSpec.get();

        int hx = Math.max(1, in.footprintWidth() / 2);
        int hz = Math.max(1, in.footprintLength() / 2);
        int cx = in.marketCentre().getX();
        int cz = in.marketCentre().getZ();
        Polygon.AABB footprint = new Polygon.AABB(cx - hx, cz - hz, cx + hx, cz + hz);

        // Shrink uniformly from padMargin down to minPadMargin; first
        // collision-free margin wins. (Per-side clipping would yield
        // larger pads in tight cores — flagged as a 2b/tuning follow-up.)
        for (int m = spec.padMargin(); m >= spec.minPadMargin(); m--) {
            Polygon.AABB cand = new Polygon.AABB(
                    cx - hx - m, cz - hz - m, cx + hx + m, cz + hz + m);
            if (!collidesAny(cand, in.obstacles())) {
                return new PlanResult(Status.SUCCESS, null,
                        rectToPolygon(cand, in.padY()),
                        rectToPolygon(footprint, in.padY()),
                        in.padY(), m, spec.padBlockId());
            }
        }
        return PlanResult.fail(Status.NO_REGION,
                "no collision-free pad margin >= " + spec.minPadMargin()
                        + " (" + in.obstacles().size() + " obstacles)");
    }

    private static boolean collidesAny(Polygon.AABB cand, List<Polygon.AABB> obstacles) {
        for (Polygon.AABB o : obstacles) {
            if (overlapsXZ(cand, o)) return true;
        }
        return false;
    }

    /** XZ overlap with each obstacle inflated by {@link #OBSTACLE_BUFFER}. */
    private static boolean overlapsXZ(Polygon.AABB a, Polygon.AABB o) {
        int oMinX = o.minX() - OBSTACLE_BUFFER, oMaxX = o.maxX() + OBSTACLE_BUFFER;
        int oMinZ = o.minZ() - OBSTACLE_BUFFER, oMaxZ = o.maxZ() + OBSTACLE_BUFFER;
        return a.minX() <= oMaxX && a.maxX() >= oMinX
                && a.minZ() <= oMaxZ && a.maxZ() >= oMinZ;
    }

    private static Polygon rectToPolygon(Polygon.AABB r, int y) {
        return new Polygon(List.of(
                new BlockPos(r.minX(), y, r.minZ()),
                new BlockPos(r.maxX(), y, r.minZ()),
                new BlockPos(r.maxX(), y, r.maxZ()),
                new BlockPos(r.minX(), y, r.maxZ())));
    }
}
