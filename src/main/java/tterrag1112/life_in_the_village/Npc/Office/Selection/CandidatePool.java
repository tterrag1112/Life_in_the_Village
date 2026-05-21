package tterrag1112.life_in_the_village.Npc.Office.Selection;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Npc.Office.OfficeDefinition;
import tterrag1112.life_in_the_village.Npc.Office.OrgType;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the eligible-candidate pool for one selection run.
 *
 * <p>Eligibility check matches the spec ({@code 06-office-framework.md}
 * "Edge cases: No eligible NPC" + the offices' min-skill requirements):</p>
 * <ul>
 *   <li>NPC is loaded in the level (offline NPCs excluded; the daily tick
 *   re-runs elections so they get picked up later when relevant).</li>
 *   <li>Profession matches one of {@link OfficeDefinition#eligibleProfessions}
 *   when the list is non-empty. Empty list = "any profession allowed",
 *   matching the spec for {@code guild_master} and similar.</li>
 *   <li>Each {@link OfficeDefinition#minSkillReqs} entry is met.</li>
 *   <li>Adult life-stage. Children and elderly are filtered out — Phase 3
 *   spec lists ELDERLY-driven retirement separately as a vacate signal.</li>
 *   <li>For VILLAGE offices, the NPC is currently assigned to the village
 *   (matching {@code getAssignedVillageName} against the village name).</li>
 *   <li>For GUILD/COMPANY/KINGDOM offices, scoping is by org membership
 *   (guild members live in the guild's village; business workers list lives
 *   on the business; kingdom rulers chosen from any village in the kingdom).</li>
 * </ul>
 */
public final class CandidatePool {

    private CandidatePool() {}

    /** Builds the list of eligible candidates for the selection run. */
    public static List<OfficeCandidate> buildFor(OfficeSelectionContext ctx) {
        Set<UUID> scope = scopeUuids(ctx);
        // Scope-mode "EMPTY" means we couldn't resolve a scope (e.g., a
        // village whose name is missing). Return empty rather than walking
        // every NPC in the world.
        if (scope == null) return List.of();

        List<OfficeCandidate> candidates = new ArrayList<>();
        for (var entity : ctx.level().getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;
            if (!scope.isEmpty() && !scope.contains(npc.getUUID())) {
                if (!matchesByVillageName(ctx, npc)) continue;
            }
            if (!isEligible(npc, ctx.def())) continue;
            candidates.add(toCandidate(npc, ctx.def()));
        }
        return candidates;
    }

    /**
     * For VILLAGE / GUILD scopes the per-NPC walker uses the assigned-village
     * name as a coarse filter (cheap, no per-NPC saved-data lookup). For
     * other org types the {@link #scopeUuids} pre-filter is authoritative
     * and this returns false.
     */
    private static boolean matchesByVillageName(OfficeSelectionContext ctx, TownspersonMob npc) {
        if (ctx.orgType() != OrgType.VILLAGE && ctx.orgType() != OrgType.GUILD) return false;
        Optional<String> assigned = npc.getAssignedVillageName();
        if (assigned.isEmpty()) return false;
        return switch (ctx.orgType()) {
            case VILLAGE -> ctx.vdata().getVillageById(ctx.orgId())
                    .map(v -> v.getName().equals(assigned.get()))
                    .orElse(false);
            case GUILD -> ctx.vdata().getGuildById(ctx.orgId())
                    .flatMap(g -> ctx.vdata().getVillageById(g.villageId()))
                    .map(v -> v.getName().equals(assigned.get()))
                    .orElse(false);
            default -> false;
        };
    }

    /**
     * For COMPANY / KINGDOM the org's authoritative member list narrows the
     * pool down before the entity scan. For VILLAGE / GUILD an empty set is
     * returned and the walker falls back to {@link #matchesByVillageName}
     * because membership is name-based not UUID-listed.
     */
    private static Set<UUID> scopeUuids(OfficeSelectionContext ctx) {
        return switch (ctx.orgType()) {
            case VILLAGE, GUILD, TEMPLE -> Set.of();
            case COMPANY -> {
                BusinessSavedData cdata = BusinessSavedData.get(ctx.level());
                Business c = cdata.getAllBusinesses().stream()
                        .filter(x -> x.getBusinessId().equals(ctx.orgId()))
                        .findFirst().orElse(null);
                if (c == null) yield null;
                Set<UUID> out = new java.util.HashSet<>();
                c.getWorkers().forEach(w -> out.add(w.npcId()));
                if (c.getOwnerPlayerId() != null) out.add(c.getOwnerPlayerId()); // tolerated; player UUIDs survive isEligible's NPC filter
                yield out;
            }
            case KINGDOM -> {
                Kingdom k = ctx.vdata().getKingdomById(ctx.orgId()).orElse(null);
                if (k == null) yield null;
                // Kingdom-scoped: collect every NPC assigned to a village in
                // the kingdom by walking village names. Realistic in v1 (low
                // tens of villages per kingdom).
                Set<String> villageNames = k.getVillageIds().stream()
                        .map(ctx.vdata()::getVillageById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .map(Village::getName)
                        .collect(Collectors.toSet());
                Set<UUID> out = new java.util.HashSet<>();
                for (var entity : ctx.level().getEntities().getAll()) {
                    if (!(entity instanceof TownspersonMob npc)) continue;
                    if (npc.getAssignedVillageName().filter(villageNames::contains).isPresent()) {
                        out.add(npc.getUUID());
                    }
                }
                yield out;
            }
        };
    }

    /** Eligibility check: profession + min-skill requirements + life-stage. */
    public static boolean isEligible(TownspersonMob npc, OfficeDefinition def) {
        if (!npc.isAdult()) return false;
        Profession prof = npc.getProfession();
        if (!def.eligibleProfessions().isEmpty() && !def.eligibleProfessions().contains(prof)) {
            return false;
        }
        var skills = npc.getSkills();
        for (var entry : def.minSkillReqs().entrySet()) {
            if (skills.getLevel(entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }

    private static OfficeCandidate toCandidate(TownspersonMob npc, OfficeDefinition def) {
        Skill primary = def.competence().primarySkill();
        int primarySkillLevel = npc.getSkills().getLevel(primary);
        float ambition = npc.getTraitVector().get(TraitAxis.AMBITION);
        return new OfficeCandidate(npc, npc.getUUID(),
                primarySkillLevel, // baseline score; engines overwrite
                primarySkillLevel,
                primary, ambition, npc.getAge());
    }
}
