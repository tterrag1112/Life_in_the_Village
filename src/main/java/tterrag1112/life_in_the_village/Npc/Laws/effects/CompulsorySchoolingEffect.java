package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * COMPULSORY_SCHOOLING: forces children into a daily lesson. Spec line
 * 175. The Phase 2 task 15 child-arc system (not yet shipped) owns
 * the schooling phase; until then this effect just flips
 * {@link LawEffect#compelsChildSchooling} so {@link
 * tterrag1112.life_in_the_village.Npc.Laws.LawScheduleHooks#compulsorySchooling}
 * returns true and the child-arc system will pick it up automatically
 * when it lands. Documented in 22 Revision Notes.
 */
public final class CompulsorySchoolingEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.COMPULSORY_SCHOOLING; }
    @Override public boolean compelsChildSchooling(Village v, LawParams p) { return true; }
}
