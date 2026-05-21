package tterrag1112.life_in_the_village.Npc.Office;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.List;

/**
 * Phase 6.3.2.d — pluggable eligibility check applied to a candidate
 * just before they're seated in an office. The default behavior across
 * the codebase is {@link #ALWAYS} (the predicate is a no-op until
 * culture / content packs register a real implementation via
 * {@link OfficeEligibilityRegistry}).
 *
 * <h3>When this fires</h3>
 * <ul>
 *   <li>{@link OfficeElection#runElectionFor} — after the selection
 *       engine picks a candidate, before {@code OfficeState.set}.
 *       Failure leaves the office in its prior state for that cycle.</li>
 *   <li>{@link OfficeElection#seatPlayer} / {@link OfficeElection#seatNpc}
 *       — appointment / verb-driven seating. Failure is a no-op; the
 *       caller can detect via the {@code boolean} return on the gated
 *       overloads. Force-variants of both methods skip the predicate
 *       for admin / debug use.</li>
 * </ul>
 *
 * <h3>Extensibility point</h3>
 * <p>This is the integration seam for culture-specific office
 * eligibility rules (per-culture profession overrides, dynastic gates,
 * skill thresholds). Concrete implementations land in content passes;
 * they register via
 * {@link OfficeEligibilityRegistry#register(String, String, OfficeEligibilityPredicate)}
 * (culture-scoped) or {@link OfficeEligibilityRegistry#registerForOffice(String, OfficeEligibilityPredicate)}
 * (culture-agnostic, all cultures).
 */
@FunctionalInterface
public interface OfficeEligibilityPredicate {

    boolean qualifies(TownspersonMob npc, OfficeContext context);

    /** Default — accepts every candidate. */
    OfficeEligibilityPredicate ALWAYS = (npc, ctx) -> true;

    /**
     * Composer: every supplied predicate must return true. Useful for
     * stacking skill-based gates atop culture-based ones without
     * modifying the {@link OfficeDefinition} itself.
     */
    static OfficeEligibilityPredicate all(List<OfficeEligibilityPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) return ALWAYS;
        if (predicates.size() == 1) return predicates.get(0);
        List<OfficeEligibilityPredicate> copy = List.copyOf(predicates);
        return (npc, ctx) -> {
            for (OfficeEligibilityPredicate p : copy) {
                if (!p.qualifies(npc, ctx)) return false;
            }
            return true;
        };
    }
}
