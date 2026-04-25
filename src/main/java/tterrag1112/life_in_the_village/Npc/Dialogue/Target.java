package tterrag1112.life_in_the_village.Npc.Dialogue;

import java.util.UUID;

/**
 * Symbolic referent in a {@link DialogueContext} — predicates and
 * effects use this rather than embedding raw UUIDs so the same tree
 * can run for any speaker / listener pair.
 *
 * <p>Spec: {@code docs/npc_redesign/08-dialogue-tree.md} (line 102).</p>
 */
public enum Target {
    /** The NPC speaking. */
    SELF,
    /** The other party in this exchange (player or NPC). */
    LISTENER,
    /** The player explicitly. {@code null} for NPC-NPC chat. */
    PLAYER,
    /** Speaker's household head (or speaker itself if HEAD). */
    HOUSEHOLD_HEAD,
    /** Speaker's spouse, if any. */
    SPOUSE;

    /** Returns the UUID for this target in {@code ctx}, or {@code null} when undefined. */
    public UUID resolve(DialogueContext ctx) {
        return switch (this) {
            case SELF -> ctx.speaker() == null ? null : ctx.speaker().getUUID();
            case LISTENER -> ctx.listenerUuid();
            case PLAYER -> ctx.player() == null ? null : ctx.player().getUUID();
            case HOUSEHOLD_HEAD -> ctx.speaker() == null ? null
                    : ctx.speaker().getFamily().getHeadOfHouseholdId().orElse(null);
            case SPOUSE -> ctx.speaker() == null ? null
                    : ctx.speaker().getFamily().getSpouseId().orElse(null);
        };
    }
}
