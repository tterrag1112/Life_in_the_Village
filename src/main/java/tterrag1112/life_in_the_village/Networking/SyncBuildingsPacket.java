package tterrag1112.life_in_the_village.Networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

public record SyncBuildingsPacket(List<Building> buildings, List<Village> villages) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncBuildingsPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "sync_buildings")
            );

    public static final StreamCodec<ByteBuf, SyncBuildingsPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(Building.CODEC.listOf()), SyncBuildingsPacket::buildings,
            ByteBufCodecs.fromCodec(Village.CODEC.listOf()), SyncBuildingsPacket::villages,
            SyncBuildingsPacket::new
    );

    @Override
    public Type<SyncBuildingsPacket> type() { return TYPE; }

    public static void handle(SyncBuildingsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Building.ClientBuildingCache.setBuildings(packet.buildings());
            Building.ClientBuildingCache.setVillages(packet.villages());
        });
    }
}