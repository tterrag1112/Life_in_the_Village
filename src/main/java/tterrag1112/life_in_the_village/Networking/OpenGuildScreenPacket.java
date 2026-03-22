// src/main/java/tterrag1112/life_in_the_village/Networking/OpenGuildScreenPacket.java
package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Gui.GuildScreen;
import tterrag1112.life_in_the_village.Life_in_the_village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record OpenGuildScreenPacket(
        UUID   guildId,
        String villageName,
        boolean registered,
        String rankName,
        int    xp,
        int    xpToNext,
        int    questsCompleted,
        List<QuestEntry>       availableQuests,
        List<QuestEntry>       activeQuests,
        List<PartyMemberEntry> partyMembers,
        List<RosterEntry>      roster
) implements CustomPacketPayload {

    // ── Nested records ────────────────────────────────────────────────────────

    public record QuestEntry(
            UUID questId, String title, String description,
            String type, String difficulty,
            long coinReward, int xpReward, String status) {}

    public record PartyMemberEntry(
            UUID npcId, String name, String roleName,
            int level, int kills, boolean isAlive) {}

    public record RosterEntry(
            UUID playerId, String playerName,
            String rankName, int xp, int questsCompleted) {}

    // ── Type ──────────────────────────────────────────────────────────────────

    public static final Type<OpenGuildScreenPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "open_guild_screen")
    );

    // ── StreamCodec ───────────────────────────────────────────────────────────

    public static final StreamCodec<RegistryFriendlyByteBuf,
            OpenGuildScreenPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.guildId());
                buf.writeUtf(pkt.villageName());
                buf.writeBoolean(pkt.registered());
                buf.writeUtf(pkt.rankName());
                buf.writeVarInt(pkt.xp());
                buf.writeVarInt(pkt.xpToNext());
                buf.writeVarInt(pkt.questsCompleted());

                writeQuestList(buf, pkt.availableQuests());
                writeQuestList(buf, pkt.activeQuests());

                buf.writeVarInt(pkt.partyMembers().size());
                for (PartyMemberEntry m : pkt.partyMembers()) {
                    buf.writeUUID(m.npcId());
                    buf.writeUtf(m.name());
                    buf.writeUtf(m.roleName());
                    buf.writeVarInt(m.level());
                    buf.writeVarInt(m.kills());
                    buf.writeBoolean(m.isAlive());
                }

                buf.writeVarInt(pkt.roster().size());
                for (RosterEntry r : pkt.roster()) {
                    buf.writeUUID(r.playerId());
                    buf.writeUtf(r.playerName());
                    buf.writeUtf(r.rankName());
                    buf.writeVarInt(r.xp());
                    buf.writeVarInt(r.questsCompleted());
                }
            },
            buf -> {
                UUID    guildId         = buf.readUUID();
                String  villageName     = buf.readUtf();
                boolean registered      = buf.readBoolean();
                String  rankName        = buf.readUtf();
                int     xp              = buf.readVarInt();
                int     xpToNext        = buf.readVarInt();
                int     questsCompleted = buf.readVarInt();

                List<QuestEntry> available = readQuestList(buf);
                List<QuestEntry> active    = readQuestList(buf);

                int pmCount = buf.readVarInt();
                List<PartyMemberEntry> partyMembers = new ArrayList<>(pmCount);
                for (int i = 0; i < pmCount; i++) {
                    partyMembers.add(new PartyMemberEntry(
                            buf.readUUID(), buf.readUtf(), buf.readUtf(),
                            buf.readVarInt(), buf.readVarInt(),
                            buf.readBoolean()));
                }

                int rCount = buf.readVarInt();
                List<RosterEntry> roster = new ArrayList<>(rCount);
                for (int i = 0; i < rCount; i++) {
                    roster.add(new RosterEntry(
                            buf.readUUID(), buf.readUtf(), buf.readUtf(),
                            buf.readVarInt(), buf.readVarInt()));
                }

                return new OpenGuildScreenPacket(guildId, villageName,
                        registered, rankName, xp, xpToNext, questsCompleted,
                        available, active, partyMembers, roster);
            }
    );

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void writeQuestList(RegistryFriendlyByteBuf buf,
                                       List<QuestEntry> list) {
        buf.writeVarInt(list.size());
        for (QuestEntry q : list) {
            buf.writeUUID(q.questId());
            buf.writeUtf(q.title());
            buf.writeUtf(q.description());
            buf.writeUtf(q.type());
            buf.writeUtf(q.difficulty());
            buf.writeVarLong(q.coinReward());
            buf.writeVarInt(q.xpReward());
            buf.writeUtf(q.status());
        }
    }

    private static List<QuestEntry> readQuestList(
            RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<QuestEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new QuestEntry(
                    buf.readUUID(), buf.readUtf(), buf.readUtf(),
                    buf.readUtf(), buf.readUtf(),
                    buf.readVarLong(), buf.readVarInt(),
                    buf.readUtf()));
        }
        return list;
    }

    // ── Interface ─────────────────────────────────────────────────────────────

    @Override
    public Type<?> type() { return TYPE; }

    // ── Client handler ────────────────────────────────────────────────────────

    public static void handle(OpenGuildScreenPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            net.minecraft.client.Minecraft.getInstance()
                    .setScreen(new GuildScreen(pkt));
        });
    }
}