package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Lighting.RoadLightingProfile;
import tterrag1112.life_in_the_village.Village.Village;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the {@link CulturePalette} and {@link RoadLightingProfile} for a
 * road edge. Central place so realisation, lighting, and debug reporting all
 * pick the same palette for the same edge.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li>{@code GREAT_ROAD} — Old Realm palette by default; imperial alternate
 *       if the nearest culture flags {@link CulturePalette#isGreatRoadAlternate()}.</li>
 *   <li>{@code TRUNK} / {@code CONNECTOR} / {@code LOCAL} — maintainer village's
 *       kingdom culture if available; endpoint VILLAGE_DOCK's culture otherwise;
 *       "default" as final fallback.</li>
 * </ol>
 *
 * <h3>Lighting override</h3>
 * If the edge has a {@link RoadEdge#getLightingOverride()}, it wins over the
 * palette's default lighting profile. The palette itself is not overridden —
 * lights still use the culture's light blocks.
 */
public final class CulturePaletteResolver {

    private CulturePaletteResolver() {}

    public record Resolved(
            CulturePalette palette,
            RoadLightingProfile lighting,
            String cultureId
    ) {}

    // =========================================================================
    // Public API
    // =========================================================================

    public static Resolved resolve(RoadEdge edge,
                                    WorldRoadGraph graph,
                                    VillageSavedData data) {
        String cultureId = cultureForEdge(edge, graph, data);
        CulturePalette palette = (edge.getTier() == RoadEdge.EdgeTier.GREAT_ROAD)
                ? PaletteRegistry.greatRoadPalette(cultureId)
                : PaletteRegistry.forCulture(cultureId);

        RoadLightingProfile lighting = edge.getLightingOverride()
                .orElse(palette.defaultLighting());

        return new Resolved(palette, lighting, palette.cultureId());
    }

    // =========================================================================
    // Culture determination
    // =========================================================================

    /**
     * Resolves a culture id string for the edge. Returns {@code "default"} when
     * nothing is found — never {@code null}, to match the palette registry's
     * "default" fallback semantics.
     */
    public static String cultureForEdge(RoadEdge edge,
                                         WorldRoadGraph graph,
                                         VillageSavedData data) {
        switch (edge.getTier()) {
            case GREAT_ROAD -> {
                // Great roads have no maintainer (invariant 4). Pick the culture
                // of the nearest village's kingdom, if any.
                BlockPos midpoint = edgeMidpoint(edge, graph);
                if (midpoint == null) return "default";
                Village nearest = nearestVillage(data, midpoint);
                if (nearest == null) return "default";
                return cultureForVillage(data, nearest);
            }
            case TRUNK -> {
                // Majority-vote across maintainer villages by kingdom culture.
                String c = majorityCultureForVillages(edge.getMaintainerVillageIds(), data);
                if (c != null) return c;
                // Fallback: endpoint dock culture
                String dock = dockCulture(edge, graph, data);
                return dock != null ? dock : "default";
            }
            case CONNECTOR, LOCAL -> {
                // Look up maintainer first, then endpoint dock.
                if (!edge.getMaintainerVillageIds().isEmpty()) {
                    String c = majorityCultureForVillages(edge.getMaintainerVillageIds(), data);
                    if (c != null) return c;
                }
                String dock = dockCulture(edge, graph, data);
                return dock != null ? dock : "default";
            }
        }
        return "default";
    }

    @Nullable
    private static String dockCulture(RoadEdge edge,
                                       WorldRoadGraph graph,
                                       VillageSavedData data) {
        for (UUID nodeId : List.of(edge.getNodeAId(), edge.getNodeBId())) {
            RoadNode node = graph.getNode(nodeId);
            if (node == null || node.type() != RoadNode.NodeType.VILLAGE_DOCK) continue;
            Village v = data.getAllVillages().stream()
                    .filter(vv -> vv.getDockNodeId().filter(node.nodeId()::equals).isPresent())
                    .findFirst().orElse(null);
            if (v != null) return cultureForVillage(data, v);
        }
        return null;
    }

    @Nullable
    private static String majorityCultureForVillages(List<UUID> villageIds,
                                                      VillageSavedData data) {
        if (villageIds.isEmpty()) return null;
        java.util.Map<String, Integer> votes = new java.util.HashMap<>();
        for (UUID vid : villageIds) {
            Village v = data.getAllVillages().stream()
                    .filter(vv -> vv.getId().equals(vid)).findFirst().orElse(null);
            if (v == null) continue;
            String c = cultureForVillage(data, v);
            votes.merge(c, 1, Integer::sum);
        }
        return votes.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Returns the kingdom's culture if the village is claimed, otherwise falls
     * back to the village's own villageType (for unclaimed villages).
     */
    private static String cultureForVillage(VillageSavedData data, Village village) {
        Optional<Kingdom> k = data.getKingdomForVillage(village.getId());
        if (k.isPresent() && k.get().getCulture() != null) return k.get().getCulture();
        String vt = village.getVillageType();
        return (vt == null || vt.isEmpty()) ? "default" : vt;
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    @Nullable
    private static BlockPos edgeMidpoint(RoadEdge edge, WorldRoadGraph graph) {
        if (!edge.getBlockPath().isEmpty()) {
            return edge.getBlockPath().get(edge.getBlockPath().size() / 2);
        }
        if (!edge.getCellPath().isEmpty()) {
            long midKey = edge.getCellPath().get(edge.getCellPath().size() / 2);
            return AtlasRouteRouter.cellKeyToBlockCenter(midKey);
        }
        RoadNode a = graph.getNode(edge.getNodeAId());
        RoadNode b = graph.getNode(edge.getNodeBId());
        if (a != null && b != null) {
            return new BlockPos(
                    (a.position().getX() + b.position().getX()) / 2,
                    (a.position().getY() + b.position().getY()) / 2,
                    (a.position().getZ() + b.position().getZ()) / 2);
        }
        return null;
    }

    @Nullable
    private static Village nearestVillage(VillageSavedData data, BlockPos point) {
        Village best = null;
        long bestDist = Long.MAX_VALUE;
        for (Village v : data.getAllVillages()) {
            BlockPos anchor = v.getAnchorPos();
            if (anchor == null) continue;
            long dx = anchor.getX() - point.getX();
            long dz = anchor.getZ() - point.getZ();
            long d2 = dx * dx + dz * dz;
            if (d2 < bestDist) {
                bestDist = d2;
                best = v;
            }
        }
        return best;
    }
}
