package tterrag1112.life_in_the_village.Npc.Religion;

import java.util.Optional;

/**
 * Religion Rework R3a — per-(religion, rite) tuning + flavor. A small,
 * sparse-friendly descriptor: a religion specifies a profile only for the
 * rites it wants to distinguish; everything unspecified falls back to
 * {@link #DEFAULT} (scalars 1.0, no flavor), so a religion with little going
 * on still works with today's exact constants.
 *
 * <p>This is a tuning/flavor layer, deliberately NOT a rules engine:</p>
 * <ul>
 *   <li>{@code moodScale} — multiplier on the rite's mood magnitude.</li>
 *   <li>{@code pietyScale} — multiplier on the rite's piety bump.</li>
 *   <li>{@code flavor} — an optional short line woven into the rite's effect
 *       text (memory / ledger), letting each faith read differently.</li>
 *   <li>{@code relationshipBoost} — R3b-3: a community relationship nudge among
 *       a signature rite's attendees (0 = none).</li>
 *   <li>{@code treasuryBoon} — R3b-3: a village treasury boon a signature rite
 *       grants (0 = none).</li>
 * </ul>
 *
 * <p>Held by {@link ReligionContent}, not persisted (pure content) — so no
 * codec/field-cap impact on the {@link Religion} record.</p>
 */
public record RiteProfile(float moodScale, float pietyScale, Optional<String> flavor,
                          int relationshipBoost, long treasuryBoon) {

    /** Unspecified profile — today's behavior, no flavor, no extra effects. */
    public static final RiteProfile DEFAULT = new RiteProfile(1f, 1f, Optional.empty(), 0, 0L);

    public RiteProfile {
        if (flavor == null) flavor = Optional.empty();
    }

    /** Scale an integer mood magnitude (never below 0). */
    public int scaleMood(int base) {
        return Math.max(0, Math.round(base * moodScale));
    }

    /** Scale a piety delta. */
    public float scalePiety(float base) {
        return base * pietyScale;
    }

    // ── Sparse builders ──────────────────────────────────────────────────

    /** Effect-only tuning (no flavor). */
    public static RiteProfile tuned(float moodScale, float pietyScale) {
        return new RiteProfile(moodScale, pietyScale, Optional.empty(), 0, 0L);
    }

    /** Flavor-only (default magnitudes). */
    public static RiteProfile flavored(String flavor) {
        return new RiteProfile(1f, 1f, Optional.of(flavor), 0, 0L);
    }

    /** Tuning + flavor. */
    public static RiteProfile of(float moodScale, float pietyScale, String flavor) {
        return new RiteProfile(moodScale, pietyScale, Optional.ofNullable(flavor), 0, 0L);
    }

    /**
     * R3b-3 — a signature rite's effect mix: a village-wide mood scale, an
     * optional community relationship boost among attendees, an optional
     * village treasury boon, and flavor. One shared {@code SIGNATURE_RITE}
     * handler reads this so the four faiths' signature rites differ entirely
     * through content (no per-faith handlers).
     */
    public static RiteProfile signature(float moodScale, int relationshipBoost,
                                        long treasuryBoon, String flavor) {
        return new RiteProfile(moodScale, 1f, Optional.ofNullable(flavor),
                relationshipBoost, treasuryBoon);
    }
}
