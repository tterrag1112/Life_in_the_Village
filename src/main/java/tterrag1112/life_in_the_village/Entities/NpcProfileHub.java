package tterrag1112.life_in_the_village.Entities;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerGuildData;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Networking.*;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-side orchestrator for the NPC profile screen lifecycle.
 *
 * <p>All public methods are called from packet handlers on the main thread
 * (via {@code IPayloadContext.enqueueWork}).
 *
 * <h3>Flow</h3>
 * <pre>
 * 1. Player right-clicks NPC → NpcInteractionHandler.handle calls open()
 * 2. Server locks NPC, builds snapshot, sends OpenNpcProfilePacket
 * 3. Client may call handleSyncRequest() at any time to refresh
 * 4. Client action button → handleAction()
 * 5. Client closes screen → handleClose() which unlocks the NPC
 * </pre>
 */
public final class NpcProfileHub {

    /** Safety timeout: 30 seconds in ticks. */
    private static final long CONVERSATION_TIMEOUT_TICKS = 600L;

    private NpcProfileHub() {}

    // =========================================================================
    // Open — called by NpcInteractionHandler
    // =========================================================================

    /**
     * Locks the NPC, builds the initial snapshot, and sends
     * {@link OpenNpcProfilePacket} to the player.
     */
    public static void open(TownspersonMob npc, ServerPlayer player, ServerLevel level) {
        if (!npc.lockForConversation(player.getUUID(),
                level.getGameTime(), CONVERSATION_TIMEOUT_TICKS)) {
            // Another player already has this NPC in conversation
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[" + npc.getNpcName() + "] I'm busy right now."),
                    false);
            return;
        }
        NpcProfileSnapshot snapshot = NpcProfileSnapshotBuilder.build(npc, player, level);
        PacketDistributor.sendToPlayer(player, new OpenNpcProfilePacket(snapshot));
    }

    // =========================================================================
    // Sync request — client requests a data refresh
    // =========================================================================

    public static void handleSyncRequest(UUID npcId,
                                         ServerPlayer player,
                                         ServerLevel level) {
        TownspersonMob.findByUUID(level, npcId).ifPresent(npc -> {
            // Renew the lock timeout so active screens don't auto-expire
            npc.lockForConversation(player.getUUID(),
                    level.getGameTime(), CONVERSATION_TIMEOUT_TICKS);
            NpcProfileSnapshot snapshot = NpcProfileSnapshotBuilder.build(npc, player, level);
            PacketDistributor.sendToPlayer(player, new NpcProfileSyncPacket(snapshot));
        });
    }

    // =========================================================================
    // Close — client closed the screen
    // =========================================================================

    public static void handleClose(UUID npcId, ServerPlayer player, ServerLevel level) {
        TownspersonMob.findByUUID(level, npcId)
                .ifPresent(npc -> npc.unlockConversation(player.getUUID()));
    }

    // =========================================================================
    // Action dispatch
    // =========================================================================

    public static void handleAction(UUID npcId,
                                    NpcProfileActionPacket.Action action,
                                    ServerPlayer player,
                                    ServerLevel level) {
        Optional<TownspersonMob> npcOpt = TownspersonMob.findByUUID(level, npcId);
        if (npcOpt.isEmpty()) return;
        TownspersonMob npc = npcOpt.get();

        switch (action) {
            case TRADE               -> doTrade(npc, player, level);
            case OPEN_GUILD          -> doOpenGuild(npc, player, level);
            case ASSIGN_WORK         -> doAssignWork(npc, player, level);
            case OPEN_COMPANY_WORKER -> doOpenCompanyWorker(npc, player, level);
            case SHOW_VILLAGE_BOOK   -> doShowVillageBook(npc, player, level);
            case SHOW_CRAFTING_ORDERS -> doShowCraftingOrders(npc, player, level);
            case RENT_STALL          -> doRentStall(npc, player, level);
            case GIVE_GIFT           -> doGiveGift(npc, player, level);
        }
    }

    // =========================================================================
    // Action implementations
    // =========================================================================

    private static void doTrade(TownspersonMob npc, ServerPlayer player, ServerLevel level) {
        npc.unlockConversation(player.getUUID());
        TradeHandler.openTradeScreen(player, npc);
    }

    private static void doOpenGuild(TownspersonMob npc,
                                    ServerPlayer player,
                                    ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);
        Optional<UUID> guildIdOpt = npc.getAssignedVillageName()
                .flatMap(vdata::getVillageByName)
                .flatMap(v -> vdata.getGuildForVillage(v.getId()))
                .map(GuildData::guildId);

        if (guildIdOpt.isEmpty()) return;

        UUID guildId = guildIdOpt.get();
        PlayerGuildData guildData = PlayerGuildData.get(level);
        if (!guildData.isRegistered(player.getUUID())) {
            guildData.registerPlayer(player.getUUID(),
                    player.getName().getString(), guildId);
            guildData.setDirty();
        }

        npc.unlockConversation(player.getUUID());
        tterrag1112.life_in_the_village.Gui.GuildScreen.sendOpenPacket(
                player, guildId, level, guildData, vdata,
                PlayerPartySavedData.get(level));
    }

    private static void doAssignWork(TownspersonMob npc,
                                     ServerPlayer player,
                                     ServerLevel level) {
        tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager
                .handleWorkRequest(player, npc, level);
        pushSync(npc, player, level);
    }

    private static void doOpenCompanyWorker(TownspersonMob npc,
                                            ServerPlayer player,
                                            ServerLevel level) {
        tterrag1112.life_in_the_village.Guilds.Companies.CompanySavedData
                .get(level)
                .getCompanyForWorker(npc.getUUID())
                .ifPresent(company -> {
                    if (company.getOwnerPlayerId().equals(player.getUUID())) {
                        npc.unlockConversation(player.getUUID());
                        tterrag1112.life_in_the_village.Gui.CompanyWorkerScreen
                                .open(player, npc, company);
                    }
                });
    }

    private static void doShowVillageBook(TownspersonMob npc,
                                          ServerPlayer player,
                                          ServerLevel level) {
        npc.getAssignedVillageName().ifPresent(vName -> {
            VillageSavedData data = VillageSavedData.get(level);
            data.getVillageByName(vName).ifPresent(village -> {
                npc.unlockConversation(player.getUUID());
                tterrag1112.life_in_the_village.Gui.VillageBookScreen
                        .sendOpenPacket(player, village.getId(), level, data);
            });
        });
    }

    private static void doShowCraftingOrders(TownspersonMob npc,
                                             ServerPlayer player,
                                             ServerLevel level) {
        npc.getAssignedVillageName().ifPresent(vName -> {
            VillageSavedData data = VillageSavedData.get(level);
            data.getVillageByName(vName).ifPresent(village -> {
                npc.unlockConversation(player.getUUID());
                tterrag1112.life_in_the_village.Gui.VillageBookScreen
                        .sendOpenPacket(player, village.getId(), level, data, "COMMISSIONS");
            });
        });
    }

    private static void doRentStall(TownspersonMob npc,
                                    ServerPlayer player,
                                    ServerLevel level) {
        // Delegate to the existing stall handler in NpcInteractionHandler
        tterrag1112.life_in_the_village.Entities.NpcInteractionHandler
                .handleRentStallAction(npc, player, level);
        pushSync(npc, player, level);
    }

    private static void doGiveGift(TownspersonMob npc,
                                   ServerPlayer player,
                                   ServerLevel level) {
        // Flat +3 delta; remove one non-stack item from player's main hand
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[" + npc.getNpcName() + "] You don't have anything to give me."),
                    false);
            return;
        }
        held.shrink(1);
        int newDelta = npc.adjustRelationship(player.getUUID(), 3);
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                        "[" + npc.getNpcName() + "] Thank you for the gift! "
                                + "(personal standing: " + (newDelta >= 0 ? "+" : "")
                                + newDelta + ")"),
                false);
        pushSync(npc, player, level);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void pushSync(TownspersonMob npc,
                                 ServerPlayer player,
                                 ServerLevel level) {
        NpcProfileSnapshot snap = NpcProfileSnapshotBuilder.build(npc, player, level);
        PacketDistributor.sendToPlayer(player, new NpcProfileSyncPacket(snap));
    }
}
