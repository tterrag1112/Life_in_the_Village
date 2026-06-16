package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import java.util.Set;

/**
 * G1/G2 — the farm verb strings used as {@code Objective.PerformService}
 * kinds for farm tasks. Centralised here so the source, executors, and
 * fulfillments all key on the same constants without string literals scattered
 * across files.
 */
public final class FarmVerb {

    private FarmVerb() {}

    // ── Crop verbs (G1) ───────────────────────────────────────────────────────
    public static final String HARVEST    = "farm_harvest";
    public static final String REPLANT    = "farm_replant";
    public static final String TILL       = "farm_till";
    public static final String COMPOST    = "farm_compost";

    // ── Animal verb (G2) ─────────────────────────────────────────────────────
    /** Generic animal-tending (pasture rotation, ANIMAL_HUSBANDRY XP,
     *  passive disease recovery). One task per farmhouse. */
    public static final String ANIMAL_TEND = "animal_tend";

    private static final Set<String> ALL = Set.of(
            HARVEST, REPLANT, TILL, COMPOST, ANIMAL_TEND);

    /** True when {@code kind} is any farm verb (crop or animal). */
    public static boolean isFarmVerb(String kind) {
        return ALL.contains(kind);
    }

    /** True when {@code kind} is one of the four crop verbs (NOT animal_tend). */
    public static boolean isCropVerb(String kind) {
        return kind.equals(HARVEST) || kind.equals(REPLANT)
                || kind.equals(TILL) || kind.equals(COMPOST);
    }
}
