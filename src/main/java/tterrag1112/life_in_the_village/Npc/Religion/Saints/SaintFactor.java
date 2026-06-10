package tterrag1112.life_in_the_village.Npc.Religion.Saints;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Saints &amp; Relics SR1 — a living saint's <b>personal divine ease</b> with their
 * patron god: a multiplier ≥ 1.0, mirroring the sacred-space
 * ({@code SacredSpace.amplifierAt}) and sacred-time ({@code SacredTime.holyDayFactor})
 * amplifiers so it composes the same way. Folded beside them into the favour grant and
 * miracle paths; a non-saint (or a saint of a different god) reads <b>1.0</b> —
 * today's exact numbers.
 *
 * <p>Self-focused (SR1 scope): it eases the saint's OWN favour/miracles with the
 * patron god; it never bypasses the tier gate or the favour cap (those live in the
 * effects, unchanged). Bless-others is a later layer.</p>
 */
public final class SaintFactor {

    private SaintFactor() {}

    /** The personal ease a living saint enjoys with their patron god (tunable). */
    public static final float SAINT_AMPLIFIER = 1.25f;

    /** {@link #SAINT_AMPLIFIER} when {@code beingId} is a living saint of {@code godId},
     *  else 1.0 (unchanged). The one home for the saint multiplier. */
    public static float amplifierFor(ServerLevel level, UUID beingId, String godId) {
        if (level == null || beingId == null || godId == null) return 1.0f;
        return SaintsSavedData.get(level).isLivingSaint(beingId, godId)
                ? SAINT_AMPLIFIER : 1.0f;
    }
}
