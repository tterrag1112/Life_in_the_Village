package tterrag1112.life_in_the_village.Npc.LifeGoal;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Daily evaluator for active life goals. Polled goals (wallet,
 * skill, family) get fresh progress reads; deadline-bearing goals
 * are checked for expiry; goals that meet their target fire
 * {@link NpcLifeEvent.GoalCompleted} on the bus.
 *
 * <p>Spec: {@code docs/npc_redesign/07-life-goals.md} (section
 * "Progress tracking"). Several goal types in the spec table need
 * subsystems that don't exist in Phase 1 (relationship ledger,
 * apprenticeship, book publish, visit-tracking) — those goals stay
 * at progressCount 0 here and will move forward when their producers
 * arrive in Phase 2+.</p>
 *
 * <p>The evaluator is invoked once per loaded NPC per in-game day from
 * the existing {@code npc_daily_decay} subsystem; called inline.</p>
 */
public final class LifeGoalEvaluator {

    private LifeGoalEvaluator() {}

    public static void runDaily(TownspersonMob npc, long currentTick) {
        if (npc == null) return;
        LifeGoalSet set = npc.getLifeGoals();
        if (set.activeCount() == 0) {
            // Refill if completely empty (spec: every 30 days, refill if
            // active.size() < 2). Cheap to attempt every day; selector
            // bails out if no eligible goal exists.
            LifeGoalSelector.refill(npc, 1, currentTick);
            return;
        }

        // Snapshot the active list — completion / failure mutates it.
        List<LifeGoal> snapshot = new ArrayList<>(set.active());
        for (LifeGoal goal : snapshot) {
            evaluateOne(npc, set, goal, currentTick);
        }

        // Stale history eviction (TTL = 90 days per spec).
        set.evictStaleHistory(currentTick);
    }

    private static void evaluateOne(TownspersonMob npc, LifeGoalSet set,
                                    LifeGoal goal, long currentTick) {
        // 1. Deadline failure.
        if (goal.isExpired(currentTick)) {
            set.fail(goal.goalId(), currentTick).ifPresent(failed ->
                    NpcLifeEventBus.fire(new NpcLifeEvent.GoalFailed(
                            npc, failed, "deadline expired")));
            return;
        }

        // 2. Progress poll for goals that don't have producer events yet.
        int polled = pollProgress(npc, goal);
        if (polled >= 0 && polled != goal.progressCount()) {
            set.setProgress(goal.goalId(), polled);
            goal = set.getById(goal.goalId()).orElse(goal);
        }

        // 3. Completion check: progressCount has reached targetCount.
        if (goal.progressCount() >= goal.targetCount()) {
            UUID id = goal.goalId();
            set.complete(id, currentTick).ifPresent(completed ->
                    NpcLifeEventBus.fire(new NpcLifeEvent.GoalCompleted(npc, completed)));
        }
    }

    /**
     * Returns the polled progress for a goal type the evaluator can
     * inspect directly. {@code -1} means "no poll path; leave existing
     * progress alone (events will update it)".
     */
    private static int pollProgress(TownspersonMob npc, LifeGoal goal) {
        return switch (goal.type()) {
            case SAVE_AMOUNT -> {
                long bronze = npc.getEconomy().getWallet().toBronze();
                yield (int) Math.min(Integer.MAX_VALUE, bronze);
            }
            case REACH_SKILL_LEVEL -> {
                Skill skill = parseSkillParam(goal.targetParam());
                yield skill == null ? -1 : npc.getSkills().getLevel(skill);
            }
            case HAVE_CHILD ->
                    npc.getFamily().getChildrenIds().size();
            case OUTLIVE_RIVAL ->
                    // Resolved when the rival's death event fires; until
                    // then, progress stays at 0. No poll source available
                    // without a global entity scan, which is too expensive
                    // for a daily pass.
                    -1;
            case WIN_OFFICE -> {
                // Phase 1 cross-org office walker check. If the NPC holds
                // any office, treat the goal as satisfied (specific-office
                // matching arrives in Phase 3 with selection-method logic).
                if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel sl)) yield -1;
                int held = tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry
                        .findOfficesHeldBy(npc.getUUID(), sl).size();
                yield held > 0 ? 1 : 0;
            }
            default -> -1;
        };
    }

    private static Skill parseSkillParam(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Skill.valueOf(s.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }
}
