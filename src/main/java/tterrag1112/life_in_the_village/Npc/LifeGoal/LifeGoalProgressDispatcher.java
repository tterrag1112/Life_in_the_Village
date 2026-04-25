package tterrag1112.life_in_the_village.Npc.LifeGoal;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;

/**
 * Event-driven goal progress. Reacts to bus events that update progress
 * on a goal that already exists on an NPC's {@link LifeGoalSet} (e.g.
 * marriage marks {@code MARRY_TARGET} / {@code MARRY_ANY} as complete).
 * The daily evaluator then sees {@code progressCount &ge; targetCount}
 * and fires {@link NpcLifeEvent.GoalCompleted}.
 *
 * <p>Phase 1 task 07 wires the events that already exist on the bus
 * (Married, BirthInFamily). Other event-driven goal types
 * (BEFRIEND_TARGET → relationship cross +60, AUTHOR_BOOK → book
 * publish, etc.) will hook in as their producer events come online in
 * Phase 2+.</p>
 */
public final class LifeGoalProgressDispatcher implements EventDispatcher {

    @Override public String name() { return "life_goal_progress"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        switch (event) {
            case NpcLifeEvent.Married e -> {
                TownspersonMob npc = e.subject();
                LifeGoalSet set = npc.getLifeGoals();
                set.activeByType(LifeGoalType.MARRY_ANY).ifPresent(g ->
                        set.setProgress(g.goalId(), g.targetCount()));
                // MARRY_TARGET completes only if the spouse matches the goal target.
                set.activeByType(LifeGoalType.MARRY_TARGET).ifPresent(g -> {
                    if (g.targetParam() != null && !g.targetParam().isEmpty()
                            && g.targetParam().equals(e.spouseId().toString())) {
                        set.setProgress(g.goalId(), g.targetCount());
                    }
                });
            }
            case NpcLifeEvent.BirthInFamily e -> {
                TownspersonMob npc = e.subject();
                LifeGoalSet set = npc.getLifeGoals();
                set.activeByType(LifeGoalType.HAVE_CHILD).ifPresent(g ->
                        set.updateProgress(g.goalId(), 1));
            }
            // Every other event type: no-op. Spec event-progress rows for
            // MARRY_TARGET, AUTHOR_BOOK, BEFRIEND_TARGET, etc. will land
            // here as their Phase 2+ producer events come online.
            default -> { /* no-op */ }
        }
    }
}
