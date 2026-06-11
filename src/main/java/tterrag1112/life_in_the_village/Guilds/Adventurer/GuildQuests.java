package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Quests.Objective;
import tterrag1112.life_in_the_village.Quests.Quest;
import tterrag1112.life_in_the_village.Quests.QuestContext;
import tterrag1112.life_in_the_village.Quests.QuestDifficulty;
import tterrag1112.life_in_the_village.Quests.QuestEvents;
import tterrag1112.life_in_the_village.Quests.QuestSavedData;
import tterrag1112.life_in_the_village.Quests.QuestStatus;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F2b-2 — the canonical routing helper for player guild quests on the F2 base. It is the
 * single place the generation → offer → accept → turn-in flow lives, so the GUI packet
 * path ({@code GuildActionPacket}) and the command path ({@code GuildCommands}) share one
 * behaviour instead of open-coding it twice.
 *
 * <p>Storage convention (see {@link QuestSavedData}): a guild's OFFERED pool is keyed under
 * the guildId; an accepted quest moves into the player's ACTIVE list. Rewards (coins / XP /
 * rank-up / party multiplier / deliver-consume) are applied here at turn-in, not by the
 * engine's auto-reward path — guild quests are turned in at the guild.</p>
 */
public final class GuildQuests {

    private GuildQuests() {}

    // ── Generation / offering ────────────────────────────────────────────────

    /** Refreshes {@code guild}'s OFFERED pool: drop expired offers, post a fresh set. */
    public static void refreshOffers(ServerLevel level, GuildData guild,
                                     Village village, VillageSavedData data) {
        QuestSavedData store = QuestSavedData.get(level);
        long now = level.getGameTime();
        store.clearExpiredGuildOffers(guild.guildId(), now);
        for (Quest q : QuestGenerator.generateForGuild(level, guild.guildId(), village, data, now)) {
            store.offerGuildQuest(guild.guildId(), q);
        }
    }

    public static List<Quest> available(ServerLevel level, UUID guildId, GuildRank rank) {
        return QuestSavedData.get(level).availableGuildQuests(guildId, rank);
    }

    public static List<Quest> activeFor(ServerLevel level, UUID playerId) {
        return QuestSavedData.get(level).activeGuildQuests(playerId);
    }

    // ── Accept ─────────────────────────────────────────────────────────────────

    public enum AcceptResult { OK, NOT_FOUND, NOT_AVAILABLE, RANK_TOO_LOW, TOO_MANY_ACTIVE }

    /**
     * Accepts {@code questId} from {@code guildId}'s pool for {@code player}: gates on the
     * active-quest cap ({@code maxActive}) and the quest's rank requirement, then moves the
     * offer out of the pool into the player's ACTIVE list.
     */
    public static AcceptResult accept(ServerLevel level, ServerPlayer player, UUID guildId,
                                      UUID questId, GuildMember member, int maxActive) {
        QuestSavedData store = QuestSavedData.get(level);
        if (store.activeGuildQuests(player.getUUID()).size() >= maxActive) return AcceptResult.TOO_MANY_ACTIVE;
        Quest offer = store.guildOffer(guildId, questId).orElse(null);
        if (offer == null) return AcceptResult.NOT_FOUND;
        GuildRank rank = member == null ? GuildRank.BRONZE : member.currentRank();
        if (!rankAllows(rank, offer)) return AcceptResult.RANK_TOO_LOW;
        store.removeGuildOffer(guildId, questId);
        store.add(player.getUUID(), offer.withStatus(QuestStatus.ACTIVE));
        return AcceptResult.OK;
    }

    /** Does {@code rank} meet the quest's {@code rankRequirement} (true when unset)? */
    public static boolean rankAllows(GuildRank rank, Quest quest) {
        return quest.rankRequirement().map(req -> rank.ordinal() >= req.ordinal()).orElse(true);
    }

    // ── Turn-in ─────────────────────────────────────────────────────────────────

    /** The outcome of a turn-in attempt (drives the caller's player messaging). */
    public record TurnInResult(boolean completed, long coins, int xpGranted,
                               float multiplier, boolean rankedUp, GuildRank newRank) {
        public static final TurnInResult NOT_READY = new TurnInResult(false, 0, 0, 1f, false, null);
    }

