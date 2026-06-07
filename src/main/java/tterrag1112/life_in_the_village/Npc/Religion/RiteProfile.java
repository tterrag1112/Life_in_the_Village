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
 * </ul>
 *
 * <p>Held by {@link ReligionContent}, not persisted (pure content) — so no
 * codec/field-cap impact on the {@link Religion} record.</p>
 */
public record RiteProfile(float moodScale, float pietyScale, Optional<String> flavor) {

    /** Unspecified profile — today's behavior, no flavor. */
    public static final RiteProfile DEFAULT = new RiteProfile(1f, 1f, Optional.empty());

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
        return new RiteProfile(moodScale, pietyScale, Optional.empty());
    }

    /** Flavor-only (default magnitudes). */
    public static RiteProfile flavored(String flavor) {
        return new RiteProfile(1f, 1f, Optional.of(flavor));
    }

    /** Tuning + flavor. */
    public static RiteProfile of(float moodScale, float pietyScale, String flavor) {
        return new RiteProfile(moodScale, pietyScale, Optional.ofNullable(flavor));
    }
}
