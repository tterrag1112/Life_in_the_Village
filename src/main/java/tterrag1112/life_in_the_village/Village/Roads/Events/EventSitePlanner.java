package tterrag1112.life_in_the_village.Village.Roads.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 10 — picks deterministic event placement sites along an edge or at a
 * node. Pure planner: it does not place blocks. {@link EventRealizer} consumes
 * its output.
 *
 * <h3>Determinism</h3>
 * Site selection seeds its RNG from {@code edgeId XOR worldSeed} (or
 * {@code nodeId XOR worldSeed}) so the same world always produces the same
 * events. Re-realisation of an edge after a stale-cell flag will produce the
 * same plan; the realiser uses this to avoid duplicates.
 *
 * <h3>Spacing</h3>
 * Within-type spacing is enforced after generation: candidates are sorted by
 * position along the path and any pair closer than {@code minSpacingBlocks}
 * collapses to the first.
 */
public final class EventSitePlanner {

    private EventSitePlanner() {}

    public record PlannedEvent(RoadEventType type, BlockPos sitePosition, RandomSource rng) {}

    // =========================================================================
    // Edge planner
    // =========================================================================

    public static List<PlannedEvent> planSitesForEdge(RoadEdge edge,
                                                      WorldRoadGraph graph,
                                                      ServerLevel level) {
        List<PlannedEvent> out = new ArrayList<>();
        List<BlockPos> path = edge.getBlockPath();
        if (path.size() < 2) return out;

        long worldSeed = level.getSeed();
        long edgeSeed = edge.getEdgeId().getMostSignificantBits()
                ^ edge.getEdgeId().getLeastSignificantBits()
                ^ worldSeed;

        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        WorldAtlas atlas = WorldAtlas.get(level);

        for (RoadEventType type : RoadEventRegistry.applicableTo(edge)) {
            if (type.worldUnique() && saved.isWorldUniquePlaced(type.typeId())) continue;

            int spacing = Math.max(8, type.minSpacingBlocks());
            float multiplier = type.rarity().targetDensityMultiplier();
            if (multiplier <= 0f) continue;

            // Walk the block path at fixed intervals; deterministic roll per slot.
            int accLen = 0;
            int slotIndex = 0;
            List<PlannedEvent> typeSites = new ArrayList<>();

            for (int i = 1; i < path.size(); i++) {
                BlockPos a = path.get(i - 1);
                BlockPos b = path.get(i);
                accLen += blockDistXZ(a, b);
                if (accLen < spacing) continue;
                accLen = 0;
                slotIndex++;

                // Biome filter (when set)
                if (!type.applicableBiomes().isEmpty()) {
                    AtlasCell cell = atlas.getCellAtBlock(b.getX(), b.getZ());
                    if (cell == null || !type.applicableBiomes().contains(cell.category())) continue;
                }

                long siteSeed = edgeSeed
                        ^ type.typeId().hashCode()
                        ^ ((long) slotIndex * 0x9E3779B97F4A7C15L);
                RandomSource rng = RandomSource.create(siteSeed);
                if (rng.nextFloat() >= multiplier) continue;

                typeSites.add(new PlannedEvent(type, b.immutable(), rng));
            }

            // Same-type spacing pass: drop any pair closer than spacing along path order.
            BlockPos last = null;
            for (PlannedEvent pe : typeSites) {
                if (last != null && distXZ(last, pe.sitePosition()) < spacing) continue;
                out.add(pe);
                last = pe.sitePosition();
            }
        }
        return out;
    }

    // =========================================================================
    // Node planner
    // =========================================================================

    public static List<PlannedEvent> planSitesForNode(RoadNode node,
                                                      WorldRoadGraph graph,
                                                      ServerLevel level) {
        List<PlannedEvent> out = new ArrayList<>();
        long worldSeed = level.getSeed();
        long nodeSeed = node.nodeId().getMostSignificantBits()
                ^ node.nodeId().getLeastSignificantBits()
                ^ worldSeed;

        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        WorldAtlas atlas = WorldAtlas.get(level);

        for (RoadEventType type : RoadEventRegistry.applicableTo(node)) {
            if (type.worldUnique() && saved.isWorldUniquePlaced(type.typeId())) continue;

            // Biome filter
            if (!type.applicableBiomes().isEmpty()) {
                AtlasCell cell = atlas.getCellAtBlock(
                        node.position().getX(), node.position().getZ());
                if (cell == null || !type.applicableBiomes().contains(cell.category())) continue;
            }

            // Node-attached events use a single deterministic roll per type.
            // Density: multiplier maps to a probability-per-node bucket
            // (COMMON 0.30, UNCOMMON 0.15, RARE 0.05, WORLD_UNIQUE handled below).
            long siteSeed = nodeSeed ^ type.typeId().hashCode();
            RandomSource rng = RandomSource.create(siteSeed);
            float p = switch (type.rarity()) {
                case COMMON       -> 0.30f;
                case UNCOMMON     -> 0.15f;
                case RARE         -> 0.05f;
                case WORLD_UNIQUE -> 1.00f; // deterministic; uniqueness enforced at registry/realiser
            };
            if (rng.nextFloat() >= p) continue;

            out.add(new PlannedEvent(type, node.position(), rng));
        }
        return out;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int blockDistXZ(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }

    private static int distXZ(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }
}
