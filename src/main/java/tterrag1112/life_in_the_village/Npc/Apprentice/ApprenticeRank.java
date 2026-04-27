package tterrag1112.life_in_the_village.Npc.Apprentice;

import com.mojang.serialization.Codec;

/**
 * Career-rank ladder for {@link tterrag1112.life_in_the_village.Profession.Profession}-based
 * crafts. Spec line 21.
 *
 * <p>Derived from the relevant primary skill via
 * {@link #fromSkillLevel(int)}. APPRENTICE 0..40, JOURNEYMAN 40..75,
 * MASTER 75+.</p>
 */
public enum ApprenticeRank {
    APPRENTICE,
    JOURNEYMAN,
    MASTER;

    public static ApprenticeRank fromSkillLevel(int skill) {
        if (skill >= 75) return MASTER;
        if (skill >= 40) return JOURNEYMAN;
        return APPRENTICE;
    }

    public static final Codec<ApprenticeRank> CODEC =
            Codec.STRING.xmap(ApprenticeRank::valueOf, ApprenticeRank::name);
}
