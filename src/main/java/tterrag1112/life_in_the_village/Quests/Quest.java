package tterrag1112.life_in_the_village.Quests;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * F2a-1 — a quest on the unified base (distinct from the legacy guild {@code
 * Guilds.Adventurer.Quest}, which is untouched and re-seats onto this base in F2b).
 *
 * <p>An immutable record: {@code objectives} is an ORDERED list (1 for a simple quest,
 * N for a future grand quest); F2a-1 completes a quest when <b>all</b> objectives are
 * done (no sequential gating yet — the list supports N, the gating is a later
 * refinement). Per-player; persisted in {@link QuestSavedData}.</p>
 */
public record Quest(UUID questId, QuestGiver giver, String title, String description,
                    List<Objective> objectives, QuestStatus status, List<QuestReward> rewards,
                    Quest.Scope scope, long deadlineTick) {

    /** Who the quest is for. PLAYER for now (party/village scopes later). */
    public enum Scope { PLAYER }

    public Quest {
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        rewards    = rewards    == null ? List.of() : List.copyOf(rewards);
        if (status == null) status = QuestStatus.OFFERED;
        if (scope == null)  scope = Scope.PLAYER;
    }

    private static final Codec<Scope> SCOPE_CODEC = Codec.STRING.xmap(
            s -> Scope.valueOf(s.toUpperCase(Locale.ROOT)), Scope::name);

    public static final Codec<Quest> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("questId").forGetter(Quest::questId),
            QuestGiver.CODEC.fieldOf("giver").forGetter(Quest::giver),
            Codec.STRING.fieldOf("title").forGetter(Quest::title),
            Codec.STRING.optionalFieldOf("description", "").forGetter(Quest::description),
            Objective.CODEC.listOf().optionalFieldOf("objectives", List.of()).forGetter(Quest::objectives),
            QuestStatus.CODEC.optionalFieldOf("status", QuestStatus.OFFERED).forGetter(Quest::status),
            QuestReward.CODEC.listOf().optionalFieldOf("rewards", List.of()).forGetter(Quest::rewards),
            SCOPE_CODEC.optionalFieldOf("scope", Scope.PLAYER).forGetter(Quest::scope),
            Codec.LONG.optionalFieldOf("deadlineTick", 0L).forGetter(Quest::deadlineTick)
    ).apply(i, Quest::new));

    /** True when every objective is complete (the F2a-1 completion rule). */
    public boolean allComplete() {
        return !objectives.isEmpty() && objectives.stream().allMatch(Objective::isComplete);
    }

    public Quest withObjectives(List<Objective> updated) {
        return new Quest(questId, giver, title, description, updated, status, rewards, scope, deadlineTick);
    }

    public Quest withStatus(QuestStatus s) {
        return new Quest(questId, giver, title, description, objectives, s, rewards, scope, deadlineTick);
    }
}
