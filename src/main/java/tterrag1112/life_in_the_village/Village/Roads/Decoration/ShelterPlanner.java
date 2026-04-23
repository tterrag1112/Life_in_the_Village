package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plans shelter placement positions along long {@link RoadEdge.EdgeTier#GREAT_ROAD}
 * and {@link RoadEdge.EdgeTier#TRUNK} stretches.
 *
 * <h3>Placement rules</h3>
 * <ul>
 *   <li>Only GREAT_ROAD and TRUNK edges qualify.</li>
 *   <li>The edge's block path must be at least 800 blocks long.</li>
 *   <li>The first 200 and last 200 blocks of path are excluded (endpoint buffer —
 *       avoids placing shelters inside village docking zones).</li>
 *   <li>Spacing between successive shelters is 400–600 blocks, seeded from the
 *       edge's meander seed so re-planning always produces the same layout.</li>
 *   <li>Each shelter is placed 4 blocks perpendicular to the road (alternating sides)
 *       so it sits beside the road surface, not on it.</li>
 * </ul>
 *
 * <h3>Type selection</h3>
 * GREAT_ROAD edges favour {@link ShelterType#INN} and {@link ShelterType#CARAVANSERAI}
 * (trade-route destinations). TRUNK edges favour {@link ShelterType#RUINED_WAYSTATION},
 * {@link ShelterType#ROADSIDE_SHRINE}, and {@link ShelterType#RUINED_WATCHTOWER}
 * (frontier / regional character).
 */
public final class ShelterPlanner {

    /** Minimum block-path length for an edge to receive any shelters. */
    public static final int MIN_PATH_LENGTH = 800;
    /** Blocks from each endpoint excluded from shelter placement. */
    public static final int ENDPOINT_BUFFER = 200;
    /** Minimum spacing between two successive shelters. */
    public static final int MIN_SPACING = 400;
    /** Maximum spacing between two successive shelters. */
    public static final int MAX_SPACING = 600;
    /** Perpendicular offset from road centerline (blocks). */
    private static final int PERP_OFFSET = 4;

    // =========================================================================
    // Types
    // =========================================================================

    /** Categories of waypoint shelter that appear along major roads. */
    public enum ShelterType {
        /** Multi-room inn with beds and a hearth. */
        INN,
        /** Walled courtyard shelter for caravans and pack animals. */
        CARAVANSERAI,
        /** Small stone shrine or roadside chapel. */
        ROADSIDE_SHRINE,
        /** Collapsed Old Realm watchtower, still partially roofed. */
        RUINED_WATCHTOWER,
        /** Crumbling waystation with a surviving covered area. */
        RUINED_WAYSTATION;

        public static final Codec<ShelterType> CODEC =
                Codec.STRING.xmap(ShelterType::valueOf, ShelterType::name);
    }

    /**
     * A planned shelter position produced by {@link #plan}. Immutable.
     *
     * @param pathIndex   Index into the edge's blockPath at which this shelter
     *                    falls (before perpendicular offset is applied).
     * @param position    World position of the shelter's origin block (after offset).
     * @param type        Which kind of shelter to build here.
     * @param facingDx    X component of the road-forward direction at this point
     *                    (not normalized, sign only).
     * @param facingDz    Z component of the road-forward direction at this point.
     */
    public record ShelterPlan(int pathIndex, BlockPos position, ShelterType type,
                               int facingDx, int facingDz) {}

    private ShelterPlanner() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Plans shelter positions along the given edge.  Returns an empty list if the
     * edge does not qualify (wrong tier, path too short, etc.).
     *
     * <p>The result is deterministic: identical inputs always produce identical
     * outputs. Call once per realization and persist the resulting
     * {@link ShelterInstance}s in {@code WorldRoadSavedData}.
     */
    public static List<ShelterPlan> plan(RoadEdge edge) {
        if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD
                && edge.getTier() != RoadEdge.EdgeTier.TRUNK) {
            return List.of();
        }

        List<BlockPos> path = edge.getBlockPath();
        if (path.size() < MIN_PATH_LENGTH) return List.of();

        Random rng = new Random(edge.getMeanderProfile().seed() ^ 0xCAFEF00DL);
        List<ShelterPlan> plans = new ArrayList<>();

        int accLen     = 0;
        int nextTarget = ENDPOINT_BUFFER + MIN_SPACING + rng.nextInt(MAX_SPACING - MIN_SPACING);
        int sideSign   = 1; // alternates ±1 for perpendicular offset

        for (int i = 1; i < path.size() - ENDPOINT_BUFFER; i++) {
            if (i < ENDPOINT_BUFFER) continue;

            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            accLen += blockDist(a, b);

            if (accLen < nextTarget) continue;

            // Road direction at this point
            int rdx = b.getX() - a.getX();
            int rdz = b.getZ() - a.getZ();
            double len = Math.max(1.0, Math.sqrt((double) rdx * rdx + (double) rdz * rdz));

            // Perpendicular (rotate road direction 90°)
            double pdx = -rdz / len;
            double pdz =  rdx / len;

            int ox = (int) Math.round(pdx * PERP_OFFSET * sideSign);
            int oz = (int) Math.round(pdz * PERP_OFFSET * sideSign);
            BlockPos planPos = new BlockPos(b.getX() + ox, b.getY(), b.getZ() + oz);

            ShelterType type = pickType(edge.getTier(), rng);
            plans.add(new ShelterPlan(i, planPos, type,
                    Integer.signum(rdx), Integer.signum(rdz)));

            sideSign   = -sideSign;
            accLen     = 0;
            nextTarget = MIN_SPACING + rng.nextInt(MAX_SPACING - MIN_SPACING);
        }

        return plans;
    }

    // =========================================================================
    // Type selection
    // =========================================================================

    private static ShelterType pickType(RoadEdge.EdgeTier tier, Random rng) {
        if (tier == RoadEdge.EdgeTier.GREAT_ROAD) {
            // GREAT_ROAD: 35% INN, 30% CARAVANSERAI, 15% SHRINE, 10% TOWER, 10% WAYSTATION
            int roll = rng.nextInt(100);
            if (roll < 35) return ShelterType.INN;
            if (roll < 65) return ShelterType.CARAVANSERAI;
            if (roll < 80) return ShelterType.ROADSIDE_SHRINE;
            if (roll < 90) return ShelterType.RUINED_WATCHTOWER;
            return ShelterType.RUINED_WAYSTATION;
        } else {
            // TRUNK: 20% INN, 15% CARAVANSERAI, 25% SHRINE, 20% TOWER, 20% WAYSTATION
            int roll = rng.nextInt(100);
            if (roll < 20) return ShelterType.INN;
            if (roll < 35) return ShelterType.CARAVANSERAI;
            if (roll < 60) return ShelterType.ROADSIDE_SHRINE;
            if (roll < 80) return ShelterType.RUINED_WATCHTOWER;
            return ShelterType.RUINED_WAYSTATION;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int blockDist(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }
}
