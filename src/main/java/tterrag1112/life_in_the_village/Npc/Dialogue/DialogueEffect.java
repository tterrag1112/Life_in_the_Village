package tterrag1112.life_in_the_village.Npc.Dialogue;

import java.util.HashMap;
import java.util.Map;

/**
 * One side effect attached to a {@link DialogueLine}. The {@code type}
 * tag selects the dispatch path in {@link EffectDispatcher}; the
 * {@code params} map carries type-specific arguments. Keeping a single
 * record rather than a sealed interface per type lets Phase 6 JSON
 * loading deserialise without polymorphic codecs (matches spec line 70).
 */
public record DialogueEffect(DialogueEffectType type, Map<String, String> params) {

    public DialogueEffect {
        if (params == null) params = Map.of();
        else params = Map.copyOf(params);
    }

    public String param(String key) { return params.get(key); }

    public String paramOr(String key, String fallback) {
        return params.getOrDefault(key, fallback);
    }

    // ── Convenience factories used by code-defined trees ───────────────────

    public static DialogueEffect endConversation() {
        return new DialogueEffect(DialogueEffectType.END_CONVERSATION, Map.of());
    }

    public static DialogueEffect moodApply(String triggerName) {
        return new DialogueEffect(DialogueEffectType.MOOD_APPLY,
                Map.of("trigger", triggerName));
    }

    public static DialogueEffect memoryRefresh(String memoryId) {
        return new DialogueEffect(DialogueEffectType.MEMORY_REFRESH,
                Map.of("memoryId", memoryId));
    }

    public static DialogueEffect memoryCreate(String memoryType, String target,
                                              int value, String summary) {
        Map<String, String> p = new HashMap<>();
        p.put("type", memoryType);
        p.put("target", target);
        p.put("value", Integer.toString(value));
        p.put("summary", summary);
        return new DialogueEffect(DialogueEffectType.MEMORY_CREATE, p);
    }

    public static DialogueEffect knowledgeShare(String topic) {
        return new DialogueEffect(DialogueEffectType.KNOWLEDGE_SHARE,
                Map.of("topic", topic));
    }

    public static DialogueEffect relationshipDelta(String target, int delta) {
        return new DialogueEffect(DialogueEffectType.RELATIONSHIP_DELTA,
                Map.of("target", target, "delta", Integer.toString(delta)));
    }

    public static DialogueEffect goalProgress(String goalType, int delta) {
        return new DialogueEffect(DialogueEffectType.GOAL_PROGRESS,
                Map.of("goalType", goalType, "delta", Integer.toString(delta)));
    }

    public static DialogueEffect traitDrift(String axis, float delta) {
        return new DialogueEffect(DialogueEffectType.TRAIT_DRIFT,
                Map.of("axis", axis, "delta", Float.toString(delta)));
    }

    public static DialogueEffect openScreen(String screen) {
        return new DialogueEffect(DialogueEffectType.OPEN_SCREEN,
                Map.of("screen", screen));
    }
}
