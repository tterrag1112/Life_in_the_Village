package tterrag1112.life_in_the_village.Village.Economy.Trade;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight snapshot of a SEA-tier {@link RoadEdge} for map
 * rendering. Carries the cell path and endpoint village IDs; the
 * client renders the route as a line on the kingdom / continent map.
 *
 * <p>Track C3.3: pre-Phase 13 this snapshot was derived from a
 * {@code SeaRoute} record. Post-Phase 13 the snapshot is computed
 * server-side from a {@code RoadEdge} of tier
 * {@link RoadEdge.EdgeTier#SEA}; the wire shape is unchanged.
 */
public record MapSeaRouteSnapshot(
        UUID connectionId,
        UUID villageA,
        UUID villageB,
        List<Long> cellPath
) {

    public static final StreamCodec<ByteBuf, MapSeaRouteSnapshot> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MapSeaRouteSnapshot decode(ByteBuf buf) {
                    UUID cid = new UUID(buf.readLong(), buf.readLong());
                    UUID va  = new UUID(buf.readLong(), buf.readLong());
                    UUID vb  = new UUID(buf.readLong(), buf.readLong());
                    int n = buf.readInt();
                    List<Long> path = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) path.add(buf.readLong());
                    return new MapSeaRouteSnapshot(cid, va, vb, path);
                }
                @Override
                public void encode(ByteBuf buf, MapSeaRouteSnapshot s) {
                    buf.writeLong(s.connectionId.getMostSignificantBits());
                    buf.writeLong(s.connectionId.getLeastSignificantBits());
                    buf.writeLong(s.villageA.getMostSignificantBits());
                    buf.writeLong(s.villageA.getLeastSignificantBits());
                    buf.writeLong(s.villageB.getMostSignificantBits());
                    buf.writeLong(s.villageB.getLeastSignificantBits());
                    buf.writeInt(s.cellPath.size());
                    for (Long k : s.cellPath) buf.writeLong(k);
                }
            };

    /**
     * Track C3.3 — derive from a SEA-tier {@link RoadEdge}.
     * Maintainers' first two entries are treated as villageA / villageB
     * (mirrors the {@code TradeRouteManager.establishSeaRoutes} order;
     * the order doesn't affect rendering).
     */
    public static MapSeaRouteSnapshot fromEdge(RoadEdge edge) {
        List<UUID> maintainers = edge.getMaintainerVillageIds();
        UUID a = maintainers.size() > 0 ? maintainers.get(0) : new UUID(0, 0);
        UUID b = maintainers.size() > 1 ? maintainers.get(1) : new UUID(0, 0);
        return new MapSeaRouteSnapshot(
                edge.getEdgeId(), a, b,
                new ArrayList<>(edge.getCellPath()));
    }
}