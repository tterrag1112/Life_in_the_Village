package tterrag1112.life_in_the_village.Npc.Office;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6.3.2.d — runtime registry for
 * {@link OfficeEligibilityPredicate} implementations. Lookups are
 * keyed by (culture id, office id) with culture-agnostic predicates
 * registered under a wildcard culture id.
 *
 * <p>Eligibility predicates are <em>behavior</em>, not data, so they
 * intentionally live outside the codec-driven {@code Culture}
 * structures: content passes register them at mod init.
 *
 * <h3>Composition</h3>
 * <p>Multiple predicates per (culture, office) compose with AND
 * semantics via {@link OfficeEligibilityPredicate#all}. The resolver
 * combines a culture-specific predicate with any culture-agnostic
 * "applies to all cultures" predicate registered for the same office,
 * so a skill-based gate and a culture-based gate can stack without
 * either knowing about the other.
 */
public final class OfficeEligibilityRegistry {

    private OfficeEligibilityRegistry() {}

    /** Wildcard culture key for "applies to every culture". */
    public static final String ANY_CULTURE = "*";

    /** (cultureId | "*") + ":" + officeId → predicate. */
    private static final Map<String, OfficeEligibilityPredicate> PREDICATES = new HashMap<>();

    private static String key(String cultureId, String officeId) {
        return (cultureId == null ? ANY_CULTURE : cultureId) + ":" + officeId;
    }

    /**
     * Registers a predicate for one (culture, office) pair. If a
     * predicate already exists for the pair, the existing one is AND'd
     * with the new one so registration order is commutative.
     */
    public static void register(String cultureId, String officeId,
                                OfficeEligibilityPredicate predicate) {
        if (predicate == null) return;
        PREDICATES.merge(key(cultureId, officeId), predicate,
                (existing, next) -> OfficeEligibilityPredicate.all(List.of(existing, next)));
    }

    /** Culture-agnostic registration: applies the predicate to {@code officeId} in every culture. */
    public static void registerForOffice(String officeId, OfficeEligibilityPredicate predicate) {
        register(ANY_CULTURE, officeId, predicate);
    }

    /**
     * Resolves the effective predicate for the given context: the
     * AND of the culture-specific predicate (if any) and the
     * culture-agnostic predicate registered for the same office (if
     * any). Returns {@link OfficeEligibilityPredicate#ALWAYS} when
     * neither is registered.
     */
    public static OfficeEligibilityPredicate resolve(@Nullable String cultureId, String officeId) {
        OfficeEligibilityPredicate cultureScoped = PREDICATES.get(key(cultureId, officeId));
        OfficeEligibilityPredicate global = PREDICATES.get(key(ANY_CULTURE, officeId));
        if (cultureScoped == null && global == null) return OfficeEligibilityPredicate.ALWAYS;
        if (cultureScoped == null) return global;
        if (global == null) return cultureScoped;
        return OfficeEligibilityPredicate.all(List.of(global, cultureScoped));
    }

    /** Test / debug — drops everything. */
    public static void clearAll() { PREDICATES.clear(); }
}
