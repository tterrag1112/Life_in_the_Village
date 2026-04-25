package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbInvocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client→server packet sent when the player presses a verb button on
 * the NPC profile screen. Carries the verb id and any string args
 * (e.g. compliment topic, ask-about subject UUID).
 *
 * <p>Coexists with the legacy {@link NpcProfileActionPacket} — the
 * verb pipeline is additive, the existing trade / guild / assign-work
 * actions keep their own packet path during the migration window.</p>
 */
public record PlayerVerbInvokePacket(UUID npcId, String verbId, Map<String, String> args)
        implements CustomPacketPayload {

    public PlayerVerbInvokePacket {
        if (verbId == null) verbId = "";
        if (args == null) args = Map.of();
        else args = Map.copyOf(args);
    }

    public static final Type<PlayerVerbInvokePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "player_verb_invoke"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerVerbInvokePacket> CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.npcId());
                        buf.writeUtf(pkt.verbId());
                        buf.writeVarInt(pkt.args().size());
                        pkt.args().forEach((k, v) -> {
                            buf.writeUtf(k);
                            buf.writeUtf(v);
                        });
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        String verbId = buf.readUtf();
                        int n = buf.readVarInt();
                        Map<String, String> args = new LinkedHashMap<>();
                        for (int i = 0; i < n; i++) {
                            String k = buf.readUtf();
                            String v = buf.readUtf();
                            args.put(k, v);
                        }
                        return new PlayerVerbInvokePacket(id, verbId, args);
                    });

    @Override public Type<?> type() { return TYPE; }

    public static void handle(PlayerVerbInvokePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            TownspersonMob npc = TownspersonMob.findByUUID(level, pkt.npcId()).orElse(null);
            if (npc == null) return;
            VerbInvocation.invoke(sp, npc, level, pkt.verbId(), new HashMap<>(pkt.args()));
        });
    }
}
