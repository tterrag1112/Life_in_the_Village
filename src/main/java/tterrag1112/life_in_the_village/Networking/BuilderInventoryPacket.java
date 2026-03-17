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

public record BuilderInventoryPacket(
        List<ItemStack> stacks,
        List<Integer> counts
) implements CustomPacketPayload {

    public static final Type<BuilderInventoryPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "builder_inventory")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderInventoryPacket> CODEC =
            StreamCodec.of(
                    (buf, packet) -> {
                        buf.writeVarInt(packet.stacks().size());
                        for (int i = 0; i < packet.stacks().size(); i++) {
                            ItemStack.STREAM_CODEC.encode(buf, packet.stacks().get(i));
                            buf.writeVarInt(packet.counts().get(i));
                        }
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        List<ItemStack> stacks = new ArrayList<>();
                        List<Integer> counts = new ArrayList<>();
                        for (int i = 0; i < size; i++) {
                            stacks.add(ItemStack.STREAM_CODEC.decode(buf));
                            counts.add(buf.readVarInt());
                        }
                        return new BuilderInventoryPacket(stacks, counts);
                    }
            );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(BuilderInventoryPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
                net.minecraft.client.Minecraft.getInstance().setScreen(
                        new StockpileScreen("Builder Inventory", packet.stacks(), packet.counts())
                )
        );
    }
}
