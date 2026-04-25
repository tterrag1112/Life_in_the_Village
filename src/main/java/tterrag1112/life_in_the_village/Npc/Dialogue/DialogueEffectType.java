package tterrag1112.life_in_the_village.Npc.Dialogue;

/**
 * Side-effect categories for {@link DialogueEffect}. Each effect's
 * concrete behaviour is dispatched by {@link EffectDispatcher} based on
 * this tag plus the {@code params} map carried on the effect record.
 *
 * <p>Spec: {@code docs/npc_redesign/08-dialogue-tree.md} (line 60).</p>
 */
public enum DialogueEffectType {
    /** {@code params}: {@code trigger} (MoodTrigger.name()), optional {@code magnitude} int. */
    MOOD_APPLY,
    /** {@code params}: {@code memoryId} (UUID string). */
    MEMORY_REFRESH,
    /**
     * {@code params}: {@code type} (MemoryType.name()),
     * {@code target} (Target enum name; resolves to UUID),
     * {@code value} (int 1..100), {@code summary} (string).
     */
    MEMORY_CREATE,
    /**
     * Transfer a low-fidelity copy of one of the speaker's recent
     * knowledge entries to the listener. {@code params}:
     * {@code topic} (string).
     */
    KNOWLEDGE_SHARE,
    /** {@code params}: {@code target} (Target name), {@code delta} (int). */
    RELATIONSHIP_DELTA,
    /** {@code params}: {@code goalType} (LifeGoalType name), {@code delta} (int). */
    GOAL_PROGRESS,
    /** {@code params}: {@code axis} (TraitAxis name), {@code delta} (float). */
    TRAIT_DRIFT,
    /**
     * Triggers an external screen — Phase 1 stub; Phase 3 routes
     * trade / quest UIs through {@code params:screen}.
     */
    OPEN_SCREEN,
    /** Ends the dialogue session immediately after this line. */
    END_CONVERSATION
}
