package tterrag1112.life_in_the_village.Npc.Religion;

/**
 * F1b sub-stage 2 — the <b>stance</b> between two religions. The interreligious
 * landscape's reactive layer: a relation between two faiths that, for the first
 * time, makes the world respond to <i>which faiths share gods</i>.
 *
 * <p><b>Baseline (this stage): only {@link #KINDRED} vs {@link #NEUTRAL}, derived
 * symmetrically from god overlap</b> — two religions are KINDRED when they share ≥1
 * god, else NEUTRAL (see {@link Relations#relation}). {@link #RIVAL} / {@link
 * #HERETICAL} are reserved for explicit per-world OVERRIDE (kingdom conflict,
 * dynamism schism/founding, the later condemned-gods layer); this stage auto-derives
 * neither and ships no writer for them — disjoint gods is NEUTRAL, not hostile.</p>
 *
 * <p>Consumers ({@link FaithReconciliation} drift, the kingdom religious-tension
 * calc) switch over all four arms, wiring the not-yet-fired RIVAL/HERETICAL branches
 * so they are ready the moment an override sets them.</p>
 */
public enum RelationStance {
    /** Shared ≥1 god — syncretism comes easily, conflict eases. */
    KINDRED,
    /** No shared god and no override — today's baseline behaviour. */
    NEUTRAL,
    /** Explicit per-world override (kingdom conflict / dynamism). Not auto-derived. */
    RIVAL,
    /** Explicit per-world override (a faith condemning another's god). Not auto-derived. */
    HERETICAL
}
