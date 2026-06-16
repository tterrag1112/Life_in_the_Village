package tterrag1112.life_in_the_village.Npc.Tasks.Monk;

import java.util.Set;

/**
 * G5a — the monk verb string used as {@link tterrag1112.life_in_the_village.Npc.Tasks.Objective.PerformService}
 * kind for monastic-craft tasks. Centralised here so the source, fulfillment,
 * and executor key on the same constant without string literals scattered across
 * files.
 *
 * <p>ONE verb covers all monastic crafts — the specific craft is selected
 * inside {@link MonkCraftExecutor} at execution time using the same
 * skill+amenity+shortfall priority the legacy
 * {@code MonkProductionBehavior.selectPlan} used.</p>
 *
 * <p>Disjoint from all existing PERFORM_SERVICE kinds:
 * {@code farm_harvest}, {@code farm_replant}, {@code farm_till},
 * {@code farm_compost}, {@code animal_tend}, {@code shear},
 * {@code collect_honey}, {@code officiate_rite}, {@code mine}.</p>
 */
public final class MonkVerb {

    private MonkVerb() {}

    /** Walk to the monastery and run one monastic production cycle. */
    public static final String MONASTIC_CRAFT = "monastic_craft";

    private static final Set<String> ALL = Set.of(MONASTIC_CRAFT);

    /** True when {@code kind} is any monk verb. */
    public static boolean isMonkVerb(String kind) {
        return ALL.contains(kind);
    }
}
