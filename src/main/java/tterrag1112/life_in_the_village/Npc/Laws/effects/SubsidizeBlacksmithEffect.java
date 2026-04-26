package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

/** SUBSIDIZE_BLACKSMITH: daily stipend paid to BLACKSMITH NPCs. */
public final class SubsidizeBlacksmithEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.SUBSIDIZE_BLACKSMITH; }
    @Override public long subsidyForProfession(Village v, Profession p, LawParams params) {
        return p == Profession.BLACKSMITH
                ? Math.max(0L, Math.round(params.numeric(VillageLaw.SUBSIDIZE_BLACKSMITH, "subsidy_per_day")))
                : 0L;
    }
}
