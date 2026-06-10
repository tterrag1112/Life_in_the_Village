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
            if (progressed.allComplete()) {
                progressed = progressed.withStatus(QuestStatus.COMPLETED);
                grantRewards(level, player, progressed, now);
                player.displayClientMessage(Component.literal("Quest complete: " + progressed.title())
                        .withStyle(ChatFormatting.GOLD), false);
            } else {
                player.displayClientMessage(Component.literal("Quest updated: " + progressed.title())
                        .withStyle(ChatFormatting.GRAY), true);
            }
            store.replace(player.getUUID(), progressed);
        }
    }

    private static void grantRewards(ServerLevel level, ServerPlayer player, Quest quest, long now) {
        for (QuestReward r : quest.rewards()) {
            try { r.grant(level, player, now); }
            catch (RuntimeException ex) { /* a bad reward never breaks completion */ }
        }
    }
}
