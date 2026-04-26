package tterrag1112.life_in_the_village.Profession;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * Phase 2 task 17 — minimum-skill gates per profession at hiring.
 *
 * <p>Spec lines 286-291:</p>
 * <ul>
 *   <li>SCRIBE: LITERACY ≥ 60</li>
 *   <li>LIBRARIAN: LITERACY ≥ 50</li>
 *   <li>SCHOLAR: LITERACY ≥ 80</li>
 * </ul>
 *
 * <p>Used by player-driven hire / re-profession paths to reject NPCs
 * who can't meet the bar. The bootstrap populator
 * ({@code VillageInhabitantPopulator}) consults
 * {@link #ensureLiteracyForBootstrap} so newly-spawned scribal NPCs
 * receive a starting LITERACY top-up — otherwise the spec's gate
 * would be tripped on its own villagers.</p>
 */
public final class ProfessionRequirements {

    private ProfessionRequirements() {}

    public static int literacyRequired(Profession profession) {
        return switch (profession) {
            case SCRIBE     -> 60;
            case LIBRARIAN  -> 50;
            case SCHOLAR    -> 80;
            default         -> 0;
        };
    }

    /** True when {@code npc} meets the LITERACY gate for {@code target}. */
    public static boolean meets(TownspersonMob npc, Profession target) {
        int needed = literacyRequired(target);
        if (needed <= 0) return true;
        return npc.getSkills().getLevel(Skill.LITERACY) >= needed;
    }

    /**
     * Bootstrap helper: bumps the NPC's LITERACY skill to the
     * profession's hiring threshold, no-op for non-scribal callers.
     * Called from the populator after
     * {@link tterrag1112.life_in_the_village.Npc.Skills.SkillComponent#initializeFromProfession}
     * so spawn-init's [15, 35] primary range doesn't undercut the
     * 50/60/80 minimum.
     */
    public static void ensureLiteracyForBootstrap(TownspersonMob npc,
                                                  Profession profession,
                                                  long currentTick) {
        int target = literacyRequired(profession);
        if (target <= 0) return;
        var skills = npc.getSkills();
        if (skills.getLevel(Skill.LITERACY) >= target) return;
        skills.setLevel(Skill.LITERACY, target, currentTick);
    }
}
