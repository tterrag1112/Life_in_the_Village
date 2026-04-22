package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the {@link PathMaterial} for a {@link RoadEdge} by consulting the
 * edge's tier, endpoint villages' cultures, maintenance score, biome, and
 * current season.
 *
 * <h3>Culture determination per tier</h3>
 * <ul>
 *   <li><b>GREAT_ROAD</b> — always {@link PathMaterial#oldRealm()}, no culture.</li>
 *   <li><b>CONNECTOR</b> — culture of the first VILLAGE_DOCK endpoint village
 *       (or the village whose dock is on nodeA).</li>
 *   <li><b>TRUNK</b> — majority vote among {@code maintainerVillageIds} cultures.</li>
 *   <li><b>LOCAL</b> — same as CONNECTOR (owning village).</li>
 * </ul>
 *
 * <h3>Biome</h3>
 * Detected from the edge's first cell centre. Cross-biome edges use the
 * endpoint-village biome for village-owned edges, and the midpoint cell centre
 * for trunk/great roads.
 */
public final class EdgeMaterialResolver {

    private EdgeMaterialResolver() {}

    /**
     * Result record carrying both the resolved material and the culture string
     * (for passing to {@link UnifiedRoadPlacer} architectural detail passes).
     */
    public record MaterialContext(PathMaterial material, @Nullable String culture) {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Resolves the full material context for the given edge.
     */
    public static MaterialContext resolveForEdge(ServerLevel level,
                                                  RoadEdge edge,
                                                  WorldRoadGraph graph,
                                                  VillageSavedData data) {
        RoadEdge.EdgeTier tier = edge.getTier();

        // Great roads always use the Old Realm palette — no other overlays (invariant 3)
        if (tier == RoadEdge.EdgeTier.GREAT_ROAD) {
            return new MaterialContext(PathMaterial.oldRealm(), null);
        }

        // Determine biome from the edge's nodeA position (or midpoint for long edges)
        VillageBiomeStyle biome = detectBiome(level, edge, graph);

        // Determine culture
        String culture = cultureForEdge(edge, tier, graph, data);

        // Maintenance score
        int maintenance = edge.getMaintenance();

        // Current season
        SeasonTracker.Season season = SeasonTracker.currentSeason(level);

        // Road tier for material resolution
        RoadShape.RoadTier roadTier = PrimitiveChainBuilder.edgeTierToRoadTier(tier);

        PathMaterial material = PathMaterial.resolve(biome, culture, maintenance, roadTier, season);
        return new MaterialContext(material, culture);
    }

    // =========================================================================
    // Culture resolution
    // =========================================================================

    /**
     * Determines the culture string for the given edge.
     * Returns null for GREAT_ROAD (callers should use oldRealm() directly).
     */
    @Nullable
    public static String cultureForEdge(RoadEdge edge,
                                         RoadEdge.EdgeTier tier,
                                         WorldRoadGraph graph,
                                         VillageSavedData data) {
        return switch (tier) {
            case GREAT_ROAD -> null;
            case CONNECTOR, LOCAL -> cultureFromDockNode(edge, graph, data);
            case TRUNK -> cultureFromMaintainers(edge, data);
        };
    }

    /** Culture of the first VILLAGE_DOCK endpoint village, or null if none. */
    @Nullable
    private static String cultureFromDockNode(RoadEdge edge,
                                               WorldRoadGraph graph,
                                               VillageSavedData data) {
        for (UUID nodeId : List.of(edge.getNodeAId(), edge.getNodeBId())) {
            RoadNode node = graph.getNode(nodeId);
            if (node == null || node.type() != RoadNode.NodeType.VILLAGE_DOCK) continue;
            Village village = findVillageForDock(node.nodeId(), data);
            if (village != null) return village.getVillageType();
        }
        return null;
    }

    /** Majority-vote culture across all maintainer villages. */
    @Nullable
    private static String cultureFromMaintainers(RoadEdge edge, VillageSavedData data) {
        List<UUID> maintainers = edge.getMaintainerVillageIds();
        if (maintainers.isEmpty()) return null;

        Map<String, Integer> votes = new HashMap<>();
        for (UUID vid : maintainers) {
            Village v = data.getAllVillages().stream()
                    .filter(vv -> vv.getId().equals(vid))
                    .findFirst().orElse(null);
            if (v == null) continue;
            String culture = v.getVillageType();
            if (culture == null) culture = "default";
            votes.merge(culture, 1, Integer::sum);
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static Village findVillageForDock(UUID dockNodeId, VillageSavedData data) {
        return data.getAllVillages().stream()
                .filter(v -> v.getDockNodeId()
                        .filter(dockNodeId::equals).isPresent())
                .findFirst().orElse(null);
    }

    // =========================================================================
    // Biome detection
    // =========================================================================

    private static VillageBiomeStyle detectBiome(ServerLevel level,
                                                   RoadEdge edge,
                                                   WorldRoadGraph graph) {
        // Use nodeA position as the biome reference point
        RoadNode nodeA = graph.getNode(edge.getNodeAId());
        if (nodeA != null) {
            return VillageBiomeStyle.detect(level, nodeA.position());
        }
        // Fallback: use first cell centre
        if (!edge.getCellPath().isEmpty()) {
            long cellKey = edge.getCellPath().get(0);
            int bx = tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter
                    .cellKeyToBlockCenter(cellKey).getX();
            int bz = tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter
                    .cellKeyToBlockCenter(cellKey).getZ();
            int by = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    bx, bz);
            return VillageBiomeStyle.detect(level, new BlockPos(bx, by, bz));
        }
        return VillageBiomeStyle.PLAINS;
    }
}
