// FILE: src/main/java/tterrag1112/life_in_the_village/Village/SettlementTier.java
package tterrag1112.life_in_the_village.Village;

/**
 * Settlement tier in the kingdom hierarchy. Phase 21 schema addition;
 * consumed by the future kingdom rework to assign roles, gate which
 * village types can become a capital, and order ordinal comparisons
 * (e.g. "this kingdom needs at least one VILLAGE or larger").
 *
 * <p>Order is meaningful — lower index = smaller. Future ordering
 * checks rely on {@link #ordinal()}.
 *
 * <p>{@code CAPITAL} is a separate tier rather than a flag on
 * {@code CITY} because capitals really are bigger and special even
 * when they're not the active capital. The {@code can_be_capital}
 * field on {@code VillageTypeData} gates eligibility separately —
 * a {@code TOWN} or larger village type may opt in.
 */
public enum SettlementTier {
    OUTPOST,    // smallest, defensive or remote
    HAMLET,     // small farming or fishing
    VILLAGE,    // standard, mixed economy
    TOWN,       // commercial, larger
    CITY,       // major trade or political center
    CAPITAL;    // kingdom seat, eligible only if can_be_capital

    /** Parses a tier name case-insensitively. Returns null for unknown
     *  names so callers can throw a contextual exception. */
    public static SettlementTier fromName(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
