package tterrag1112.life_in_the_village.Entities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.NpcProfileActionPacket;
import tterrag1112.life_in_the_village.Networking.NpcProfileSnapshot;
import tterrag1112.life_in_the_village.Networking.NpcProfileSyncPacket;
import tterrag1112.life_in_the_village.Networking.OpenNpcProfilePacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-side orchestrator for the informational NPC profile screen.
 *
 * <p>Track 4 redesign: the profile is INFORMATIVE only. Player actions
 * have moved to physical/contextual triggers handled by
 * {@code NpcInteractionHandler}. The profile retains a single nav
 * button whose target is resolved per the Step-5 priority rule.</p>
 *
 * <h3>Flow</h3>
 * <pre>
 * 1. Player right-clicks NPC → NpcInteractionHandler.handle calls open()
 * 2. Server locks NPC, builds snapshot, sends OpenNpcProfilePacket
 * 3. Auto-greet line fires on open (Q10)
 * 4. Client closes screen → handleClose() unlocks the NPC
 * 5. Optional nav button → handleAction(OPEN_NAV_TARGET) → context-specific screen
 * </pre>
 */
public final class NpcProfileHub {

    /** Safety timeout: 30 seconds in ticks. */
    private static final long CONVERSATION_TIMEOUT_TICKS = 600L;

    private NpcProfileHub() {}

    // =========================================================================
    // Open — called by NpcInteractionHandler
    // =========================================================================