    /**
     * Turns in {@code quest} for {@code player} when its objectives are SATISFIED. Pays the
     * difficulty's coins (purse-aware) + XP scaled by the party multiplier, ranks the
     * player up (regenerating the guild pool on promotion), consumes delivered items,
     * records the completion on the member + party, and marks the quest COMPLETED.
     */
    public static TurnInResult turnIn(ServerLevel level, ServerPlayer player,
                                      Quest quest, VillageSavedData vdata) {
        // Escort completes on arrival at the destination village; turning in at the guild
        // (which sits in that village) satisfies it deterministically without waiting on
        // the periodic arrival scan.
        quest = resolveEscort(level, player, quest);
        if (!quest.allSatisfied(level, player)) return TurnInResult.NOT_READY;

        QuestSavedData store = QuestSavedData.get(level);
        PlayerGuildData guildData = PlayerGuildData.get(level);
        PlayerPartySavedData partyData = PlayerPartySavedData.get(level);
        UUID playerId = player.getUUID();

        QuestDifficulty diff = quest.difficulty();
        long coins = diff.baseCoinReward();
        int baseXp = diff.baseXp();
        float mult = partyData.getPartyForPlayer(playerId)
                .map(PlayerParty::getPartyXpMultiplier).orElse(1.0f);
        int finalXp = (int) (baseXp * mult);

        // Coins — purse-aware (routes into the player's coin purse, as legacy did).
        CoinHelper.playerReceive(player, CurrencyValue.of(coins));

        // XP + rank-up (regenerate the guild's offer pool on promotion, as legacy did).
        GuildRank before = guildData.getMember(playerId).map(GuildMember::currentRank).orElse(GuildRank.BRONZE);
        guildData.addXp(playerId, finalXp);
        GuildRank after = guildData.getMember(playerId).map(GuildMember::currentRank).orElse(before);
        boolean rankedUp = after.ordinal() > before.ordinal();
        if (rankedUp) {
            guildData.setRank(playerId, after);
            guildIdOf(quest).ifPresent(guildId ->
                    vdata.getGuildById(guildId).ifPresent(guild ->
                            vdata.getVillageById(guild.villageId()).ifPresent(village ->
                                    refreshOffers(level, guild, village, vdata))));
        }

        // Deliver / gather — consume the items the quest required.
        consumeQuestItems(player, quest);

        // Record completion.
        store.replace(playerId, quest.withStatus(QuestStatus.COMPLETED));
        store.accrueStanding(playerId, quest.giver());
        guildData.getMember(playerId).ifPresent(m -> guildData.updateMember(m.withCompletedQuest(quest.questId())));
        partyData.getPartyForPlayer(playerId).ifPresent(party -> {
            party.completeQuest();
            partyData.setDirty();
        });

        return new TurnInResult(true, coins, finalXp, mult, rankedUp, after);
    }

    // ── Escort arrival source (F2b-2) ────────────────────────────────────────

    /**
     * Fires an {@code ESCORT_ARRIVED} notify for every online player standing in the
     * destination village of one of their active escort quests. Wired from the guild
     * worker's periodic tick — the F2 escort objective's completion source.
     */
    public static void notifyEscortArrivals(ServerLevel level) {
        QuestSavedData store = QuestSavedData.get(level);
        VillageSavedData data = VillageSavedData.get(level);
        for (ServerPlayer player : level.players()) {
            for (Quest q : store.activeGuildQuests(player.getUUID())) {
                for (Objective o : q.objectives()) {
                    if (o instanceof Objective.Escort esc && !esc.done()) {
                        String dest = esc.destination();
                        boolean atDest = dest == null || dest.isEmpty()
                                || data.getVillageAt(player.blockPosition())
                                       .map(v -> v.getId().toString().equals(dest)).orElse(false);
                        if (atDest) QuestEvents.notify(player, QuestContext.escortArrived(dest));
                    }
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** Marks any escort objective done when the player stands in its destination village. */
    private static Quest resolveEscort(ServerLevel level, ServerPlayer player, Quest quest) {
        VillageSavedData data = VillageSavedData.get(level);
        boolean changed = false;
        List<Objective> updated = new ArrayList<>(quest.objectives().size());
        for (Objective o : quest.objectives()) {
            if (o instanceof Objective.Escort esc && !esc.done()) {
                String dest = esc.destination();
                boolean atDest = dest == null || dest.isEmpty()
                        || data.getVillageAt(player.blockPosition())
                               .map(v -> v.getId().toString().equals(dest)).orElse(false);
                if (atDest) { updated.add(esc.advanced()); changed = true; continue; }
            }
            updated.add(o);
        }
        return changed ? quest.withObjectives(updated) : quest;
    }

    /** The guild that issued {@code quest} (its id is the giver id for GUILD quests). */
    public static Optional<UUID> guildIdOf(Quest quest) {
        try {
            return Optional.of(UUID.fromString(quest.giver().id()));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return Optional.empty();
        }
    }

    /** A short readable kind label for a quest's first objective (for the GUI / books). */
    public static String kindLabel(Quest quest) {
        if (quest.objectives().isEmpty()) return "QUEST";
        return switch (quest.objectives().get(0).type()) {
            case Objective.Hunt.TYPE    -> "HUNT";
            case Objective.Gather.TYPE  -> "GATHER";
            case Objective.Explore.TYPE -> "EXPLORE";
            case Objective.Deliver.TYPE -> "DELIVER";
            case Objective.Escort.TYPE  -> "ESCORT";
            default -> "QUEST";
        };
    }

    /** Completion fraction for the readout: event objectives report current/target. */
    public static float completionFraction(Quest quest) {
        if (quest.objectives().isEmpty()) return 0f;
        Objective o = quest.objectives().get(0);
        if (o instanceof Objective.Hunt h) {
            return h.target() <= 0 ? 0f : Math.min(1f, (float) h.current() / h.target());
        }
        return o.isComplete() ? 1f : 0f;
    }

    /** Removes the quest's delivered/gathered items from the player's inventory at turn-in. */
    private static void consumeQuestItems(ServerPlayer player, Quest quest) {
        for (Objective o : quest.objectives()) {
            if (o instanceof Objective.Gather g)  consume(player, g.itemId(), g.target());
            if (o instanceof Objective.Deliver d) consume(player, d.itemId(), 1);
        }
    }

    private static void consume(ServerPlayer player, String itemId, int count) {
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        net.minecraft.world.item.Item item = id == null ? null
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) return;
        int remaining = count;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }
}
