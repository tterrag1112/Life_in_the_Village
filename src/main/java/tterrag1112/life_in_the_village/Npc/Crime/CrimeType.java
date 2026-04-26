package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;

/**
 * Spec line 17 — 13 entries across {@link CrimeSeverity} tiers.
 *
 * <p>Severity drives the conviction threshold, presider selection
 * (MINOR/MODERATE → bailiff; SERIOUS/CAPITAL → leader), and the
 * {@code DOUBLE_PUNISHMENT} escalation rule.</p>
 */
public enum CrimeType {
    THEFT_MINOR     (CrimeSeverity.MINOR),       // item < 20 bronze
    THEFT_MAJOR     (CrimeSeverity.MODERATE),    // item ≥ 20 bronze
    ASSAULT         (CrimeSeverity.MODERATE),
    MURDER          (CrimeSeverity.CAPITAL),
    FRAUD           (CrimeSeverity.MODERATE),
    CONTRACT_BREACH (CrimeSeverity.MINOR),
    VANDALISM       (CrimeSeverity.MINOR),
    TRESPASSING     (CrimeSeverity.MINOR),
    SMUGGLING       (CrimeSeverity.MODERATE),
    SEAL_VIOLATION  (CrimeSeverity.MINOR),
    TAX_EVASION     (CrimeSeverity.MODERATE),
    PERJURY         (CrimeSeverity.MODERATE),
    BRIBERY         (CrimeSeverity.SERIOUS);

    private final CrimeSeverity severity;

    CrimeType(CrimeSeverity severity) { this.severity = severity; }

    public CrimeSeverity severity() { return severity; }

    public static final Codec<CrimeType> CODEC =
            Codec.STRING.xmap(CrimeType::valueOf, CrimeType::name);
}
