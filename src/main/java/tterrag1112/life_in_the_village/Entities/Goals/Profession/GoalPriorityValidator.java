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

/**
 * Post-registration scan that makes {@link ProfessionGoalFactory}'s
 * documented "no two exclusive goals share a priority" promise real.
 *
 * <p>After all goals are registered for an NPC, we group
 * {@link net.minecraft.world.entity.ai.goal.WrappedGoal} entries by
 * priority. For any priority with two or more goals where at least
 * two carry {@link Goal.Flag#MOVE}, we log a WARN naming the profession,
 * the priority, and the conflicting goal class names. Log-only — never
 * throws, so a live server stays up even if a future profession adds
 * an unforeseen conflict.</p>
 *
 * <h3>Allowlist</h3>
 * Some same-priority MOVE goals are intentionally benign because their
 * {@code canUse} only returns {@code true} in a narrow window that
 * doesn't overlap the other contenders at the same priority. Those goal
 * classes appear in {@link #BENIGN_BY_GATE} and are excluded from
 * conflict counting. Adding to this list is deliberate; never widen it
 * silently.
 */
public final class GoalPriorityValidator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GoalPriorityValidator.class);

    /**
     * Goal classes whose {@code canUse} short-circuits except in a
     * narrow, mutually-exclusive window. They count toward the
     * priority slot but never against a conflict warning.
     *
     * <p>Justifications (must match a corresponding canUse comment):</p>
     * <ul>
     *   <li>{@code ConstableInvestigationGoal} — only active when the
     *       NPC currently holds the INVESTIGATE_CRIME power.</li>
     *   <li>{@code GreetPlayerGoal} — only active when seated by
     *       {@code GreeterAssignment}.</li>
     *   <li>{@code VisitorGoal} — only active for NPCs flagged as a
     *       visitor by {@code VisitorFluxEngine}.</li>
     *   <li>{@code EatMealGoal} — only active during the MEAL phase
     *       window.</li>
     *   <li>{@code MentorGoal} — only active for elderly NPCs with a
     *       master-tier primary skill and a co-located mentee.</li>
     *   <li>{@code HobbyGoal} — only active during LEISURE phase with
     *       a resolvable hobby location.</li>
     *   <li>{@code BuyGoodsGoal} — only active when the NPC has an
     *       unmet shopping list and money.</li>
     *   <li>{@code CourtingGoal} — only active for unmarried adults
     *       with an eligible nearby target.</li>
     *   <li>{@code PostalGoal} — only active for scribes with an
     *       outstanding letter to deliver.</li>
     *   <li>{@code CaravanGuardGoal} — only active for guards
     *       assigned to a moving caravan.</li>
     * </ul>
     */
    private static final Set<String> BENIGN_BY_GATE = Set.of(
            "ConstableInvestigationGoal",
            "GreetPlayerGoal",
            "VisitorGoal",
            "EatMealGoal",
            "MentorGoal",
            "HobbyGoal",
            "BuyGoodsGoal",
            "CourtingGoal",
            "PostalGoal",
            "CaravanGuardGoal",
            // P_PATHFIND: gated by !hasHome && !isWorkTime so never
            // contends with the leader/guard work goals.
            "SeekHouseGoal",
            // P_COMBAT: gated by entity.shouldBeHome().
            "ReturnHomeGoal",
            // P_COMBAT (vanilla): gated by entity.getTarget() != null.
            "MeleeAttackGoal",
            // P_WORK_SECONDARY: defers to BuilderMaintenanceGoal via
            // BuilderMaintenanceGoal.findPendingRepair() cross-check.
            "BuilderRepaintGoal"
    );

    private GoalPriorityValidator() {}

    /**
     * Scan {@code npc.goalSelector} for unallowed same-priority MOVE
     * conflicts and WARN-log any found. Returns the number of
     * conflicts logged.
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
            List<Goal> contenders = new ArrayList<>();
            for (Goal g : entry.getValue()) {
                if (!g.getFlags().contains(Goal.Flag.MOVE)) continue;
                if (BENIGN_BY_GATE.contains(g.getClass().getSimpleName())) continue;
                contenders.add(g);
            }
            if (contenders.size() < 2) continue;

            List<String> names = new ArrayList<>(contenders.size());
            for (Goal g : contenders) names.add(g.getClass().getSimpleName());

            warnings++;
            LOGGER.warn("[GoalPriorityValidator] {} @ priority {}: conflicting MOVE goals {}",
                    profession, entry.getKey(), names);
        }
        return warnings;
    }
}
