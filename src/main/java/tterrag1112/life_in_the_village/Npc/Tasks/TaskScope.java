package tterrag1112.life_in_the_village.Npc.Tasks;

/**
 * T2 — the scope a {@link DoTaskBehavior} instance serves. A single
 * dispatch loop is parameterised by this so the same machinery can drive
 * profession work (in the WORK activity) and household chores (in the IDLE
 * activity) without each picking up the other's tasks.
 *
 * <p>The scope controls two things in the dispatcher:
 * <ul>
 *   <li><b>The eligibility gate</b> — whether this NPC's work is owned by
 *       the Task System for this scope. WORK_PROFESSION gates on
 *       {@link TaskMigration#isMigrated}; HOUSEHOLD gates on
 *       {@link TaskMigration#ownsHousehold}.</li>
 *   <li><b>The membership filter</b> — which of the NPC's
 *       {@link TaskContext#memberships() boards} it scans. WORK_PROFESSION
 *       scans every membership EXCEPT the HOUSEHOLD board (so the
 *       producer's BUSINESS / personal NPC boards only); HOUSEHOLD scans
 *       ONLY the HOUSEHOLD board. This is the scope-isolation requirement:
 *       neither dispatcher grabs the other's tasks even though both share
 *       the NPC's full membership set.</li>
 * </ul>
 *
 * <p>The default {@link DoTaskBehavior} constructor selects
 * {@code WORK_PROFESSION} so the existing WORK wiring is byte-identical.</p>
 */
public enum TaskScope {
    /** Profession production work; runs in the WORK activity. Scans all
     *  boards except HOUSEHOLD. */
    WORK_PROFESSION,
    /** Household chores (food upkeep); runs in the IDLE activity. Scans
     *  only the HOUSEHOLD board. */
    HOUSEHOLD;

    /** Whether {@code ref} is in this scope's board set. */
    public boolean includes(IssuerRef ref) {
        return switch (this) {
            case WORK_PROFESSION -> ref.level() != LevelKind.HOUSEHOLD;
            case HOUSEHOLD       -> ref.level() == LevelKind.HOUSEHOLD;
        };
    }
}
