package tterrag1112.life_in_the_village.Networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Life_in_the_village;

public record ManaPenaltyData(int penaltyAmount) implements CustomPacketPayload {
    public static final Type<ManaPenaltyData> TYPE = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "penalty_data"));
    public static final StreamCodec<ByteBuf, ManaPenaltyData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            ManaPenaltyData::penaltyAmount,

            ManaPenaltyData::new
    );


    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
