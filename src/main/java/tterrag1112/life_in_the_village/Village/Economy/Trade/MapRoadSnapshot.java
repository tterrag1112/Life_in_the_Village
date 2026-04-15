package tterrag1112.life_in_the_village.Village.Economy.Trade;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lightweight snapshot of a {@link TradeRoad} for map rendering.
 * Carries only the cell path and endpoints — not the full block list,
 * which can run to thousands of positions and blow past NBT size limits
 * when a map covers many roads.
 *
 * <p>Streams with raw field writes (no NBT) so a map packet can carry
 * arbitrarily many snapshots without hitting the 2 MB per-field
 * NBT cap.
 *
 * <p>If a road has no cell path (legacy pre-Phase-7a roads), callers
 * should derive a coarse path from the block list before sending.
 * See {@link #fromRoad}.
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
     * Build a snapshot from a server-side {@link TradeRoad}. Uses the
     * road's native cell path when available; otherwise downsamples the
     * block list to one cell per unique cell entered.
     */
    public static MapRoadSnapshot fromRoad(TradeRoad road) {
        List<Long> path;
        if (road.hasCellPath()) {
            path = new ArrayList<>(road.getCellPath());
        } else {
            path = new ArrayList<>();
            long last = Long.MIN_VALUE;
            for (var bp : road.getBlocks()) {
                long key = tterrag1112.life_in_the_village.World.Atlas
                        .AtlasCell.packKey(
                                bp.getX() >> 6, bp.getZ() >> 6);
                if (key != last) { path.add(key); last = key; }
            }
        }
        return new MapRoadSnapshot(
                road.getRoadId(), road.getVillageA(), road.getVillageB(), path);
    }
}