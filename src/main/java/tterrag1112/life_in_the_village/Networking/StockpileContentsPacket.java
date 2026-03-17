package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.StockpileScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record StockpileContentsPacket(
        String buildingName,
        List<ItemStack> stacks,
        List<Integer> counts
) implements CustomPacketPayload {

    public static final Type<StockpileContentsPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "stockpile_contents")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, StockpileContentsPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeUtf(packet.buildingName());
                        buf.writeVarInt(packet.stacks().size());
                        for (int i = 0; i < packet.stacks().size(); i++) {
                            ItemStack.STREAM_CODEC.encode(buf, packet.stacks().get(i));
                            buf.writeVarInt(packet.counts().get(i));
                        }
                    },
                    buf -> {
                        String name = buf.readUtf();
                        int size = buf.readVarInt();
                        List<ItemStack> stacks = new ArrayList<>();
                        List<Integer> counts = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            stacks.add(ItemStack.STREAM_CODEC.decode(buf));
                            counts.add(buf.readVarInt());
                        }
                        return new StockpileContentsPacket(name, stacks, counts);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(StockpileContentsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new StockpileScreen(packet.buildingName(), packet.stacks(), packet.counts())
                )
        );
    }
}
