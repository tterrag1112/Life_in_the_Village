package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.util.RandomSource;

/**
 * Defines the cross-section of a road with organic edge variation.
 *
 * <h3>Width zones</h3>
 * A road at full width (e.g. 7 blocks) has three concentric zones:
 * <pre>
 *   EDGE | INNER | CORE | INNER | EDGE
 *    1      1       3       1      1    = 7 total at max width
 * </pre>
 *
 * <ul>
 *   <li><b>CORE</b> — always the path material. Never has grass creep.</li>
 *   <li><b>INNER</b> — mostly path material with occasional edge material.</li>
 *   <li><b>EDGE</b> — blend of edge material and terrain (grass/dirt).
 *       This is where the organic shape happens — per-block noise
 *       determines whether each edge position gets a road block or
 *       is left as terrain, creating the natural creeping effect.</li>
 * </ul>
 *
 * <h3>Tier-based width</h3>
 * A freshly spawned village might only place the core (3 wide).
 * As the village upgrades, inner blocks are added (5 wide), then
 * edge blocks (7 wide). The reserved width is always the max so
 * buildings never encroach on future road expansion space.
 *
 * <h3>Reserved vs placed width</h3>
 * {@code reservedHalfWidth} defines the collision exclusion zone
 * used by building placement — buildings must not overlap this.
 * {@code placedHalfWidth} defines how many blocks are actually
 * surfaced at the current tier. The difference is "reserved for
 * future upgrade" — left as natural terrain.
 */
public class RoadShape {

    /**
     * Which zone a cross-section position belongs to.
     */
    public enum Zone {
        /** Center of the road — always surfaced with core material. */
        CORE,
        /** Between core and edge — mostly road, slight variation. */
        INNER,
        /** Outermost blocks — organic blend with terrain. */
        EDGE,
        /** Beyond the road — terrain, no placement. */
        OUTSIDE
    }

    // =========================================================================
    // Tier definitions
    // =========================================================================

    /**
     * Road tier defines placed width and material mapping.
     */
    public enum RoadTier {
        /** Dirt path — 3 blocks placed (core only), 7 reserved. */
        FOOTPATH(1, 0, 0),
        /** Gravel — 3 blocks placed (core only), 7 reserved. */
        VILLAGE_PATH(1, 0, 0),
        /** Cobblestone — 5 blocks placed (core + inner), 7 reserved. */
        VILLAGE_ROAD(1, 1, 0),
        /** Stone brick — 7 blocks placed (core + inner + edge), 7 reserved. */
        TOWN_ROAD(1, 1, 1),
        /** Wide primary road for capitals. */
        CAPITAL_ROAD(2, 1, 1);

        /** Half-width of each zone (blocks from center). */
        public final int coreHalf;
        public final int innerHalf;
        public final int edgeHalf;

        RoadTier(int coreHalf, int innerHalf, int edgeHalf) {
            this.coreHalf = coreHalf;
            this.innerHalf = innerHalf;
            this.edgeHalf = edgeHalf;
        }

        /** Total half-width of placed blocks. */
        public int placedHalfWidth() {
            return coreHalf + innerHalf + edgeHalf;
        }

        /** Reserved half-width for collision (always max). */
        public int reservedHalfWidth() {
            return 3; // 7 blocks total reserved for all tiers
        }
    }

    // =========================================================================
    // Per-position zone classification
    // =========================================================================

    /**
     * Determines which zone a position at distance {@code dist} from
     * the road centerline belongs to.
     *
     * @param dist absolute distance from road center (0 = center)
     * @param tier current road tier
     * @return the zone
     */
    public static Zone classify(int dist, RoadTier tier) {
        if (dist <= tier.coreHalf) return Zone.CORE;
        if (dist <= tier.coreHalf + tier.innerHalf) return Zone.INNER;
        if (dist <= tier.coreHalf + tier.innerHalf + tier.edgeHalf) return Zone.EDGE;
        return Zone.OUTSIDE;
    }

    // =========================================================================
    // Organic edge noise
    // =========================================================================

    /**
     * For an EDGE zone position, determines whether a road block should
     * be placed or left as terrain. Uses position-seeded noise so the
     * result is deterministic — the same world position always produces
     * the same result, which matters for weathering and upgrades.
     *
     * @param worldX  world X coordinate
     * @param worldZ  world Z coordinate
     * @param dist    distance from center
     * @param tier    road tier
     * @return true if a road block should be placed here
     */
    public static boolean shouldPlaceEdge(int worldX, int worldZ,
                                          int dist, RoadTier tier) {
        // Core and inner always place
        if (dist <= tier.coreHalf + tier.innerHalf) return true;

        // Edge blocks use position hash for organic pattern
        // Closer to inner = higher chance of placement
        int maxEdgeDist = tier.coreHalf + tier.innerHalf + tier.edgeHalf;
        float edgeProgress = (float)(dist - tier.coreHalf - tier.innerHalf)
                / Math.max(1, tier.edgeHalf);

        // Position hash: deterministic pseudo-noise
        long hash = positionHash(worldX, worldZ);
        float noise = (hash & 0xFFFF) / 65535.0f;

        // Threshold: 0.8 at inner edge, 0.3 at outer edge
        float threshold = 0.8f - edgeProgress * 0.5f;
        return noise < threshold;
    }

    /**
     * For an INNER zone position, determines whether to use core material
     * or edge material. Adds slight variation to the inner ring.
     *
     * @return true if core material, false if edge material
     */
    public static boolean isInnerCore(int worldX, int worldZ) {
        long hash = positionHash(worldX, worldZ);
        float noise = ((hash >> 16) & 0xFFFF) / 65535.0f;
        return noise < 0.85f; // 85% core material in inner zone
    }

    // =========================================================================
    // Position hash — deterministic noise for organic patterns
    // =========================================================================

    /**
     * Simple position hash for deterministic per-block variation.
     * Same position always produces same hash, so patterns are stable
     * across saves, upgrades, and weathering passes.
     */
    private static long positionHash(int x, int z) {
        long h = x * 73856093L ^ z * 19349663L;
        h = (h ^ (h >>> 16)) * 0x45d9f3bL;
        h = (h ^ (h >>> 16)) * 0x45d9f3bL;
        return h ^ (h >>> 16);
    }

    // =========================================================================
    // Mapping from VillagePath.PathTier to RoadTier
    // =========================================================================

    /**
     * Maps the village's path upgrade tier to the road placement tier.
     */
    public static RoadTier fromPathTier(VillagePath.PathTier pathTier) {
        return switch (pathTier) {
            case DIRT        -> RoadTier.FOOTPATH;
            case GRAVEL      -> RoadTier.VILLAGE_PATH;
            case COBBLESTONE -> RoadTier.VILLAGE_ROAD;
            case STONE_BRICK -> RoadTier.TOWN_ROAD;
        };
    }

    /**
     * Maps to a StreetTier for backward compatibility with existing systems.
     */
    public static RoadTier fromStreetTier(StreetTier streetTier) {
        return switch (streetTier) {
            case PRIMARY   -> RoadTier.VILLAGE_ROAD;
            case SECONDARY -> RoadTier.VILLAGE_PATH;
            case TERTIARY  -> RoadTier.FOOTPATH;
        };
    }
}