    public static void open(TownspersonMob npc, ServerPlayer player, ServerLevel level) {
        if (!npc.lockForConversation(player.getUUID(),
                level.getGameTime(), CONVERSATION_TIMEOUT_TICKS)) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[" + npc.getNpcName() + "] I'm busy right now."),
                    false);
            return;
        }
        NpcProfileSnapshot snapshot = NpcProfileSnapshotBuilder.build(npc, player, level);
        PacketDistributor.sendToPlayer(player, new OpenNpcProfilePacket(snapshot));

        // Auto-greet on open (Q10) — fires the greet verb's effect so the
        // dialogue line goes out without requiring an extra button press.
        // No caller depends on greet firing as an explicit invocation
        // (verified — no cooldown/rel/memory side-effects in GreetVerb).
        var greet = tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerbRegistry
                .get("greet").orElse(null);
        if (greet != null) {
            greet.invoke(tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb
                    .context(player, npc, level));
        }
    }

    // =========================================================================
    // Sync / close
    // =========================================================================

    public static void handleSyncRequest(UUID npcId,
                                         ServerPlayer player,
                                         ServerLevel level) {
        TownspersonMob.findByUUID(level, npcId).ifPresent(npc -> {
            npc.lockForConversation(player.getUUID(),
                    level.getGameTime(), CONVERSATION_TIMEOUT_TICKS);
            NpcProfileSnapshot snapshot = NpcProfileSnapshotBuilder.build(npc, player, level);
            PacketDistributor.sendToPlayer(player, new NpcProfileSyncPacket(snapshot));
        });
    }

    public static void handleClose(UUID npcId, ServerPlayer player, ServerLevel level) {
        TownspersonMob.findByUUID(level, npcId)
                .ifPresent(npc -> npc.unlockConversation(player.getUUID()));
    }

    // =========================================================================
    // Action dispatch (only OPEN_PROFILE + OPEN_NAV_TARGET survive)
    // =========================================================================

    public static void handleAction(UUID npcId,
                                    NpcProfileActionPacket.Action action,
                                    ServerPlayer player,
                                    ServerLevel level) {
        Optional<TownspersonMob> npcOpt = TownspersonMob.findByUUID(level, npcId);
        if (npcOpt.isEmpty()) return;
        TownspersonMob npc = npcOpt.get();

        switch (action) {
            case OPEN_PROFILE     -> open(npc, player, level);
            case OPEN_NAV_TARGET  -> openNavTarget(npc, player, level);
            case OPEN_PRIMARY     -> openBusinessFrontPrimary(npc, player, level);
        }
    }

    // =========================================================================
    // BusinessFrontScreen primary-action resolution
    // =========================================================================

    /**
     * Routes the BusinessFrontScreen primary button per profession.
     * Most profession primaries open a profession-specific UI; a few
     * (priest, healer) trigger a verb invocation.
     */
    private static void openBusinessFrontPrimary(TownspersonMob npc,
                                                  ServerPlayer player,
                                                  ServerLevel level) {
        Profession prof = npc.getProfession();
        switch (prof) {
            case MERCHANT -> {
                npc.unlockConversation(player.getUUID());
                tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler
                        .openTradeScreen(player, npc);
            }
            case BLACKSMITH, CARPENTER, MILLER, BAKER, STONEMASON, WEAVER,
                 CANDLEMAKER, MINER -> {
                npc.unlockConversation(player.getUUID());
                tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler
                        .openTradeScreen(player, npc);
            }
            case PRIEST   -> invokeVerb("request_blessing", npc, player, level);
            case HEALER   -> invokeVerb("request_treatment", npc, player, level);
            case LIBRARIAN -> openLibraryScreen(npc, player, level);
            case SCRIBE   -> openScribeCounter(npc, player, level);
            case SCHOLAR  -> invokeVerb("take_lesson", npc, player, level);
            case INNKEEPER -> npc.handleInnkeeperInteraction(player, level);
            default -> {
                // Profession has no specific primary — leave a chat hint.
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "[" + npc.getNpcName() + "] How can I help?"),
                        false);
            }
        }
    }

    /** Track 5a.1 — open the ScribeCounter composer screen. */
    private static void openScribeCounter(TownspersonMob npc, ServerPlayer player,
                                          ServerLevel level) {
        UUID workshop = npc.getAssignedBuildingId().orElse(new UUID(0L, 0L));
        String vName = npc.getAssignedVillageName().orElse("");
        npc.unlockConversation(player.getUUID());
        PacketDistributor.sendToPlayer(player,
                new tterrag1112.life_in_the_village.Networking.OpenScribeCounterPacket(
                        npc.getUUID(), npc.getNpcName(), workshop, vName));
    }

    /** Track 5a.8 — open the LibraryScreen with the library's catalogue. */
    private static void openLibraryScreen(TownspersonMob npc, ServerPlayer player,
                                          ServerLevel level) {
        UUID lib = npc.getAssignedBuildingId().orElse(null);
        if (lib == null) return;
        var cat = VillageSavedData.get(level).getOrCreateLibraryCatalogue(lib);
        java.util.List<tterrag1112.life_in_the_village.Networking
                .OpenLibraryScreenPacket.Entry> entries = new java.util.ArrayList<>();
        cat.all().forEach(book -> entries.add(
                new tterrag1112.life_in_the_village.Networking.OpenLibraryScreenPacket.Entry(
                        book.bookId(), book.title(), book.author(),
                        book.copyCount(),
                        cat.isAvailableForLending(book.bookId()))));
        npc.unlockConversation(player.getUUID());
        PacketDistributor.sendToPlayer(player,
                new tterrag1112.life_in_the_village.Networking.OpenLibraryScreenPacket(
                        npc.getUUID(), npc.getNpcName(), lib, entries));
    }

    /** Track 5a.2 — open the StallLease screen at a market merchant. */
    private static void openStallLeaseScreen(TownspersonMob npc, ServerPlayer player,
                                             ServerLevel level) {
        UUID market = npc.getAssignedBuildingId().orElse(new UUID(0L, 0L));
        npc.unlockConversation(player.getUUID());
        PacketDistributor.sendToPlayer(player,
                new tterrag1112.life_in_the_village.Networking.OpenStallLeasePacket(
                        npc.getUUID(), npc.getNpcName(), market));
    }

    private static void invokeVerb(String verbId, TownspersonMob npc,
                                   ServerPlayer player, ServerLevel level) {
        tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerbRegistry.get(verbId)
                .ifPresent(v -> v.invoke(
                        tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb
                                .context(player, npc, level)));
    }

    // =========================================================================
    // Nav target resolution (Step-5 priority)
    // =========================================================================

    /**
     * Resolves and opens the single context-appropriate secondary screen
     * for {@code npc}. Priority:
     * <ol>
     *   <li>Office holder → office screen (VillageBookScreen for
     *       VILLAGE_LEADER, KingdomBookScreen for KINGDOM_RULER).</li>
     *   <li>Owned business worker → BusinessWorkerScreen.</li>
     *   <li>Profession default → profession-specific screen if one exists.</li>
     * </ol>
     */
    /** Phase 5c — true if this NPC holds the village-treasurer office. */
    private static boolean holdsVillageTreasurerOffice(TownspersonMob npc, ServerLevel level) {
        return tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry
                .findOfficesHeldBy(npc.getUUID(), level).stream()
                .anyMatch(m -> tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry
                        .VILLAGE_TREASURER.equals(m.holding().officeId()));
    }

    private static void openNavTarget(TownspersonMob npc, ServerPlayer player, ServerLevel level) {
        Profession prof = npc.getProfession();
        VillageSavedData data = VillageSavedData.get(level);

        // 1. Office holder routes.
        if (prof == Profession.VILLAGE_LEADER) {
            Village v = npc.getAssignedVillageName().flatMap(data::getVillageByName).orElse(null);
            if (v != null) {
                npc.unlockConversation(player.getUUID());
                tterrag1112.life_in_the_village.Gui.VillageBookScreen
                        .sendOpenPacket(player, v.getId(), level, data);
                return;
            }
        }
        // Phase 5c — the village treasurer (an office held by a MERCHANT/
        // SCHOLAR, not a profession) opens the book straight to the Economy
        // section. Detected via the office registry.
        if (holdsVillageTreasurerOffice(npc, level)) {
            Village v = npc.getAssignedVillageName().flatMap(data::getVillageByName).orElse(null);
            if (v != null) {
                npc.unlockConversation(player.getUUID());
                tterrag1112.life_in_the_village.Gui.VillageBookScreen
                        .sendOpenPacket(player, v.getId(), level, data, "ECONOMY");
                return;
            }
        }
        if (prof == Profession.KINGDOM_RULER) {
            // KingdomBookScreen has its own open path; nothing wired here yet.
            // Fall through to default.
        }

        // 2. Owned business worker.
        if (prof == Profession.COMPANY_WORKER) {
            Business business = BusinessSavedData.get(level)
                    .getBusinessForWorker(npc.getUUID()).orElse(null);
            if (business != null && business.getOwnerPlayerId().equals(player.getUUID())) {
                npc.unlockConversation(player.getUUID());
                tterrag1112.life_in_the_village.Gui.BusinessWorkerScreen
                        .open(player, npc, business);
                return;
            }
        }

        // 3. Adventurer party member — surface party status in chat.
        //    WorkPanel now also shows this; the nav button is a quick
        //    refresh hook until a dedicated party screen lands.
        if (prof == Profession.ADVENTURER && npc.getCombatRole() != null) {
            var party = tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData
                    .get(level).getPartyContaining(npc.getUUID()).orElse(null);
            if (party != null && party.getLeaderPlayerId().equals(player.getUUID())) {
                party.getMember(npc.getUUID()).ifPresent(m ->
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                                m.role().symbol + " " + m.role().getDisplayName()
                                                        + " | Lv." + m.level()
                                                        + " | " + m.kills() + " kills")
                                        .withStyle(net.minecraft.ChatFormatting.AQUA),
                                false));
                return;
            }
        }

        // 4. Profession default — no profession-specific screen wired
        //    today. Polish follow-up may add e.g. a MerchantStallScreen.
    }

    // =========================================================================
    // Nav-target availability (read from snapshot builder)
    // =========================================================================

    /** Returns true when {@link #openNavTarget} would do something useful. */
    public static boolean hasNavTarget(TownspersonMob npc, ServerPlayer player, ServerLevel level) {
        Profession prof = npc.getProfession();
        if (prof == Profession.VILLAGE_LEADER) return true;
        if (prof == Profession.COMPANY_WORKER) {
            return BusinessSavedData.get(level)
                    .getBusinessForWorker(npc.getUUID())
                    .map(c -> c.getOwnerPlayerId().equals(player.getUUID()))
                    .orElse(false);
        }
        return false;
    }
}
