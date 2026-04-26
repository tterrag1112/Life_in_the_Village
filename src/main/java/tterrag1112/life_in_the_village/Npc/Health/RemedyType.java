package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Spec line 141. Seven remedy types — one per condition family. Each
 * remedy targets a fixed set of {@link HealthCondition}s; the healer's
 * work goal picks a remedy whose target set covers the patient's
 * highest-severity condition.
 */
public enum RemedyType {
    HERBAL_POULTICE,        // wounds + burns
    FEVER_TEA,
    BONE_SALVE,
    BURN_OIL,
    STOMACH_BITTERS,
    RESPIRATORY_TONIC,
    PLAGUE_ANTIDOTE;        // rare; only treats PLAGUE_CARRIER

    private static final Map<RemedyType, Set<HealthCondition>> TARGETS;

    static {
        TARGETS = new EnumMap<>(RemedyType.class);
        TARGETS.put(HERBAL_POULTICE, EnumSet.of(
                HealthCondition.MINOR_WOUND, HealthCondition.SERIOUS_WOUND));
        TARGETS.put(FEVER_TEA, EnumSet.of(
                HealthCondition.FEVER, HealthCondition.RESPIRATORY_ILLNESS));
        TARGETS.put(BONE_SALVE, EnumSet.of(HealthCondition.BROKEN_BONE));
        TARGETS.put(BURN_OIL, EnumSet.of(HealthCondition.BURN));
        TARGETS.put(STOMACH_BITTERS, EnumSet.of(HealthCondition.STOMACH_AILMENT));
        TARGETS.put(RESPIRATORY_TONIC, EnumSet.of(HealthCondition.RESPIRATORY_ILLNESS));
        TARGETS.put(PLAGUE_ANTIDOTE, EnumSet.of(HealthCondition.PLAGUE_CARRIER));
    }

    public Set<HealthCondition> targets() {
        return TARGETS.getOrDefault(this, Set.of());
    }

    public boolean treats(HealthCondition c) {
        return targets().contains(c);
    }

    /** Looks up the best remedy type for a condition; empty if no
     *  remedy treats it (e.g. EXHAUSTION, MELANCHOLY, FRAILTY). */
    public static java.util.Optional<RemedyType> forCondition(HealthCondition c) {
        for (RemedyType r : values()) if (r.treats(c)) return java.util.Optional.of(r);
        return java.util.Optional.empty();
    }

    public static final Codec<RemedyType> CODEC = Codec.STRING.xmap(
            s -> RemedyType.valueOf(s.toUpperCase(Locale.ROOT)),
            RemedyType::name);
}
