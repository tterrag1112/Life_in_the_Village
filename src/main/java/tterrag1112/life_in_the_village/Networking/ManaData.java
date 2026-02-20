package tterrag1112.life_in_the_village.Networking;


import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Life_in_the_village;

public record ManaData(int oldValue, int newValue) implements CustomPacketPayload {
    public static final Type<ManaData> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "mana_data"));

    public static final StreamCodec<ByteBuf, ManaData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            ManaData::oldValue,

            ByteBufCodecs.VAR_INT,
            ManaData::newValue,

            ManaData::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}