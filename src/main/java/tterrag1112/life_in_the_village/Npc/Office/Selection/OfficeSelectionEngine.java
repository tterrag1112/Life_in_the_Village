package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.Optional;

/**
 * Strategy interface implemented once per {@link SelectionMethod}.
 * Implementations return either a concrete {@link OfficeHolding} (NPC or
 * vacant) or {@link Optional#empty()} to signal "no candidate could be
 * picked under this method right now — leave the office unchanged."
 *
 * <p>The driver — {@link tterrag1112.life_in_the_village.Npc.Office.OfficeElection}
 * — handles tick bookkeeping (term-end, persistence, life-event firing),
 * so engines focus purely on candidate ranking.</p>
 *
 * <p>Spec: {@code 06-office-framework.md} → "Selection methods".</p>
 */
public interface OfficeSelectionEngine {

    /** {@link SelectionMethod} this engine handles. */
    SelectionMethod method();

    /**
     * Returns the chosen holding, or empty if the engine declines to fill
     * the office now (e.g. ASCENSION found no eligible-prereq candidate;
     * APPOINTED was invoked without an authorised appointer).
     */
    Optional<OfficeHolding> select(OfficeSelectionContext ctx);
}
