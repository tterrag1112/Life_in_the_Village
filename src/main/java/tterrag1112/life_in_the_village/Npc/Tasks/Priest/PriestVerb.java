package tterrag1112.life_in_the_village.Npc.Tasks.Priest;

import java.util.Set;

/**
 * G4 — the priest verb string used as {@code Objective.PerformService} kind
 * for rite-officiation tasks. Centralised here so the source, executor, and
 * fulfillment key on the same constant without string literals scattered
 * across files.
 *
 * <p>ONE verb covers both calendar rites (MARRIAGE, FUNERAL, FEAST_DAY, …)
 * AND CONSECRATION — both flow through the same
 * {@code RiteExecutor.runImmediate} perform path.</p>
 */
public final class PriestVerb {

    private PriestVerb() {}

    /** Walk to a due rite's location and perform it via {@code RiteExecutor.runImmediate}. */
    public static final String OFFICIATE_RITE = "officiate_rite";

    private static final Set<String> ALL = Set.of(OFFICIATE_RITE);

    /** True when {@code kind} is any priest verb. */
    public static boolean isPriestVerb(String kind) {
        return ALL.contains(kind);
    }
}
