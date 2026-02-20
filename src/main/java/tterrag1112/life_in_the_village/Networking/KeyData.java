package tterrag1112.life_in_the_village.Networking;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

public record KeyData(String sender, int keyCode) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<KeyData> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "key_data"));

    public static final StreamCodec<ByteBuf, KeyData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, KeyData::sender,
            ByteBufCodecs.INT,         KeyData::keyCode,
            KeyData::new
    );

    @Override
    public CustomPacketPayload.Type<KeyData> type() {
        return TYPE;
    }

    // This runs on the SERVER
    public static void handle(KeyData data, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            ServerLevel level = (ServerLevel) player.level();
            BlockPos pos = player.getOnPos();

            if (data.keyCode() == 13) {
                //X Key
                Optional<Building> current = VillageSavedData.get(level).getBuildingAt(pos);
                VillageSavedData data2 = VillageSavedData.get(level);


                if (current.isPresent()) {
                    current.get().upgrade(level);
                } else {
                    player.sendSystemMessage(
                            Component.literal("You are not inside any building.")
                    );
                }
                PacketDistributor.sendToPlayer(player, new SyncBuildingsPacket(data2.getAllBuildings(), data2.getAllVillages()));
            }

            if (data.keyCode() == 67) {
                // V key — check if player is in a building
                Optional<Building> current = VillageSavedData.get(level).getBuildingAt(pos);
                Optional<Village> currentVillage = VillageSavedData.get(level).getVillageAt(pos);

                if (currentVillage.isPresent()) {
                    // Send a message back to the client
                    player.sendSystemMessage(
                            Component.literal("You are inside: " + currentVillage.get().getName())
                    );
                }
                if(current.isPresent()){
                    player.sendSystemMessage(
                            Component.literal("You are inside: " + current.get().getName())
                    );
                }
                else {
                    player.sendSystemMessage(
                            Component.literal("You are not inside any building.")
                    );
                }

            }

            if (data.keyCode() == 42) {
                //X Key
                Optional<Building> current = VillageSavedData.get(level).getBuildingAt(pos);
                VillageSavedData data2 = VillageSavedData.get(level);


                if (current.isPresent()) {
                    current.get().downgrade(level);
                } else {
                    player.sendSystemMessage(
                            Component.literal("You are not inside any building.")
                    );
                }
                PacketDistributor.sendToPlayer(player, new SyncBuildingsPacket(data2.getAllBuildings(), data2.getAllVillages()));
            }

    });
}
}

