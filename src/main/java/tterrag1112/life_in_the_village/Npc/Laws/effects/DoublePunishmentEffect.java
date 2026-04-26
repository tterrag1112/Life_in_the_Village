package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;

/**
 * DOUBLE_PUNISHMENT: PunishmentSelector reads
 * {@link LawEffect#law} at sentencing time and upgrades severity. Spec
 * line 159. Phase 3 doc 19 (next session) wires the actual punishment
 * type swap; the registry slot stays so doc 19 only has to call
 * {@link tterrag1112.life_in_the_village.Npc.Laws.LawCrimeHooks#severityModifier}.
 */
public final class DoublePunishmentEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.DOUBLE_PUNISHMENT; }
}
