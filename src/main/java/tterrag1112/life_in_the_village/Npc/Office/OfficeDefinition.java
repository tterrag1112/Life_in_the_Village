package tterrag1112.life_in_the_village.Npc.Office;

import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.Map;

/**
 * Static metadata for an office. One instance per {@link #id} lives in
 * {@link OfficeRegistry} and is shared across every concrete
 * {@link OfficeHolding} of that office.
 *
 * <p>Definitions are not persisted — only the {@code id} on the holding
 * is. Loading an unknown id (because the definition was renamed or
 * removed) is logged and the holding becomes vacant.</p>
 *
 * <p>See {@code docs/npc_redesign/06-office-framework.md} for the v1
 * office slate and competence-curve table.</p>
 */
public record OfficeDefinition(
        String id,
        OrgType orgType,
        String displayName,
        List<Profession> eligibleProfessions,
        Map<Skill, Integer> minSkillReqs,
        SelectionMethod defaultSelection,
        int termDays,
        List<OfficePower> powers,
        Competence competence,
        List<String> ascensionPrereqs,
        int ascensionMinHoldDays
) {
    public OfficeDefinition {
        eligibleProfessions = List.copyOf(eligibleProfessions);
        minSkillReqs = Map.copyOf(minSkillReqs);
        powers = List.copyOf(powers);
        ascensionPrereqs = List.copyOf(ascensionPrereqs);
    }

    /** Convenience — empty ascension chain, no min-hold requirement. */
    public static OfficeDefinition of(String id,
                                      OrgType orgType,
                                      String displayName,
                                      List<Profession> eligibleProfessions,
                                      Map<Skill, Integer> minSkillReqs,
                                      SelectionMethod defaultSelection,
                                      int termDays,
                                      List<OfficePower> powers,
                                      Competence competence) {
        return new OfficeDefinition(id, orgType, displayName,
                eligibleProfessions, minSkillReqs, defaultSelection,
                termDays, powers, competence, List.of(), 0);
    }

    /** {@code true} when the office definition holds eligibility entries. */
    public boolean hasEligibleProfession(Profession profession) {
        return eligibleProfessions.contains(profession);
    }

    /** Indefinite term (term length = 0 days). */
    public boolean isIndefiniteTerm() {
        return termDays <= 0;
    }
}
