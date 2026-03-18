package tterrag1112.life_in_the_village.Networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.List;

public record SyncKingdomPacket(List<Kingdom> kingdoms) implements CustomPacketPayload {

    public static final Type<SyncKingdomPacket> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "sync_kingdoms"));

    public static final StreamCodec<ByteBuf, SyncKingdomPacket>
            STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(Kingdom.CODEC.listOf()),
            SyncKingdomPacket::kingdoms,
            SyncKingdomPacket::new
    );

    @Override
    public Type<SyncKingdomPacket> type() { return TYPE; }

    public static void handle(SyncKingdomPacket packet,
                              IPayloadContext context) {
        context.enqueueWork(() ->
                Kingdom.ClientKingdomCache.setKingdoms(
                        packet.kingdoms()));
    }
}