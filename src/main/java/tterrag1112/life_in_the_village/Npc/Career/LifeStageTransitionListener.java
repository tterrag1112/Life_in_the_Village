package tterrag1112.life_in_the_village.Npc.Career;

import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * Phase 6.3.3.a — pure-observation listener for lifestage transitions.
 *
 * <p>Lifestages advance from aging, not from a discrete request — there
 * is nothing to veto. Listeners observe and can react (schedule events,
 * adjust state) but cannot prevent the transition.
 *
 * <p>Fires from {@link tterrag1112.life_in_the_village.Entities.custom.TownspersonMob#onLifeStageChanged}
 * in addition to the existing {@code NpcLifeEvent.LifeStageAdvanced}
 * event-bus signal — the facade is additive, the bus continues to drive
 * its existing listeners untouched.
 */
@FunctionalInterface
public interface LifeStageTransitionListener {
    void onLifeStageTransition(TownspersonMob npc, LifeStage from, LifeStage to);
}
