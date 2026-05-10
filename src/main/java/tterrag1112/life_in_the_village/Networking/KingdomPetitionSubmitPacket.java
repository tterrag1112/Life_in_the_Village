package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Kingdom.Audience.AudienceDriver;
import tterrag1112.life_in_the_village.Kingdom.Audience.PetitionPayload;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Track D3.5B — client → server packet for submitting a petition
 * to a kingdom's audience chamber.
 *
 * <p>Carries the full {@link PetitionPayload} so all five kinds
 * (TREATY_RATIFICATION / CHARTER_REQUEST / AUDIENCE_GRIEVANCE /
 * WAR_DECLARATION / BREAK_TREATY) can be submitted from the
 * audience screen. The payload is serialized via the
 * {@link PetitionPayload#CODEC} into NBT bytes — that's the
 * smallest path that keeps the dispatched-union codec working
 * across the wire.
 *
 * <p>Slice A's {@link KingdomPetitionPacket} stays as the
 * resolve / withdraw verbs; this packet handles only submit.
 */
public record KingdomPetitionSubmitPacket(
        UUID kingdomId,
        byte[] payloadBytes
) implements CustomPacketPayload {

    public static final Type<KingdomPetitionSubmitPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "kingdom_petition_submit"));

    public static final StreamCodec<RegistryFriendlyByteBuf,
            KingdomPetitionSubmitPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.kingdomId());
                buf.writeByteArray(pkt.payloadBytes());
            },
            buf -> new KingdomPetitionSubmitPacket(
                    buf.readUUID(),
                    buf.readByteArray()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Encodes a {@link PetitionPayload} into the byte form this
     * packet expects. Returns empty bytes on encode failure (caller
     * should treat as a malformed petition).
     */
    public static byte[] encodePayload(PetitionPayload payload) {
        DataResult<Tag> tag = PetitionPayload.CODEC.encodeStart(NbtOps.INSTANCE, payload);
        Tag rootTag = tag.result().orElse(null);
        if (rootTag == null) return new byte[0];
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                if (rootTag instanceof net.minecraft.nbt.CompoundTag c) {
                    NbtIo.write(c, dos);
                } else {
                    // Non-compound root: wrap so NbtIo can write it.
                    net.minecraft.nbt.CompoundTag wrapper = new net.minecraft.nbt.CompoundTag();
                    wrapper.put("p", rootTag);
                    NbtIo.write(wrapper, dos);
                }
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    /** Decodes the bytes back into a {@link PetitionPayload}. */
    public PetitionPayload decodePayload() {
        if (payloadBytes.length == 0) return null;
        try (DataInputStream dis = new DataInputStream(
                new ByteArrayInputStream(payloadBytes))) {
            net.minecraft.nbt.CompoundTag root =
                    NbtIo.read(dis, NbtAccounter.unlimitedHeap());
            Tag actual = root.contains("p") ? root.get("p") : root;
            DataResult<PetitionPayload> result = PetitionPayload.CODEC
                    .parse(new Dynamic<>(NbtOps.INSTANCE, actual));
            return result.result().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static void handle(KingdomPetitionSubmitPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) ctx.player();
            ServerLevel level   = (ServerLevel) player.level();
            VillageSavedData data = VillageSavedData.get(level);
            Kingdom kingdom = data.getKingdomById(pkt.kingdomId()).orElse(null);
            if (kingdom == null) return;

            PetitionPayload payload = pkt.decodePayload();
            if (payload == null) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Malformed petition payload."), false);
                return;
            }

            UUID petitionId = AudienceDriver.submit(level, data, kingdom,
                    player.getUUID(), payload, level.getGameTime());
            data.setDirty();
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "Petition submitted ("
                                    + petitionId.toString().substring(0, 8) + ")"),
                    false);
        });
    }
}
