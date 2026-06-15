package tterrag1112.life_in_the_village.Npc.Tasks;

import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.EnumSet;
import java.util.Set;

/**
 * The per-profession migration gate. A profession in this set drives its
 * work through the Task System; every other profession keeps its legacy
 * production behavior untouched.
 *
 * <p>Two consumers:
 * <ul>
 *   <li>{@link DoTaskBehavior#checkExtraStartConditions} proceeds only
 *       for a migrated profession (else it declines so the legacy WORK@0
 *       behavior runs).</li>
 *   <li>The legacy production behavior of a migrated profession yields
 *       when {@code isMigrated(profession)}, falling through from WORK@0
 *       to {@link DoTaskBehavior} at WORK@1.</li>
 * </ul>
 *
 * <p>There is no master feature flag: the migration set is the sole gate.
 * Professions in the set are task-driven on every load without any
 * flag/respawn required; professions not in the set run legacy behavior
 * exactly as before.</p>
 */
public final class TaskMigration {

    private TaskMigration() {}

    private static final Set<Profession> MIGRATED =
            EnumSet.of(Profession.BLACKSMITH,
                       Profession.CANDLEMAKER,
                       Profession.WEAVER,
                       Profession.CARPENTER,
                       Profession.STONEMASON,
                       Profession.SCRIBE,
                       Profession.BAKER,
                       Profession.MILLER,
                       Profession.FARMER);

    /** True if {@code profession} drives work through the Task System. */
    public static boolean isMigrated(Profession profession) {
        return profession != null && MIGRATED.contains(profession);
    }

    /**
     * True if the Task System should own {@code profession}'s work: the
     * profession is in the migrated set. The single predicate both the
     * dispatcher gate and the legacy-yield gate use. No flag involved.
     */
    public static boolean ownsWork(Profession profession) {
        return isMigrated(profession);
    }

    /**
     * T2 -- whether the Task System owns HOUSEHOLD-scope chores (food upkeep).
     * Household migration is non-profession: every household is always
     * task-migrated. Always returns {@code true}. The two consumers mirror
     * the profession path:
     * <ul>
     *   <li>the HOUSEHOLD-scope {@code DoTaskBehavior} (IDLE) gates on this;</li>
     *   <li>{@code HomeProductionBehavior.selectPlan} yields when this is true,
     *       so the legacy IDLE baking stands down for the household dispatcher.</li>
     * </ul>
     */
    public static boolean ownsHousehold() {
        return true;
    }

    /** Snapshot of the migrated set (defensive copy). */
    public static Set<Profession> migrated() {
        return EnumSet.copyOf(MIGRATED);
    }
}
