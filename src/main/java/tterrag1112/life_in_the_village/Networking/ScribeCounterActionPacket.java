package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeCounter;

import java.util.UUID;

/**
 * Client → server: submit one of the three ScribeCounter tabs.
 *
 * <p>Tabs are discriminated by {@link Tab}. Fields not used by the
 * selected tab carry zero / empty values and are ignored server-side.
 * The server validates inputs, charges the fee, and either:</p>
 * <ul>
 *   <li>JOB_CONTRACT — mints the item directly with the assembled
 *       {@code JobContractTerms} component and hands to the player.</li>
 *   <li>LETTER — enqueues a {@code ScribeCommission} for the workshop;
 *       the NPC's {@code ScribeWorkGoal} mints the
 *       {@code WRITTEN_LETTER} when it cycles.</li>
 *   <li>BOOK_COPY — enqueues a {@code ScribeCommission} for the
 *       workshop. The fast route remains the held-source-book physical
 *       path in {@code NpcInteractionHandler}.</li>
 * </ul>
 */
public record ScribeCounterActionPacket(
        UUID scribeId,
        Tab tab,
        // JOB_CONTRACT fields
        String professionName,
        String rankName,
        long salaryBronze,
        String targetItemId,
        int durationDays,
        // LETTER fields
        String recipientName,
        String body,
        // BOOK_COPY fields
        String sourceBookId
) implements CustomPacketPayload {

    public enum Tab { JOB_CONTRACT, LETTER, BOOK_COPY }

    public static final Type<ScribeCounterActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "scribe_counter_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScribeCounterActionPacket> CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeUUID(p.scribeId);
                        buf.writeUtf(p.tab.name());
                        buf.writeUtf(p.professionName);
                        buf.writeUtf(p.rankName);
                        buf.writeLong(p.salaryBronze);
                        buf.writeUtf(p.targetItemId);
                        buf.writeVarInt(p.durationDays);
                        buf.writeUtf(p.recipientName);
                        buf.writeUtf(p.body);
                        buf.writeUtf(p.sourceBookId);
                    },
                    buf -> {
                        UUID id = buf.readUUID();
                        Tab tab;
                        try { tab = Tab.valueOf(buf.readUtf()); }
                        catch (IllegalArgumentException e) { tab = Tab.JOB_CONTRACT; }
                        return new ScribeCounterActionPacket(id, tab,
                                buf.readUtf(), buf.readUtf(), buf.readLong(),
                                buf.readUtf(), buf.readVarInt(),
                                buf.readUtf(), buf.readUtf(),
                                buf.readUtf());
                    });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ScribeCounterActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel level)) return;
            ScribeCounter.handle(pkt, sp, level);
        });
    }
}
