package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;

import java.util.List;
import java.util.Optional;

/**
 * "Highest skilled wins" selection. Score = primary skill level + small
 * trait nudges per the office's nature (Industry weights all offices
 * lightly — diligent NPCs are preferred everywhere; Ambition only on
 * leader/master offices where it matches the role).
 *
 * <p>Phase 3 spec call-out: "scores candidates by relevant skill +
 * traits (per-office prerequisite mapping per spec)". v1 keeps the trait
 * nudges generic — the per-office tweaks land alongside the office-
 * specific subsystem sessions (constable values Courage, treasurer values
 * Honesty, etc.).</p>
 */
public final class MeritocraticSelection implements OfficeSelectionEngine {

    @Override public SelectionMethod method() { return SelectionMethod.MERITOCRATIC; }

    @Override
    public Optional<OfficeHolding> select(OfficeSelectionContext ctx) {
        List<OfficeCandidate> pool = CandidatePool.buildFor(ctx);
        if (pool.isEmpty()) return Optional.empty();

        List<OfficeCandidate> scored = pool.stream()
                .map(c -> c.withScore(scoreOf(c, ctx)))
                .sorted(SelectionTiebreaker.byScoreThenTiebreak())
                .toList();

        OfficeCandidate winner = scored.get(0);
        long termEnd = ctx.def().isIndefiniteTerm()
                ? 0L
                : ctx.tick() + (long) ctx.def().termDays() * 24000L;
        return Optional.of(OfficeHolding.heldByNpc(
                ctx.def().id(), ctx.orgId(), winner.uuid(),
                ctx.tick(), termEnd, SelectionMethod.MERITOCRATIC));
    }

    private static double scoreOf(OfficeCandidate c, OfficeSelectionContext ctx) {
        double score = c.primarySkillLevel();
        // Light trait nudges — not enough to overwhelm skill, but enough
        // to settle near-ties without falling through to age/UUID order.
        score += 5f * c.npc().getTraitVector().get(TraitAxis.INDUSTRY);
        // Honesty is a virtue across every office — corrupt holders wreck
        // org outcomes. Phase 3 corruption mechanics may amplify this.
        score += 3f * c.npc().getTraitVector().get(TraitAxis.HONESTY);
        return score;
    }
}
