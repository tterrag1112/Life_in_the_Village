package tterrag1112.life_in_the_village.Npc.Religion;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeRank;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * R1d — the cosmetic clergy rank/title, derived from the priest's SOCIAL skill
 * via the shared {@link ApprenticeRank} ladder (no persistent rank field).
 *
 * <p>Single source of truth for the Initiate/Priest/Senior-Priest mapping that
 * {@code PriestBehavior} surfaces in activity text and the R9 profile/temple
 * screens surface read-only. Factored out in R9b to retire the duplication
 * flagged by the R9a simplification sweep.</p>
 */
public final class ClergyTitles {

    private ClergyTitles() {}

    /** The cosmetic title for an NPC's current SOCIAL rank. */
    public static String of(TownspersonMob npc) {
        return forSocialLevel(npc.getSkills().getLevel(Skill.SOCIAL));
    }

    /** The cosmetic title for a raw SOCIAL skill level. */
    public static String forSocialLevel(int social) {
        return switch (ApprenticeRank.fromSkillLevel(social)) {
            case APPRENTICE -> "Initiate";
            case JOURNEYMAN -> "Priest";
            case MASTER     -> "Senior Priest";
        };
    }
}
