package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * SUBSIDIZE_FARMER: adds a daily subsidy to FARMER / FARMHAND wages,
 * paid out of the village treasury via {@link
 * tterrag1112.life_in_the_village.Npc.Laws.LawTaxHooks#dailySubsidyForProfession}.
 * Spec line 110: "food-deficit + Compassionate leader → SUBSIDIZE_FARMER".
 *
 * <p>Treasury overdraft (spec "Edge cases" + "Things to flag" #2): the
 * tax hook auto-suspends rather than repeals when the treasury can't
 * cover all subsidies on a tick. Suspended laws resume once funds
 * recover; documented on {@link
 * tterrag1112.life_in_the_village.Npc.Laws.VillagePolicy#suspend}.</p>
 */
public final class SubsidizeFarmerEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.SUBSIDIZE_FARMER; }
    @Override public long subsidyForProfession(Village v, Profession p, LawParams params) {
        if (p != Profession.FARMER) return 0L;
        return Math.max(0L, Math.round(params.numeric(VillageLaw.SUBSIDIZE_FARMER, "subsidy_per_day")));
    }
}
