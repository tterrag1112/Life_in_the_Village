package tterrag1112.life_in_the_village.Quests;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.God;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;

import java.util.List;
import java.util.UUID;

/**
 * F2a — minimal quest issuance (the proving + deity-issuance stub). F2a-3 grows this
 * into V3-calling graduation and rich givers (deity / saint / clergy). For now it mints
 * the four religious quest kinds the {@code MAKE_OFFERING}/{@code VISIT_SACRED_SITE}/
 * {@code ENSHRINE_RELIC}/{@code PERFORM_RITES} objectives prove.
 */
public final class QuestIssuer {

    private QuestIssuer() {}

    /** The objective kinds {@link #grant} can issue (the F2a debug vocabulary). */
    public static final String[] KINDS = { "offering", "pilgrimage", "relic", "rites" };

    /** Issues an ACTIVE religious quest of {@code kind} (offering / pilgrimage / relic /
     *  rites) for {@code godId} to {@code player}, rewarding favour with that god.
     *  Returns the issued quest, or null for an unknown god / kind. */
    public static Quest grant(ServerLevel level, ServerPlayer player,
                              String godId, String kind, int count) {
        God god = GodRegistry.get(godId);
        if (god == null) return null;
        int n = Math.max(1, count);
        String gn = god.displayName();
        Objective objective;
        String title;
        switch (kind == null ? "" : kind) {
            case "offering"   -> { objective = new Objective.MakeOffering(godId, 0, n);
                                   title = "Offerings to " + gn; }
            case "pilgrimage" -> { objective = new Objective.VisitSacredSite(godId, 0, 1);
                                   title = "Pilgrimage to " + gn; }
            case "relic"      -> { objective = new Objective.EnshrineRelic(godId, 0, 1);
                                   title = "Enshrine a relic of " + gn; }
            case "rites"      -> { objective = new Objective.PerformRites(godId, 0, n);
                                   title = "Serve " + gn + "'s rites"; }
            default -> { return null; }
        }
        Quest quest = new Quest(
                UUID.randomUUID(),
                new QuestGiver(QuestGiver.Type.DIVINE, godId),
                title, title + ".",
                List.of(objective),
                QuestStatus.ACTIVE,
                List.of(new QuestReward.Favour(godId, 20f)),
                Quest.Scope.PLAYER,
                0L);
        QuestSavedData.get(level).add(player.getUUID(), quest);
        return quest;
    }
}
