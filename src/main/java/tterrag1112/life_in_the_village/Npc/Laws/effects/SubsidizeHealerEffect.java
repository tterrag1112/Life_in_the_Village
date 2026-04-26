package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * SUBSIDIZE_HEALER: daily stipend for HEALER NPCs. The HEALER profession
 * lands in Phase 3 doc 21 — until then this effect's subsidy never
 * fires (no NPC has the profession). Spec line 110 listed it; the
 * registry slot stays so doc 21 only has to populate the profession.
 */
public final class SubsidizeHealerEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.SUBSIDIZE_HEALER; }
    @Override public long subsidyForProfession(Village v, Profession p, LawParams params) {
        // HEALER not in the v1 Profession enum; return 0 until doc 21
        // adds it. Documented in 22 Revision Notes.
        return 0L;
    }
}
