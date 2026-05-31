package tterrag1112.life_in_the_village.Networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.KingdomBookScreen;
import tterrag1112.life_in_the_village.Gui.Map.Kingdom.KingdomMapScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Economy.Trade.ClientTradeConnectionCache;
import tterrag1112.life_in_the_village.Village.Economy.Trade.MapRoadSnapshot;
import tterrag1112.life_in_the_village.Village.Economy.Trade.MapSeaRouteSnapshot;
import tterrag1112.life_in_the_village.Village.Travel.ClientTravellerCache;
import tterrag1112.life_in_the_village.Village.Travel.TravellerSnapshot;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.ClientAtlasCache;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → client payload for kingdom map data. Uses raw stream codecs
 * on heavy lists ({@link AtlasCell}, {@link MapRoadSnapshot},
 * {@link MapSeaRouteSnapshot}) rather than routing through NBT —
 * NBT serialisation caps individual fields at 2 MB, which a
 * medium-sized kingdom's atlas data can exceed.
 */
public record KingdomMapSyncPacket(
        UUID kingdomId,
        List<AtlasCell> cells,
        List<MapRoadSnapshot> roads,
        List<MapSeaRouteSnapshot> seaRoutes,
        List<TravellerSnapshot> travellers   // Phase 5e — in-transit caravans
) implements CustomPacketPayload {

    public static final Type<KingdomMapSyncPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "kingdom_map_sync"));

    public static final StreamCodec<ByteBuf, KingdomMapSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.fromCodec(UUIDUtil.CODEC),
                    KingdomMapSyncPacket::kingdomId,
                    ByteBufCodecs.collection(ArrayList::new, AtlasCell.STREAM_CODEC),
                    KingdomMapSyncPacket::cells,
                    ByteBufCodecs.collection(ArrayList::new, MapRoadSnapshot.STREAM_CODEC),
                    KingdomMapSyncPacket::roads,
                    ByteBufCodecs.collection(ArrayList::new, MapSeaRouteSnapshot.STREAM_CODEC),
                    KingdomMapSyncPacket::seaRoutes,
                    ByteBufCodecs.collection(ArrayList::new, TravellerSnapshot.STREAM_CODEC),
                    KingdomMapSyncPacket::travellers,
                    KingdomMapSyncPacket::new
            );

    @Override
    public Type<KingdomMapSyncPacket> type() { return TYPE; }

    public static void handle(KingdomMapSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientAtlasCache.setCells(pkt.cells());
            ClientTradeConnectionCache.setRoads(pkt.roads());
            ClientTradeConnectionCache.setSeaRoutes(pkt.seaRoutes());
            ClientTravellerCache.setTravellers(pkt.travellers());

            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof KingdomBookScreen book
                    && book.getKingdomId().equals(pkt.kingdomId())) {
                book.onMapDataSynced();
            } else if (mc.screen instanceof KingdomMapScreen map) {
                map.onMapDataSynced();
            }
        });
    }
}