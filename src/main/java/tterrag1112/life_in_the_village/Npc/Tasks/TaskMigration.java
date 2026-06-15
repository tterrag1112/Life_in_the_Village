package tterrag1112.life_in_the_village.Npc.Tasks;

import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.EnumSet;
import java.util.Set;

/**
 * T1 — the one-profession-at-a-time migration gate. A profession in this
 * set drives its work through the Task System (when
 * {@link TaskSystemConfig#ENABLED}); every other profession keeps its
 * legacy production behavior untouched.
 *
 * <p>Two consumers:
 * <ul>
 *   <li>{@link DoTaskBehavior#checkExtraStartConditions} proceeds only
 *       for a migrated profession (else it declines so the legacy WORK@0
 *       behavior runs).</li>
 *   <li>The legacy production behavior of a migrated profession yields
 *       when {@code ENABLED && isMigrated(profession)}, falling through
 *       from WORK@0 to {@link DoTaskBehavior} at WORK@1.</li>
 * </ul>
 *
 * <p>T2 sweeps CANDLEMAKER, WEAVER, CARPENTER, and STONEMASON onto the
 * Task System alongside the existing BLACKSMITH. The set is intentionally
 * NOT flag-gated itself — the {@link TaskSystemConfig#ENABLED} flag is the
 * master switch; this set is the per-profession selector consulted only when
 * the flag is on.</p>
 */
public final class TaskMigration {

    private TaskMigration() {}

    private static final Set<Profession> MIGRATED =
            EnumSet.of(Profession.BLACKSMITH,
                       Profession.CANDLEMAKER,
                       Profession.WEAVER,
                       Profession.CARPENTER,
                       Profession.STONEMASON);

    /** True if {@code profession} drives work through the Task System. */
    public static boolean isMigrated(Profession profession) {
        return profession != null && MIGRATED.contains(profession);
    }

    /** True if the Task System should own {@code profession}'s work right
     *  now: master flag on AND the profession is migrated. The single
     *  predicate both the dispatcher gate and the legacy-yield gate use. */
    public static boolean ownsWork(Profession profession) {
        return TaskSystemConfig.ENABLED && isMigrated(profession);
    }

    /** Snapshot of the migrated set (defensive copy). */
    public static Set<Profession> migrated() {
        return EnumSet.copyOf(MIGRATED);
    }
}
