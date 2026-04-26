package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

/** SUBSIDIZE_SCHOLAR: daily stipend for SCHOLAR / SCRIBE / LIBRARIAN NPCs. */
public final class SubsidizeScholarEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.SUBSIDIZE_SCHOLAR; }
    @Override public long subsidyForProfession(Village v, Profession p, LawParams params) {
        if (p != Profession.SCHOLAR && p != Profession.SCRIBE && p != Profession.LIBRARIAN) return 0L;
        return Math.max(0L, Math.round(params.numeric(VillageLaw.SUBSIDIZE_SCHOLAR, "subsidy_per_day")));
    }
}
