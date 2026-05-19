package tterrag1112.life_in_the_village.Entities.Goals.Profession;

import net.minecraft.world.entity.ai.goal.Goal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-registration scan that makes {@link ProfessionGoalFactory}'s
 * documented "no two exclusive goals share a priority" promise real.
 *
 * <h3>Rule</h3>
 * A priority is <b>safe</b> when:
 * <ul>
 *   <li>It has &le;1 {@link Goal.Flag#MOVE} goal, <b>or</b></li>
 *   <li>Every MOVE goal at that priority is {@linkplain #GATED_BY_WINDOW
 *       window-gated} — its {@code canUse()} short-circuits to {@code false}
 *       outside a narrow declared window (target-present, meal phase,
 *       leisure phase, homeless &amp; off-work, caravan-active,
 *       crime-present, etc.).</li>
 * </ul>
 * Anything else WARN-logs: profession, priority, full goal list, and the
 * specific members that are not gated. Log-only via SLF4J; never throws.
 *
 * <h3>Accepted residual blind spot</h3>
 * Two gated goals whose windows actually overlap will not be flagged.
 * The shipped goal set is small and the gated windows are narrow by
 * construction, so the practical risk is low. The validator catches the
 * dominant failure mode — a broadly-active errand goal piled on top of a
 * narrowly-gated one at the same priority.
 *
 * <h3>Maintenance</h3>
 * Adding a class to {@link #GATED_BY_WINDOW} is deliberate: each entry's
 * comment must cite the {@code canUse} short-circuit it relies on. If
 * the goal's {@code canUse} ever loosens, remove it from the set —
 * a real WARN is preferable to a silent guardrail.
 */
public final class GoalPriorityValidator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GoalPriorityValidator.class);

    /**
     * Goal classes whose {@code canUse} is narrow by construction.
     * Citations point at the specific guard line that closes the goal
     * outside its window.
     */
    private static final Set<String> GATED_BY_WINDOW = Set.of(
            // Crime/INVESTIGATE_CRIME power held — ConstableInvestigationGoal.java:51
            "ConstableInvestigationGoal",
            // Externally seated `target` field — GreetPlayerGoal.java:81
            "GreetPlayerGoal",
            // VisitorState.isVisitor() — VisitorGoal.java:63
            "VisitorGoal",
            // ScheduleResolver.isMealTime narrow phase — EatMealGoal.canUse
            "EatMealGoal",
            // ELDERLY + master skill + isWorkTime + mentee target —
            // MentorGoal.java:52,57,60,66
            "MentorGoal",
            // DayPhase.LEISURE or dayOff — HobbyGoal.java:55,57
            "HobbyGoal",
            // SCRIBE + isSocialTime + has-letter — PostalGoal.java:57-58
            "PostalGoal",
            // FamilyRole.HEAD + adult + unmarried + eligible target —
            // CourtingGoal.java:33-35, target check at :44+
            "CourtingGoal",
            // isCaravanMember — CaravanGuardGoal.java:46
            "CaravanGuardGoal",
            // !hasHome && !isWorkTime — SeekHouseGoal canUse
            "SeekHouseGoal",
            // shouldBeHome (typically nightly) — ReturnHomeGoal.java:43
            "ReturnHomeGoal",
            // Vanilla: target != null — only set by HurtByTargetGoal /
            // GuardAttackGoal, civilians stay clean.
            "MeleeAttackGoal",
            // getRepaintJob() != null + maintenance not pending —
            // BuilderRepaintGoal.java:77-78, cross-check at :80-82
            "BuilderRepaintGoal"
            // NOTE: BuyGoodsGoal was previously listed here. Removed —
            // its canUse is broadly true during off-work hours whenever
            // household food/tool need is non-zero (the same shape as
            // SellToMarketGoal / BuyFromNpcGoal, which we treat as the
            // canonical NOT-gated examples).
    );

    /**
     * Toggle for {@link ProfessionGoalFactory}. Validator is log-only;
     * leaving it on by default exposes regressions immediately.
     */
    /**
     * Dedup: only log each {@code (profession, priority, signature)} once
     * per JVM session. Mass-spawn flows register the same goal set across
     * many NPCs; without dedup the log floods with identical entries.
     */
    private static final Set<String> ALREADY_LOGGED =
            ConcurrentHashMap.newKeySet();

    private GoalPriorityValidator() {}

    /**
     * Scan {@code npc.goalSelector} once and WARN-log any priority slot
     * whose MOVE-goal set violates the safety rule.
     *
     * @return number of warnings emitted (0 on a clean profession)
     */
    public static int validate(TownspersonMob npc) {
        Map<Integer, List<Goal>> byPriority = new HashMap<>();
        for (var wrapped : npc.goalSelector.getAvailableGoals()) {
            byPriority.computeIfAbsent(wrapped.getPriority(), k -> new ArrayList<>())
                    .add(wrapped.getGoal());
        }

        String profession = npc.getProfession() == null
                ? "NONE" : npc.getProfession().name();

        int warnings = 0;
        for (var entry : byPriority.entrySet()) {
            List<Goal> moveGoals = new ArrayList<>();
            for (Goal g : entry.getValue()) {
                if (g.getFlags().contains(Goal.Flag.MOVE)) moveGoals.add(g);
            }
            if (moveGoals.size() < 2) continue;

            List<String> ungated = new ArrayList<>();
            for (Goal g : moveGoals) {
                if (!GATED_BY_WINDOW.contains(g.getClass().getSimpleName())) {
                    ungated.add(g.getClass().getSimpleName());
                }
            }
            if (ungated.isEmpty()) continue;

            List<String> all = new ArrayList<>(moveGoals.size());
            for (Goal g : moveGoals) all.add(g.getClass().getSimpleName());

            warnings++;
            String key = profession + ":" + entry.getKey() + ":" + all;
            if (ALREADY_LOGGED.add(key)) {
                LOGGER.warn("[GoalPriorityValidator] {} @ priority {}: MOVE goals {}; "
                                + "non-gated: {}",
                        profession, entry.getKey(), all, ungated);
            }
        }
        return warnings;
    }
}
