package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.Optional;

/**
 * "Holder selects own successor; if vacant with no designation, falls
 * back to council or merit." Phase 0 has no designated-successor field
 * on {@link OfficeHolding}, so v1 always falls through.
 *
 * <p>Order: COUNCIL → MERITOCRATIC.</p>
 *
 * <p>Phase 5+ scribal-contract pass adds a {@code designatedSuccessor}
 * field; this engine then prefers it before falling through. Documented
 * in 06 Revision Notes.</p>
 */
public final class DictatorialSelection implements OfficeSelectionEngine {

    @Override public SelectionMethod method() { return SelectionMethod.DICTATORIAL; }

    @Override
    public Optional<OfficeHolding> select(OfficeSelectionContext ctx) {
        Optional<OfficeHolding> council = new CouncilSelection().select(ctx);
        if (council.isPresent()) return council;
        return new MeritocraticSelection().select(ctx);
    }
}
