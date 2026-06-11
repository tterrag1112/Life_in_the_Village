package tterrag1112.life_in_the_village.Quests;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * F2a-1 — <b>the single unified completion path</b> for the quest base, and the whole
 * point of the engine: one hook ({@link #notify}) advances every matching
 * {@link Objective} on a player's ACTIVE quests, completes a quest when all its
 * objectives are done, and grants its rewards. This replaces the per-type hardcoded
 * listeners the legacy guild system uses (which F2b retires) — adding an objective kind
 * needs a new {@link Objective} + a {@code notify} source, never a new listener.
 */
public final class QuestEvents {

    private QuestEvents() {}

    /**
     * The player did something quest-relevant ({@code ctx}). Advance every matching
     * objective on their active quests; complete + reward any quest whose objectives
     * are all done. Side-effect-free on non-matching quests (and on the source act).
     */
    public static void notify(ServerPlayer player, QuestContext ctx) {
        if (player == null || ctx == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        QuestSavedData store = QuestSavedData.get(level);
        long now = level.getGameTime();

        for (Quest quest : store.active(player.getUUID())) {
            boolean changed = false;
            List<Objective> updated = new ArrayList<>(quest.objectives().size());
            for (Objective o : quest.objectives()) {
                if (!o.isComplete() && o.matches(ctx)) { updated.add(o.advanced()); changed = true; }
                else updated.add(o);
            }
            if (!changed) continue;

            Quest progressed = quest.withObjectives(updated);
            // EVENT completion: all objectives complete by progress (poll objectives, if
            // any, are finalized at turn-in via evaluate()).
            //
            // F2b-2 — GUILD ("turn-in") quests are an exception: their objectives still
            // ADVANCE on events (kills, escort arrival), but they are only COMPLETED and
            // REWARDED when the player returns to the guild and turns in (the party
            // multiplier / purse-aware coins / rank-up flow lives there). So we save the
            // progress and prompt the player to turn in, never auto-completing them here.
            boolean turnInGiver = progressed.giver().type() == QuestGiver.Type.GUILD;
            if (progressed.allComplete() && !turnInGiver) {
                complete(store, level, player, progressed, now);
            } else {
                store.replace(player.getUUID(), progressed);
                if (turnInGiver) {
                    // Mirror the legacy guild HUNT notice: silent on partial progress, a
                    // "return to the guild" prompt once every objective is met.
                    if (progressed.allComplete()) {
                        player.displayClientMessage(Component.literal(
                                "Objective complete: " + progressed.title()
                                        + " — return to the guild to claim your reward!"), true);
                    }
                } else {
                    player.displayClientMessage(Component.literal("Quest updated: " + progressed.title())
                            .withStyle(ChatFormatting.GRAY), true);
                }
            }
        }
    }

    /**
     * F2b-1 — the POLL / turn-in path: complete every active quest whose objectives are
     * all SATISFIED right now (poll objectives via their live check; event objectives via
     * progress). The sanctioned engine addition — a general capability, funneling to the
     * SAME {@link #complete} routine as the event path.
     */
    public static void evaluate(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) return;
        QuestSavedData store = QuestSavedData.get(level);
        long now = level.getGameTime();
        for (Quest quest : store.active(player.getUUID())) {
            if (quest.allSatisfied(level, player)) complete(store, level, player, quest, now);
        }
    }

    /** The ONE completion routine — reward + giver standing + record — shared by the
     *  event ({@link #notify}) and poll ({@link #evaluate}) paths. */
    private static void complete(QuestSavedData store, ServerLevel level, ServerPlayer player,
                                 Quest quest, long now) {
        Quest done = quest.withStatus(QuestStatus.COMPLETED);
        grantRewards(level, player, done, now);
        store.accrueStanding(player.getUUID(), done.giver());
        store.replace(player.getUUID(), done);
        player.displayClientMessage(Component.literal("Quest complete: " + done.title())
                .withStyle(ChatFormatting.GOLD), false);
    }

    private static void grantRewards(ServerLevel level, ServerPlayer player, Quest quest, long now) {
        for (QuestReward r : quest.rewards()) {
            try { r.grant(level, player, now); }
            catch (RuntimeException ex) { /* a bad reward never breaks completion */ }
        }
    }
}
