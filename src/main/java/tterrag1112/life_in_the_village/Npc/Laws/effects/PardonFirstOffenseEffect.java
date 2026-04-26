package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;

/**
 * PARDON_FIRST_OFFENSE: PunishmentSelector downgrades first minor
 * offences. Spec line 159. Wired in doc 19 (next session) via
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawCrimeHooks#pardonFirstOffense}.
 */
public final class PardonFirstOffenseEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.PARDON_FIRST_OFFENSE; }
}
