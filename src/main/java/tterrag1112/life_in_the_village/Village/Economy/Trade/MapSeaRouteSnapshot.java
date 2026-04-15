package tterrag1112.life_in_the_village.Village.Economy.Trade;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight snapshot of a {@link SeaRoute} for map rendering.
 * Carries only the cell path and endpoints. Sea routes are already
 * cell-path-native (no block list), but we still use a raw stream
 * codec to stay off NBT for consistency with {@link MapRoadSnapshot}.
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

    public static MapSeaRouteSnapshot fromSeaRoute(SeaRoute r) {
        return new MapSeaRouteSnapshot(
                r.getConnectionId(), r.getVillageA(), r.getVillageB(),
                new ArrayList<>(r.getCellPath()));
    }
}