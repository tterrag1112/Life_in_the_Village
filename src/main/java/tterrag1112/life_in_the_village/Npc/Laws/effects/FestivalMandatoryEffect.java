package tterrag1112.life_in_the_village.Npc.Laws.effects;

import tterrag1112.life_in_the_village.Npc.Laws.LawEffect;
import tterrag1112.life_in_the_village.Npc.Laws.LawParams;
import tterrag1112.life_in_the_village.Npc.Laws.VillageLaw;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * FESTIVAL_MANDATORY: when a festival event runs, every adult is
 * forced into attendance. Spec line 174. The existing
 * {@code TownspersonMob.setEventOverride} infrastructure handles
 * per-NPC event override; the daily tick checks this flag and applies
 * the override to all loaded villagers when an event is announced
 * (wired via {@link
 * tterrag1112.life_in_the_village.Npc.Laws.LawScheduleHooks#festivalMandatory}).
 *
 * <p>Phase 3 just exposes the flag; the festival-attendance enforcer
 * itself lands with the Phase 5 events-expanded session (doc 32).</p>
 */
public final class FestivalMandatoryEffect implements LawEffect {
    @Override public VillageLaw law() { return VillageLaw.FESTIVAL_MANDATORY; }
    @Override public boolean festivalAttendanceMandatory(Village v, LawParams p) { return true; }
}
