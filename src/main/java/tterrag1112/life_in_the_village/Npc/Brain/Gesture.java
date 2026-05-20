package tterrag1112.life_in_the_village.Npc.Brain;

/**
 * Enumeration of idle gestures available to the Brain. Each value
 * corresponds 1:1 to an {@code AnimationState} field on TownspersonMob
 * and an entity-event ID broadcast from {@code triggerGesture}.
 *
 * <p>Visual keyframes / poses are intentionally minimal at Phase 6.1.a
 * — this enum is the contract; polish authoring is a later pass.
 */
public enum Gesture {
    WAVE,
    STRETCH,
    LOOK_AROUND,
    YAWN,
    NOD,
    FRIENDLY_WAVE,
    HEAD_SHAKE,
    SIGH,
    SLOUCH,
    LEAN
}
