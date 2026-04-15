package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.ContinentMapScope;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.ContinentMapScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Economy.Trade.ClientTradeConnectionCache;
import tterrag1112.life_in_the_village.Village.Economy.Trade.MapRoadSnapshot;
import tterrag1112.life_in_the_village.Village.Economy.Trade.MapSeaRouteSnapshot;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.ClientAtlasCache;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Continent-scale map sync. Uses raw stream codecs (no NBT) so it
 * can carry up to {@code MAX_CONTINENT_CELLS} land cells + the
 * surrounding viewport without hitting per-field size limits.
 */
public record ContinentMapSyncPacket(
        UUID seedKingdomId,
        List<AtlasCell> cells,
        List<Long> continentCells,
        List<ContinentMapScope.ForeignClaimSnapshot> claims,
        List<MapRoadSnapshot> roads,
        List<MapSeaRouteSnapshot> seaRoutes,
        boolean prefillComplete
) implements CustomPacketPayload {

    public static final Type<ContinentMapSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "continent_map_sync"));

    /** Raw stream codec for a foreign claim snapshot. No NBT. */
    private static final StreamCodec<ByteBuf, ContinentMapScope.ForeignClaimSnapshot>
            CLAIM_STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ContinentMapScope.ForeignClaimSnapshot decode(ByteBuf buf) {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            int n = buf.readInt();
            List<Long> keys = new ArrayList<>(n);
            for (int i = 0; i < n; i++) keys.add(buf.readLong());
            return new ContinentMapScope.ForeignClaimSnapshot(id, keys);
        }
        @Override
        public void encode(ByteBuf buf, ContinentMapScope.ForeignClaimSnapshot s) {
            buf.writeLong(s.kingdomId().getMostSignificantBits());
            buf.writeLong(s.kingdomId().getLeastSignificantBits());
            buf.writeInt(s.cellKeys().size());
            for (Long k : s.cellKeys()) buf.writeLong(k);
        }
    };

    /** Raw stream codec for a Long list — used for continentCells. */
    private static final StreamCodec<ByteBuf, List<Long>> LONG_LIST_CODEC = new StreamCodec<>() {
        @Override
        public List<Long> decode(ByteBuf buf) {
            int n = buf.readInt();
            List<Long> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(buf.readLong());
            return out;
        }
        @Override
        public void encode(ByteBuf buf, List<Long> list) {
            buf.writeInt(list.size());
            for (Long v : list) buf.writeLong(v);
        }
    };

    public static final StreamCodec<ByteBuf, ContinentMapSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.fromCodec(UUIDUtil.CODEC),
                    ContinentMapSyncPacket::seedKingdomId,
                    ByteBufCodecs.collection(ArrayList::new, AtlasCell.STREAM_CODEC),
                    ContinentMapSyncPacket::cells,
                    LONG_LIST_CODEC,
                    ContinentMapSyncPacket::continentCells,
                    ByteBufCodecs.collection(ArrayList::new, CLAIM_STREAM_CODEC),
                    ContinentMapSyncPacket::claims,
                    ByteBufCodecs.collection(ArrayList::new, MapRoadSnapshot.STREAM_CODEC),
                    ContinentMapSyncPacket::roads,
                    ByteBufCodecs.collection(ArrayList::new, MapSeaRouteSnapshot.STREAM_CODEC),
                    ContinentMapSyncPacket::seaRoutes,
                    ByteBufCodecs.BOOL,
                    ContinentMapSyncPacket::prefillComplete,
                    ContinentMapSyncPacket::new
            );

    @Override
    public Type<ContinentMapSyncPacket> type() { return TYPE; }

    public static void handle(ContinentMapSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientAtlasCache.setCells(pkt.cells());
            ClientTradeConnectionCache.setRoads(pkt.roads());
            ClientTradeConnectionCache.setSeaRoutes(pkt.seaRoutes());

            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof ContinentMapScreen screen
                    && screen.getSeedKingdomId().equals(pkt.seedKingdomId())) {
                screen.onContinentSynced(pkt.continentCells(),
                        pkt.claims(), pkt.prefillComplete());
            }
        });
    }
}