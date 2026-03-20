package tterrag1112.life_in_the_village.Networking;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import tterrag1112.life_in_the_village.Kingdom.DiplomaticRelation;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomLaw;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Lore.KingdomHistoryData;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.UUID;

public record KingdomActionPacket(
        ActionType action,
        UUID kingdomId,
        String stringParam,
        int intParam
) implements CustomPacketPayload {

    public enum ActionType {
        TOGGLE_LAW,
        SET_TAX_RATE,
        SET_UPKEEP,
        SET_RELATION,
        APPOINT_LEADER,
        ISSUE_DECREE
    }

    public static final Type<KingdomActionPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(
                    Life_in_the_village.MODID, "kingdom_action")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf,
            KingdomActionPacket> CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.action().name());
                buf.writeUUID(pkt.kingdomId());
                buf.writeUtf(pkt.stringParam());
                buf.writeVarInt(pkt.intParam());
            },
            buf -> new KingdomActionPacket(
                    ActionType.valueOf(buf.readUtf()),
                    buf.readUUID(),
                    buf.readUtf(),
                    buf.readVarInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(KingdomActionPacket pkt,
                              IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) ctx.player();
            ServerLevel level   = (ServerLevel) player.level();
            VillageSavedData data = VillageSavedData.get(level);

            Kingdom kingdom = data.getKingdomById(pkt.kingdomId())
                    .orElse(null);
            if (kingdom == null) return;

            // Verify player is the ruler
            if (!kingdom.getRulerPlayerId()
                    .map(id -> id.equals(player.getUUID()))
                    .orElse(false)) {
                player.sendSystemMessage(
                        net.minecraft.network.chat.Component
                                .literal("You are not the ruler "
                                        + "of this kingdom."));
                return;
            }

            switch (pkt.action()) {
                case TOGGLE_LAW -> {
                    try {
                        KingdomLaw law = KingdomLaw.valueOf(
                                pkt.stringParam());

                        // Toggle first
                        if (kingdom.hasLaw(law)) {
                            kingdom.repealLaw(law);
                        } else {
                            kingdom.enactLaw(law);
                        }

                        // Record AFTER toggle — now hasLaw reflects
                        // the new state
                        kingdom.getHistory().recordEvent(
                                kingdom.hasLaw(law)
                                        ? HistoryTextGenerator.lawEnacted(
                                        formatLawName(law.name()),
                                        player.getName().getString(),
                                        level.getGameTime())
                                        : HistoryTextGenerator.lawRepealed(
                                        formatLawName(law.name()),
                                        player.getName().getString(),
                                        level.getGameTime()),
                                kingdom.getName(),
                                kingdom.getRulerName(level));

                        data.setDirty();
                    } catch (IllegalArgumentException ignored) {}
                }
                case SET_TAX_RATE -> {
                    kingdom.setIncomeTaxRate(
                            pkt.intParam() / 100.0);
                    data.setDirty();
                }
                case SET_UPKEEP -> {
                    kingdom.setFlatUpkeepBronze(pkt.intParam());
                    data.setDirty();
                }
                case SET_RELATION -> {
                    try {
                        // stringParam = "targetKingdomId:RELATION"
                        String[] parts = pkt.stringParam()
                                .split(":");


                        UUID targetId = UUID.fromString(parts[0]);
                        DiplomaticRelation currentRelation =
                                kingdom.getRelation(targetId);
                        DiplomaticRelation rel =
                                DiplomaticRelation.valueOf(parts[1]);
                        kingdom.setRelation(targetId, rel);




                        // Mirror on target kingdom
                        data.getKingdomById(targetId)
                                .ifPresent(target -> {
                                    target.setRelation(
                                            kingdom.getId(), rel);
                                });
                       /* kingdom.getHistory().recordEvent(
                                rel == DiplomaticRelation.WAR
                                        ? HistoryTextGenerator.warDeclared(
                                        kingdom.getName(),
                                        targetId.toString(),
                                        level.getGameTime())
                                        : rel == DiplomaticRelation.ALLIANCE
                                        ? HistoryTextGenerator.allianceFormed(
                                        kingdom.getName(),
                                        targetId.toString(),
                                        level.getGameTime())
                                        : HistoryTextGenerator.tradePactSigned(
                                        kingdom.getName(),
                                        targetId.toString(),
                                        level.getGameTime()),
                                kingdom.getName());
                        data.setDirty();

                        */

                        // Record as event
                        kingdom.getHistory().recordEvent(
                                KingdomHistoryData.KingdomHistoryEvent.create(
                                        rel == DiplomaticRelation.WAR
                                                ? KingdomHistoryData.HistoryEventType.WAR_DECLARED
                                                : rel == DiplomaticRelation.ALLIANCE
                                                ? KingdomHistoryData.HistoryEventType.ALLIANCE_FORMED
                                                : KingdomHistoryData.HistoryEventType.TRADE_PACT_SIGNED,
                                        rel.name().replace("_", " ")
                                                + " with "
                                                + data.getKingdomById(targetId)
                                                .map(Kingdom::getName)
                                                .orElse("Unknown"),
                                        player.getName().getString()
                                                + " changed relations to "
                                                + rel.name(),
                                        level.getGameTime(),
                                        data.getKingdomById(targetId)
                                                .map(Kingdom::getName)
                                                .orElse("Unknown")),
                                kingdom.getName());
                        data.setDirty();
                    } catch (Exception ignored) {}
                }
                case APPOINT_LEADER -> {
                    // stringParam = "villageId:npcName"
                    try {
                        String[] parts = pkt.stringParam()
                                .split(":", 2);
                        UUID villageId = UUID.fromString(parts[0]);
                        String npcName = parts[1];
                        // Find NPC and set as village leader
                        level.getEntitiesOfClass(
                                tterrag1112.life_in_the_village
                                        .Entities.custom.TownspersonMob.class,
                                player.getBoundingBox().inflate(256),
                                npc -> npc.getNpcName().equals(npcName)
                                        && npc.getAssignedVillageName()
                                        .map(v -> data.getVillageByName(v)
                                                .map(vil -> vil.getId()
                                                        .equals(villageId))
                                                .orElse(false))
                                        .orElse(false)
                        ).stream().findFirst().ifPresent(npc -> {
                            npc.setProfession(
                                    Profession.VILLAGE_LEADER);
                        });
                        data.setDirty();
                    } catch (Exception ignored) {}
                }
                case ISSUE_DECREE -> {
                    // Broadcast decree message to all players
                    // in the kingdom's villages
                    String decree = pkt.stringParam();
                    level.getServer().getPlayerList()
                            .getPlayers()
                            .forEach(p -> p.sendSystemMessage(
                                    net.minecraft.network.chat
                                            .Component.literal(
                                                    "[Royal Decree] " + decree)
                                            .withStyle(net.minecraft
                                                    .ChatFormatting.GOLD)));
                    data.setDirty();
                }
            }
            System.out.println("Kingdom history events before save: "
                    + kingdom.getHistory().getEvents().size());
            System.out.println("Kingdom history forGetter test: "
                    + kingdom.getHistory().getEvents().size());
            data.setDirty();

            // Send updated data back to client
            PacketDistributor
                    .sendToPlayer(player,
                            new SyncKingdomPacket(data.getAllKingdoms()));
        });
    }

    private static String formatLawName(String name) {
        return name.charAt(0)
                + name.substring(1).toLowerCase()
                .replace("_", " ");
    }
}