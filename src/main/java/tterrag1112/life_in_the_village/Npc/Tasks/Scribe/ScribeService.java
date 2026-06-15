package tterrag1112.life_in_the_village.Npc.Tasks.Scribe;

/**
 * T3 — shared constants for the SCRIBE Task-System migration.
 *
 * <p>A scribal commission is non-production work (no item flow with a fixed
 * quota), so it is modeled as an {@code Objective.PerformService(KIND, ref)}
 * where {@code ref} carries the {@code commissionId} (UUID string). The
 * {@link ScribeCommissionTaskSource} mirrors a workshop's {@code CommissionQueue}
 * onto the scribe's board; {@link ScribeWriteFulfillment} executes the
 * walk -&gt; write -&gt; deliver loop ported from the legacy {@code ScribeWorkBehavior}.</p>
 */
public final class ScribeService {

    private ScribeService() {}

    /** The {@code PerformService.kind} discriminator for a scribal commission. */
    public static final String KIND = "scribal_commission";

    /** Stable task-id prefix: {@code "commission:" + commissionId}. */
    public static final String ID_PREFIX = "commission:";

    /** Commission {@code priority} at/above which the task issues at HIGH tier. */
    public static final int HIGH_PRIORITY_THRESHOLD = 10;

    /** Divisor used to map commission {@code priority} -&gt; intra-tier urgency. */
    public static final float URGENCY_SCALE = 15.0f;
}
