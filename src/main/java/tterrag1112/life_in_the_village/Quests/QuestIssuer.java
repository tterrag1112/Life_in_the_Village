package tterrag1112.life_in_the_village.Quests;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.God;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;

import java.util.List;
import java.util.UUID;

/**
 * F2a-1 — minimal quest issuance (the proving + deity-issuance stub). F2a-2 grows this
 * into V3-calling graduation and rich givers (deity / saint / clergy). For now it mints
 * the one religious quest the {@code MAKE_OFFERING} objective proves.
 */
public final class QuestIssuer {

    private QuestIssuer() {}

    /** Issues an ACTIVE "make {@code count} offerings to {@code godId}" quest to
     *  {@code player}, rewarding favour with that god. Returns the issued quest, or
     *  null for an unknown god. */
    public static Quest grantOfferingQuest(ServerLevel level, ServerPlayer player,
                                           String godId, int count) {
        God god = GodRegistry.get(godId);
        if (god == null) return null;
        int n = Math.max(1, count);
        String godName = god.displayName();
        Quest quest = new Quest(
                UUID.randomUUID(),
                new QuestGiver(QuestGiver.Type.DIVINE, godId),
                "Offerings to " + godName,
                "Make " + n + " offering(s) to " + godName + ".",
                List.of(new Objective.MakeOffering(godId, 0, n)),
                QuestStatus.ACTIVE,
                List.of(new QuestReward.Favour(godId, 20f)),
                Quest.Scope.PLAYER,
                0L);
        QuestSavedData.get(level).add(player.getUUID(), quest);
        return quest;
    }
}
