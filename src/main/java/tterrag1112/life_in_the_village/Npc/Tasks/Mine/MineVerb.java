package tterrag1112.life_in_the_village.Npc.Tasks.Mine;

import java.util.Set;

/**
 * G5a — the miner verb string used as
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.Objective.PerformService}
 * kind for mining tasks. Centralised here so the source, fulfillment, and
 * executor key on the same constant without string literals scattered across
 * files.
 *
 * <p>Disjoint from all existing PERFORM_SERVICE kinds:
 * {@code farm_harvest}, {@code farm_replant}, {@code farm_till},
 * {@code farm_compost}, {@code animal_tend}, {@code shear},
 * {@code collect_honey}, {@code officiate_rite}, {@code monastic_craft}.</p>
 */
public final class MineVerb {

    private MineVerb() {}

    /** Walk to the mine, mine ore, and deposit yields into mine storage. */
    public static final String MINE = "mine";

    private static final Set<String> ALL = Set.of(MINE);

    /** True when {@code kind} is any mine verb. */
    public static boolean isMineVerb(String kind) {
        return ALL.contains(kind);
    }
}
