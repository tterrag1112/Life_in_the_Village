package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;

/**
 * BAN_EXECUTION: PunishmentSelector swaps EXECUTION → EXILE. Spec line
 * 159. Wired in doc 19. The flag is exposed via
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawCrimeHooks#banExecution}
 * so the punishment subsystem doesn't have to enumerate the law list.
 */
public final class BanExecutionEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.BAN_EXECUTION; }
}
