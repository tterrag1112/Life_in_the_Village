package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;

/**
 * PILGRIM_WELCOME_BONUS: spec line 170. Phase 4 visitor flux (doc 29)
 * adds a reputation bonus to pilgrim visitors on entry. Phase 3 keeps
 * the registry slot; the visitor system queries the registry directly
 * when it lands.
 */
public final class PilgrimWelcomeBonusEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.PILGRIM_WELCOME_BONUS; }
}
