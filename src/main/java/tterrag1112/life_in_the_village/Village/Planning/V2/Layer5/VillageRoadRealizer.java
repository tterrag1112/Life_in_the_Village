package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.CulturePalette;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PaletteRegistry;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.DensityProfile;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkEdge;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.InternalPath;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadFormality;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Roads.Realization.UnifiedRoadPlacer;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Roads fix-up — realizes the routed in-village road network through the SAME
 * pipeline the great roads + inter-village connectors use:
 * {@link UnifiedRoadPlacer} (→ {@code OrganicRoadPlacer} core/inner/edge zones
 * + position-noise) with the village's {@link CulturePalette}. Replaces the
 * retired {@code RoadPainter}, which painted a single flat perpendicular strip
 * per centerline point (the diagonal "checkerboard") with a flat palette.
 *
 * <p>Each routed edge is a {@link RoadPrimitive.SmoothedPath} (the
 * {@code BlockServingRouter} emits only SmoothedPath edges); its {@code tier()}
 * is the road-tier hierarchy the router assigned — {@code TOWN_ROAD} for the
 * trunk/main street, {@code VILLAGE_ROAD} for the district-serving branches —
 * so wider main streets read clearly against the lanes. The placer densifies,
 * clears vegetation, paints organic zones, and runs the culture architectural +
 * winter passes, exactly as connectors do; {@code greatRoad=false} skips the
 * great-road terrain-authority pass (supports / retaining walls).
 */
