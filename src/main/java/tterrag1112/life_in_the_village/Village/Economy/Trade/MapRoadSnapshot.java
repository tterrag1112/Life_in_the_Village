package tterrag1112.life_in_the_village.Village.Economy.Trade;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight snapshot of a land trade route for map rendering. Carries
 * the route ID, the two endpoint villages, and the concatenated cell path
 * derived from the route's graph edges.
 *
 * <p>Streams with raw field writes (no NBT) so a map packet can carry
 * arbitrarily many snapshots without hitting the 2 MB per-field NBT cap.
 */
public record MapRoadSnapshot(
        UUID roadId,
        UUID villageA,
        UUID villageB,
        List<Long> cellPath
) {

    public static final StreamCodec<ByteBuf, MapRoadSnapshot> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MapRoadSnapshot decode(ByteBuf buf) {
                    UUID roadId   = new UUID(buf.readLong(), buf.readLong());
                    UUID villageA = new UUID(buf.readLong(), buf.readLong());
                    UUID villageB = new UUID(buf.readLong(), buf.readLong());
                    int n = buf.readInt();
                    List<Long> path = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) path.add(buf.readLong());
                    return new MapRoadSnapshot(roadId, villageA, villageB, path);
                }

                @Override
                public void encode(ByteBuf buf, MapRoadSnapshot s) {
                    buf.writeLong(s.roadId.getMostSignificantBits());
                    buf.writeLong(s.roadId.getLeastSignificantBits());
                    buf.writeLong(s.villageA.getMostSignificantBits());
                    buf.writeLong(s.villageA.getLeastSignificantBits());
                    buf.writeLong(s.villageB.getMostSignificantBits());
                    buf.writeLong(s.villageB.getLeastSignificantBits());
                    buf.writeInt(s.cellPath.size());
                    for (Long k : s.cellPath) buf.writeLong(k);
                }
            };

    /**
     * Builds a snapshot for a graph-backed land route. Concatenates the
     * cellPath of each edge in traversal order, deduplicating consecutive
     * shared cells at edge joins.
     */
    public static MapRoadSnapshot fromRoute(TradeRoute route, WorldRoadGraph graph) {
        LinkedHashSet<Long> cells = new LinkedHashSet<>();
        for (UUID edgeId : route.getEdgeIds()) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            cells.addAll(edge.getCellPath());
        }
        return new MapRoadSnapshot(
                route.getRouteId(), route.getVillageA(), route.getVillageB(),
                new ArrayList<>(cells));
    }
}
