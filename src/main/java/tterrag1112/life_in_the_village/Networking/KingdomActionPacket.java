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
        /** @deprecated Track D3.4 — superseded by DRAFT_LAW + PROPOSE_LAW + ENACT_LAW.
         *              Kept one phase as a fallback for any unmigrated client. */
        @Deprecated
        TOGGLE_LAW,
        SET_TAX_RATE,
        SET_UPKEEP,
        SET_RELATION,
        APPOINT_LEADER,
        ISSUE_DECREE,
        // ── Track D3.4 — law lifecycle verbs ────────────────────────────────
        /** stringParam = lawId; intParam unused. */
        DRAFT_LAW,
        /** stringParam = lawId; intParam unused. */
        PROPOSE_LAW,
        /** stringParam = lawId; intParam unused. */
        ENACT_LAW,
        /** stringParam = lawId; intParam unused. */
        REPEAL_LAW,
        /** stringParam = "lawId:value" for ScalarLaw drafts (numeric value). */
        UPDATE_DRAFT_SCALAR,
        /** stringParam = "lawId:choice" for EnumLaw drafts. */
        UPDATE_DRAFT_CHOICE
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

            // Phase 3 task 06: route the law-toggle gate through
            // PowerGrant. The ENACT_LAW power is granted by both
            // KINGDOM_KING and (for villages) VILLAGE_LEADER; for the
            // kingdom-scoped packet only the kingdom's office state is
            // consulted. The legacy rulerPlayerId remains populated for
            // one release window — but the gate itself sources its
            // truth from the office state now.
            if (!tterrag1112.life_in_the_village.Npc.Office.Powers.PowerGrant
                    .hasPower(player.getUUID(),
                            tterrag1112.life_in_the_village.Npc.Office.OfficePower.ENACT_LAW,
                            kingdom)) {
                player.sendSystemMessage(
                        net.minecraft.network.chat.Component
                                .literal("You don't hold the office that "
                                        + "lets you change this kingdom's laws."));
                return;
            }

            switch (pkt.action()) {
                case TOGGLE_LAW -> {
                    // Track D3.3b — capability gate. PASS_LAW
                    // requires King OR Magistrate competently held.
                    if (!checkCapability(player, level, data, kingdom,
                            tterrag1112.life_in_the_village.Kingdom.Capabilities
                                    .KingdomCapability.PASS_LAW,
                            "pass a law")) {
                        return;
                    }
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

                        // Track D3.3b — capability gate per relation
                        // type. WAR → DECLARE_WAR (King OR General),
                        // ALLIANCE / TRADE_PACT → DRAFT_TREATY (King
                        // OR Diplomat).
                        var requiredCap = switch (rel) {
                            case WAR -> tterrag1112.life_in_the_village
                                    .Kingdom.Capabilities
                                    .KingdomCapability.DECLARE_WAR;
                            case ALLIANCE, TRADE -> tterrag1112
                                    .life_in_the_village.Kingdom.Capabilities
                                    .KingdomCapability.DRAFT_TREATY;
                            default -> null;
                        };
                        if (requiredCap != null
                                && !checkCapability(player, level, data, kingdom,
                                        requiredCap,
                                        "change diplomatic stance to "
                                                + rel.name())) {
                            return;
                        }
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
                            // Phase 6.3.3.a — gated, PLAYER (kingdom UI packet).
                            tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                                    npc, Profession.VILLAGE_LEADER,
                                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.BUSINESS_PROMOTION,
                                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.PLAYER);
                        });
                        data.setDirty();
                    } catch (Exception ignored) {}
                }
                case ISSUE_DECREE -> {
                    // Track D3.3b — capability gate. ISSUE_DECREE
                    // requires King OR Chancellor competently held.
                    if (!checkCapability(player, level, data, kingdom,
                            tterrag1112.life_in_the_village.Kingdom.Capabilities
                                    .KingdomCapability.ISSUE_DECREE,
                            "issue a royal decree")) {
                        return;
                    }
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
                // ── Track D3.4 — law lifecycle verbs ────────────────────────
                case DRAFT_LAW -> handleDraftLaw(player, level, data, kingdom, pkt);
                case PROPOSE_LAW -> handleProposeLaw(player, level, data, kingdom, pkt);
                case ENACT_LAW -> handleEnactLaw(player, level, data, kingdom, pkt);
                case REPEAL_LAW -> handleRepealLaw(player, level, data, kingdom, pkt);
                case UPDATE_DRAFT_SCALAR -> handleUpdateDraftScalar(player, kingdom, pkt);
                case UPDATE_DRAFT_CHOICE -> handleUpdateDraftChoice(player, kingdom, pkt);
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

    // =========================================================================
    // Track D3.4 — law lifecycle handlers
    // =========================================================================

    /**
     * DRAFT_LAW. Drafting requires a Scholar competently held
     * (mapped through the {@code DRAFT_LAW} capability — using
     * ISSUE_DECREE as a proxy for "literate ministerial capacity"
     * since v1's capability table doesn't include a dedicated
     * DRAFT slot). Validates the law id exists in the registry.
     */
    private static void handleDraftLaw(ServerPlayer player, ServerLevel level,
                                       VillageSavedData data, Kingdom kingdom,
                                       KingdomActionPacket pkt) {
        String lawId = pkt.stringParam();
        if (tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawRegistry
                .find(lawId).isEmpty()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Unknown law id: " + lawId));
            return;
        }
        // Drafting gate: Scholar competently held, surfaced through
        // the existing ISSUE_DECREE capability for v1.
        if (!checkCapability(player, level, data, kingdom,
                tterrag1112.life_in_the_village.Kingdom.Capabilities
                        .KingdomCapability.ISSUE_DECREE,
                "draft a law")) return;
        kingdom.draftLaw(lawId, java.util.Optional.of(player.getUUID()),
                level.getGameTime());
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .LawDrafted(kingdom.getId(), lawId, player.getUUID(),
                        level.getGameTime()));
        data.setDirty();
    }

    /**
     * PROPOSE_LAW. Drafting → Proposed transition. Requires the
     * archetype's enactment capability (King for Toggle, Chancellor
     * for Scalar/Enum). Per the user-confirmed authority rule.
     */
    private static void handleProposeLaw(ServerPlayer player, ServerLevel level,
                                         VillageSavedData data, Kingdom kingdom,
                                         KingdomActionPacket pkt) {
        String lawId = pkt.stringParam();
        var law = tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawRegistry
                .find(lawId).orElse(null);
        if (law == null) return;
        if (!checkCapability(player, level, data, kingdom,
                law.enactmentCapability(),
                "propose '" + law.displayName() + "'")) return;
        if (!kingdom.proposeLaw(lawId, player.getUUID(), level.getGameTime())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Cannot propose '" + law.displayName()
                            + "' — must be in DRAFT state."));
            return;
        }
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .LawProposed(kingdom.getId(), lawId, player.getUUID(),
                        level.getGameTime()));
        data.setDirty();
    }

    /** ENACT_LAW. Proposed → Active. Same capability as propose. */
    private static void handleEnactLaw(ServerPlayer player, ServerLevel level,
                                       VillageSavedData data, Kingdom kingdom,
                                       KingdomActionPacket pkt) {
        String lawId = pkt.stringParam();
        var law = tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawRegistry
                .find(lawId).orElse(null);
        if (law == null) return;
        if (!checkCapability(player, level, data, kingdom,
                law.enactmentCapability(),
                "enact '" + law.displayName() + "'")) return;
        if (!kingdom.enactLaw(lawId, level.getGameTime())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "Cannot enact '" + law.displayName()
                            + "' — must be in PROPOSED state."));
            return;
        }
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .LawEnacted(kingdom.getId(), lawId, player.getUUID(),
                        level.getGameTime()));
        data.setDirty();
    }

    /**
     * REPEAL_LAW. Removes the law instance entirely. Capability
     * matches the law's enactment capability (per the prompt's
     * "repeal mirrors enactment authority" rule).
     */
    private static void handleRepealLaw(ServerPlayer player, ServerLevel level,
                                        VillageSavedData data, Kingdom kingdom,
                                        KingdomActionPacket pkt) {
        String lawId = pkt.stringParam();
        var law = tterrag1112.life_in_the_village.Kingdom.Laws.KingdomLawRegistry
                .find(lawId).orElse(null);
        if (law == null) return;
        if (!checkCapability(player, level, data, kingdom,
                law.enactmentCapability(),
                "repeal '" + law.displayName() + "'")) return;
        var prior = kingdom.findLawInstance(lawId).map(i -> i.state().name())
                .orElse("ABSENT");
        if (!kingdom.repealLaw(lawId, level.getGameTime())) return;
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.fire(
                new tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent
                        .LawRepealed(kingdom.getId(), lawId, prior, player.getUUID(),
                        level.getGameTime()));
        data.setDirty();
    }

    /** UPDATE_DRAFT_SCALAR. Edits a DRAFT-state ScalarLaw's value. */
    private static void handleUpdateDraftScalar(ServerPlayer player, Kingdom kingdom,
                                                KingdomActionPacket pkt) {
        String[] parts = pkt.stringParam().split(":", 2);
        if (parts.length != 2) return;
        try {
            kingdom.updateDraftScalar(parts[0], Double.parseDouble(parts[1]));
        } catch (NumberFormatException ignored) {}
    }

    /** UPDATE_DRAFT_CHOICE. Edits a DRAFT-state EnumLaw's choice. */
    private static void handleUpdateDraftChoice(ServerPlayer player, Kingdom kingdom,
                                                KingdomActionPacket pkt) {
        String[] parts = pkt.stringParam().split(":", 2);
        if (parts.length != 2) return;
        kingdom.updateDraftChoice(parts[0], parts[1]);
    }

    /**
     * Track D3.3b — server-side capability check. Layered on top of
     * the existing PowerGrant check (PowerGrant authorizes the
     * <em>player</em>; capability authorizes the <em>kingdom</em>
     * given its current office state). Sends the player a chat
     * notice on denial.
     */
    private static boolean checkCapability(
            ServerPlayer player, ServerLevel level, VillageSavedData data,
            Kingdom kingdom,
            tterrag1112.life_in_the_village.Kingdom.Capabilities.KingdomCapability cap,
            String actionDescription) {
        var result = tterrag1112.life_in_the_village.Kingdom.Capabilities
                .KingdomCapabilityEvaluator.evaluate(level, data, kingdom, cap);
        if (result.allowed()) return true;
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "Cannot " + actionDescription + " — "
                        + result.reason()));
        return false;
    }
}