public final class VillageRoadRealizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(VillageRoadRealizer.class);

    private VillageRoadRealizer() {}

    /** Fresh-at-spawn roads have full maintenance (no decay overlay). */
    private static final int FRESH_MAINTENANCE = 100;

    /** Legacy entry — no density profile: every edge paints ORGANIC
     *  (byte-identical to the pre-formality pipeline). */
    public static int realize(ServerLevel level, RoadNetwork roads, Culture culture) {
        return realize(level, roads, culture, (DensityProfile) null);
    }

    /**
     * Realizes every routed village edge; returns the total blocks placed.
     *
     * <p>City-morphology step 2a — {@code profile} is the village's density
     * gradient (area-budget zones over the terrain-warped cost-distance
     * field); each edge's {@link RoadFormality} is re-sampled from it at the
     * edge's ENDPOINT midpoint (the same invariant sample the planning-side
     * geometry rewrite used, so the two passes always agree).
     * FORMAL edges paint CRISP (no edge-noise dropout, no accent speckle)
     * with the stone-brick TOWN_ROAD base ({@link PathMaterial#stoneBrick}
     * — the most formal surface the existing tier machinery has; the per-
     * culture formal palette is deferred to the culture work, per design
     * doc §4). MIXED and ORGANIC edges keep the culture palette material
     * and the organic placer behavior, byte-identical to today.
     */
    public static int realize(ServerLevel level, RoadNetwork roads, Culture culture,
                              @Nullable DensityProfile profile) {
        if (roads == null || roads.skeleton() == null) return 0;

        String cultureId = culture != null ? culture.id() : "default";
        CulturePalette palette = PaletteRegistry.forCulture(cultureId);
        PathMaterial base = PathMaterial.fromCulturePalette(palette);
        SeasonTracker.Season season = SeasonTracker.currentSeason(level);
        long worldSeed = level.getSeed();

        int total = 0;
        int edges = 0;
        int formalEdges = 0;
        for (NetworkEdge edge : roads.skeleton().edges()) {
            if (!(edge.primitive() instanceof RoadPrimitive.SmoothedPath sp)) continue;
            List<BlockPos> centerline = sp.waypoints();
            if (centerline == null || centerline.size() < 2) continue;

            RoadShape.RoadTier tier = sp.tier();
            boolean formal =
                    RoadFormality.atMid(profile, centerline) == RoadFormality.FORMAL;
            if (formal) formalEdges++;
            // Maintenance decay is skipped on fresh roads (FRESH_MAINTENANCE);
            // the seasonal overlay still applies (winter snow on stone tiers).
            PathMaterial material = PathMaterial.applyOverlays(
                    formal ? PathMaterial.stoneBrick() : base,
                    FRESH_MAINTENANCE, tier, season);

            long seed = worldSeed ^ edgeSeed(centerline);
            List<BlockPos> placed = UnifiedRoadPlacer.place(level, centerline,
                    material, tier, seed, /*greatRoad*/ false, /*character*/ null,
                    cultureId, palette, formal);
            total += placed.size();
            edges++;
        }

        LOGGER.info("VillageRoadRealizer: realized {} village edge(s) ({} formal)"
                + " -> {} blocks (culture={})", edges, formalEdges, total, cultureId);
        return total;
    }

    /**
     * Internal-path render infra — realizes a list of explicit centerlines (e.g.
     * residential variant lanes) through the SAME pipeline as {@link #realize}:
     * {@link UnifiedRoadPlacer} + the village {@link CulturePalette} at each
     * path's tier. This is NOT a parallel painter — it drives the unified placer
     * from caller-supplied centerlines instead of routed network edges, the seam
     * the street-row lane + (later) courtyard entry path render through. Returns
     * total blocks placed.
     */
    public static int realizePaths(ServerLevel level, List<InternalPath> paths,
                                   Culture culture) {
        return realizePaths(level, paths, culture, (DensityProfile) null);
    }

    /**
     * City-morphology step 1 — formality-aware internal paths. District
     * streets/lanes are ALREADY straight (BSP corridors, variant lanes), so
     * no geometry changes here and there is no jitter to double-suppress;
     * the FORMAL treatment is surface-only: crisp paint + the stone-brick
     * base, sampled per path exactly like {@link #realize} samples per edge,
     * so a quarter street inside the formal core reads as one fabric with
     * the router streets it meets.
     */
    public static int realizePaths(ServerLevel level, List<InternalPath> paths,
                                   Culture culture, @Nullable DensityProfile profile) {
        if (paths == null || paths.isEmpty()) return 0;

        String cultureId = culture != null ? culture.id() : "default";
        CulturePalette palette = PaletteRegistry.forCulture(cultureId);
        PathMaterial base = PathMaterial.fromCulturePalette(palette);
        SeasonTracker.Season season = SeasonTracker.currentSeason(level);
        long worldSeed = level.getSeed();

        int total = 0;
        int painted = 0;
        for (InternalPath path : paths) {
            List<BlockPos> centerline = path.waypoints();
            if (centerline == null || centerline.size() < 2) continue;
            RoadShape.RoadTier tier = path.tier();
            boolean formal =
                    RoadFormality.atMid(profile, centerline) == RoadFormality.FORMAL;
            PathMaterial material = PathMaterial.applyOverlays(
                    formal ? PathMaterial.stoneBrick() : base,
                    FRESH_MAINTENANCE, tier, season);
            long seed = worldSeed ^ edgeSeed(centerline);
            List<BlockPos> placed = UnifiedRoadPlacer.place(level, centerline,
                    material, tier, seed, /*greatRoad*/ false, /*character*/ null,
                    cultureId, palette, formal);
            total += placed.size();
            painted++;
        }
        LOGGER.info("VillageRoadRealizer: realized {} internal path(s) -> {} blocks"
                + " (culture={})", painted, total, cultureId);
        return total;
    }

    /** Deterministic per-edge seed from its endpoints (the routed centerline is
     *  deterministic, so re-realisation matches). */
    private static long edgeSeed(List<BlockPos> centerline) {
        BlockPos a = centerline.get(0);
        BlockPos b = centerline.get(centerline.size() - 1);
        long h = 1125899906842597L;
        h = 31 * h + a.getX();
        h = 31 * h + a.getZ();
        h = 31 * h + b.getX();
        h = 31 * h + b.getZ();
        return h;
    }
}
