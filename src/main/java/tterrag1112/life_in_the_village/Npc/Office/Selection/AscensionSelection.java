package tterrag1112.life_in_the_village.Npc.Office.Selection;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Promotes from a defined subordinate office in the same hierarchy.
 *
 * <p>The ascension chain is whatever {@link
 * tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition#ascensionPrereqs}
 * lists for the target office. The candidate must currently hold a
 * prereq office and have held it for at least
 * {@code ascensionMinHoldDays} (in-game days).</p>
 *
 * <p>Phase 3 spec note: ascension chains are sparse in v1. Only
 * {@code guild_master ← master_of_apprentices} is populated by the Phase 0
 * registry; {@code village_leader} ascension is a recognised but not
 * required selection method (the spec also lists ELECTIVE / HEREDITARY
 * as alternatives), so when an empty chain meets ASCENSION the engine
 * falls back to MERITOCRATIC. Documented in 06 Revision Notes.</p>
 */
public final class AscensionSelection implements OfficeSelectionEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Override public SelectionMethod method() { return SelectionMethod.ASCENSION; }

    @Override
    public Optional<OfficeHolding> select(OfficeSelectionContext ctx) {
        List<String> prereqs = ctx.def().ascensionPrereqs();
        if (prereqs.isEmpty()) {
            // Spec: "ASCENSION via village politics" without a defined
            // prereq is implementation-defined. v1 falls back to merit.
            LOGGER.debug("[OfficeElection] ASCENSION on '{}' with empty prereq chain; falling back to MERITOCRATIC",
                    ctx.def().id());
            return new MeritocraticSelection().select(ctx);
        }

        List<UUID> ascensionEligible = collectEligible(ctx, prereqs);
        if (ascensionEligible.isEmpty()) return Optional.empty();

        // From the eligibility shortlist, score with the meritocratic
        // engine — the same rule already deals with min-skill, life
        // stage, and traits.
        List<OfficeCandidate> pool = CandidatePool.buildFor(ctx).stream()
                .filter(c -> ascensionEligible.contains(c.uuid()))
                .toList();
        if (pool.isEmpty()) return Optional.empty();

        OfficeCandidate winner = pool.stream()
                .map(c -> c.withScore(c.primarySkillLevel()))
                .sorted(SelectionTiebreaker.byScoreThenTiebreak())
                .findFirst()
                .orElseThrow();

        long termEnd = ctx.def().isIndefiniteTerm()
                ? 0L
                : ctx.tick() + (long) ctx.def().termDays() * 24000L;
        return Optional.of(OfficeHolding.heldByNpc(
                ctx.def().id(), ctx.orgId(), winner.uuid(),
                ctx.tick(), termEnd, SelectionMethod.ASCENSION));
    }

    /**
     * Walks all loaded {@link OfficeState}s that this org's prereq
     * offices belong to, returning the UUIDs of holders who have served
     * at least {@code ascensionMinHoldDays}.
     */
    private static List<UUID> collectEligible(OfficeSelectionContext ctx, List<String> prereqs) {
        long minHoldTicks = (long) ctx.def().ascensionMinHoldDays() * 24000L;
        List<UUID> out = new ArrayList<>();

        // For ascension, we look across every same-orgType OfficeState in
        // the level — a guild_master ascends from any master_of_apprentices
        // who held the office long enough, not necessarily this guild's.
        // Simplification: only the SAME org's OfficeState is consulted in
        // v1, which keeps ascension within the organisation. Documented
        // in 06 Revision Notes.
        OfficeState state = ctx.orgState();
        for (String prereqId : prereqs) {
            OfficeHolding holding = state.get(prereqId).orElse(null);
            if (holding == null || holding.isVacant()) continue;
            if (holding.holderIsPlayer()) continue;
            if (ctx.tick() - holding.termStartTick() < minHoldTicks) continue;
            holding.holderNpcId().ifPresent(out::add);
        }
        // Same-village leader-from-bailiff path: guard NPC ascends to
        // VILLAGE_LEADER from VILLAGE_BAILIFF. The spec lists this
        // implicitly via ASCENSION on village_leader; populated only when
        // the registry adds a chain. v1 chain-list-empty handled above.
        return out;
    }

    /** Helper called by debug commands to expose the eligibility decision. */
    public static List<UUID> debugEligible(OfficeSelectionContext ctx) {
        return collectEligible(ctx, ctx.def().ascensionPrereqs());
    }
}
