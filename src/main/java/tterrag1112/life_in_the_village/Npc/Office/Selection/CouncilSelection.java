package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal vote among a {@link CouncilResolver named pool}. Each voter
 * casts a relationship-weighted ballot for every eligible candidate; the
 * candidate with the highest summed weight wins.
 *
 * <p>Voters score each candidate as
 * {@code w = max(1, npcRel.score + 50)} where {@code score} is the
 * voter's NPC↔NPC relationship value (range −100..+100). The +50 floor
 * ensures even neutral voters cast a non-zero ballot, so a council with
 * no formed relationships still picks the highest-skilled candidate via
 * the meritocratic-style tiebreak.</p>
 *
 * <p>If the council is empty, returns empty so the driver can fall back
 * (typically to MERITOCRATIC). If no eligible candidates exist, returns
 * empty unconditionally — vacancy stands.</p>
 */
public final class CouncilSelection implements OfficeSelectionEngine {

    @Override public SelectionMethod method() { return SelectionMethod.COUNCIL; }

    @Override
    public Optional<OfficeHolding> select(OfficeSelectionContext ctx) {
        List<TownspersonMob> council = CouncilResolver.resolve(ctx);
        if (council.isEmpty()) return Optional.empty();

        List<OfficeCandidate> candidates = CandidatePool.buildFor(ctx);
        if (candidates.isEmpty()) return Optional.empty();

        // Sum relationship-weighted votes from every council member.
        Map<UUID, Double> voteScore = new HashMap<>();
        Map<UUID, OfficeCandidate> byUuid = new HashMap<>();
        for (OfficeCandidate c : candidates) byUuid.put(c.uuid(), c);

        for (TownspersonMob voter : council) {
            for (OfficeCandidate c : candidates) {
                int rel = voter.getNpcRelationships().getScore(c.uuid());
                // Floor at +1 so the ballot still expresses a preference
                // among unfamiliar candidates (skill-based tiebreak then
                // dominates).
                double weight = Math.max(1d, rel + 50d);
                voteScore.merge(c.uuid(), weight, Double::sum);
            }
        }

        // Compose the ranking: vote score, then primary skill as a coarse
        // pre-tiebreak, then the spec tiebreak chain.
        List<OfficeCandidate> ranked = candidates.stream()
                .map(c -> c.withScore(voteScore.getOrDefault(c.uuid(), 0d) + 0.001 * c.primarySkillLevel()))
                .sorted(SelectionTiebreaker.byScoreThenTiebreak())
                .toList();

        OfficeCandidate winner = ranked.get(0);
        long termEnd = ctx.def().isIndefiniteTerm()
                ? 0L
                : ctx.tick() + (long) ctx.def().termDays() * 24000L;
        return Optional.of(OfficeHolding.heldByNpc(
                ctx.def().id(), ctx.orgId(), winner.uuid(),
                ctx.tick(), termEnd, SelectionMethod.COUNCIL));
    }
}
