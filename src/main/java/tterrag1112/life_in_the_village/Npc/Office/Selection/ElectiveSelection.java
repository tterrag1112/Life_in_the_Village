package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Citizen-wide election with relationship + trait-perception weighting.
 * The voter pool is every adult NPC assigned to the same village (or to
 * any village in a kingdom-scoped election). For each voter, each
 * candidate gets:
 *
 * <pre>
 *   score = max(1, voter.rel(candidate).score + 50)
 *           + 25 * candidate.traitMatch(office)
 * </pre>
 *
 * <p>The trait-match term lets voters reward office-relevant traits even
 * when no relationship is formed yet — e.g., a Bold candidate wins
 * constable elections, an Honest candidate wins treasurer elections.
 * The mapping table is per-office and lives in {@link #officeTrait}.</p>
 *
 * <p>Falls back to MERITOCRATIC when no electorate is found (rare —
 * means the village is empty / unloaded).</p>
 */
public final class ElectiveSelection implements OfficeSelectionEngine {

    @Override public SelectionMethod method() { return SelectionMethod.ELECTIVE; }

    @Override
    public Optional<OfficeHolding> select(OfficeSelectionContext ctx) {
        List<TownspersonMob> electorate = electorate(ctx);
        if (electorate.isEmpty()) return new MeritocraticSelection().select(ctx);

        List<OfficeCandidate> candidates = CandidatePool.buildFor(ctx);
        if (candidates.isEmpty()) return Optional.empty();

        TraitAxis officeAxis = officeTrait(ctx.def().id());
        Map<UUID, Double> votes = new HashMap<>();
        for (TownspersonMob voter : electorate) {
            for (OfficeCandidate c : candidates) {
                int rel = voter.getNpcRelationships().getScore(c.uuid());
                double weight = Math.max(1d, rel + 50d);
                if (officeAxis != null) {
                    weight += 25d * c.npc().getTraitVector().get(officeAxis);
                }
                votes.merge(c.uuid(), weight, Double::sum);
            }
        }

        List<OfficeCandidate> ranked = candidates.stream()
                .map(c -> c.withScore(votes.getOrDefault(c.uuid(), 0d)
                        + 0.001 * c.primarySkillLevel()))
                .sorted(SelectionTiebreaker.byScoreThenTiebreak())
                .toList();

        OfficeCandidate winner = ranked.get(0);
        long termEnd = ctx.def().isIndefiniteTerm()
                ? 0L
                : ctx.tick() + (long) ctx.def().termDays() * 24000L;
        return Optional.of(OfficeHolding.heldByNpc(
                ctx.def().id(), ctx.orgId(), winner.uuid(),
                ctx.tick(), termEnd, SelectionMethod.ELECTIVE));
    }

    /**
     * Per-office trait the electorate cares about. Empty mapping → no
     * trait-perception term in the score.
     */
    private static TraitAxis officeTrait(String officeId) {
        return switch (officeId) {
            case tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_LEADER ->
                    TraitAxis.SOCIABILITY;
            case tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_CONSTABLE,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_BAILIFF ->
                    TraitAxis.COURAGE;
            case tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_TREASURER,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_SCRIBE,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.GUILD_TREASURER,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.GUILD_REGISTRAR ->
                    TraitAxis.HONESTY;
            case tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.VILLAGE_PRIEST,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.TEMPLE_HIGH_PRIEST ->
                    TraitAxis.COMPASSION;
            case tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.GUILD_MASTER,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.MASTER_OF_APPRENTICES,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.KINGDOM_KING,
                 tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry.KINGDOM_CHANCELLOR ->
                    TraitAxis.AMBITION;
            default -> null;
        };
    }

    private static List<TownspersonMob> electorate(OfficeSelectionContext ctx) {
        List<TownspersonMob> out = new ArrayList<>();
        // VILLAGE: assigned-village name match. KINGDOM: any village name
        // in the kingdom. GUILD/COMPANY/TEMPLE: spec lists no electorate
        // pattern — they default empty and the engine falls through.
        switch (ctx.orgType()) {
            case VILLAGE -> {
                Village v = ctx.vdata().getVillageById(ctx.orgId()).orElse(null);
                if (v == null) return out;
                String name = v.getName();
                for (var entity : ctx.level().getEntities().getAll()) {
                    if (!(entity instanceof TownspersonMob npc)) continue;
                    if (!npc.isAdult()) continue;
                    if (npc.getAssignedVillageName().filter(n -> n.equals(name)).isPresent()) {
                        out.add(npc);
                    }
                }
            }
            case KINGDOM -> {
                var k = ctx.vdata().getKingdomById(ctx.orgId()).orElse(null);
                if (k == null) return out;
                java.util.Set<String> names = new java.util.HashSet<>();
                for (UUID vid : k.getVillageIds()) {
                    ctx.vdata().getVillageById(vid).ifPresent(v -> names.add(v.getName()));
                }
                for (var entity : ctx.level().getEntities().getAll()) {
                    if (!(entity instanceof TownspersonMob npc)) continue;
                    if (!npc.isAdult()) continue;
                    if (npc.getAssignedVillageName().filter(names::contains).isPresent()) {
                        out.add(npc);
                    }
                }
            }
            default -> { /* no general electorate */ }
        }
        return out;
    }
}
