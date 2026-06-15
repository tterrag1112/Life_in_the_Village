package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import java.util.Set;

/**
 * G1 — the four crop-work verb strings used as {@code Objective.PerformService}
 * kinds for farm tasks. Centralised here so the source, executors, and
 * fulfillments all key on the same constants without string literals scattered
 * across four files.
 */
public final class FarmVerb {

    private FarmVerb() {}

    public static final String HARVEST = "farm_harvest";
    public static final String REPLANT = "farm_replant";
    public static final String TILL    = "farm_till";
    public static final String COMPOST = "farm_compost";

    private static final Set<String> ALL = Set.of(HARVEST, REPLANT, TILL, COMPOST);

    /** True when {@code kind} is one of the four farm-crop verb kinds. */
    public static boolean isFarmVerb(String kind) {
        return ALL.contains(kind);
    }
}
