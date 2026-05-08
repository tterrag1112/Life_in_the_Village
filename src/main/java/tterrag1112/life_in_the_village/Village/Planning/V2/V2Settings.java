package tterrag1112.life_in_the_village.Village.Planning.V2;

/**
 * Runtime-toggleable feature flags for the V2 adaptive village
 * planner. Track A1a wires {@code VillageSpawner.spawnVillage} to
 * route through {@code V2VillageSpawnerAdapter} when {@link
 * #adaptiveV2Enabled()} is true.
 *
 * <p>Default: {@code false}. Toggled at runtime via
 * {@code /litv config adaptive_v2 <on|off>}.
 *
 * <p>Volatile so a server-thread spawn read sees the latest write
 * from the command thread without a lock.
 */
public final class V2Settings {

    private static volatile boolean adaptiveV2 = false;

    private V2Settings() {}

    public static boolean adaptiveV2Enabled() {
        return adaptiveV2;
    }

    public static void setAdaptiveV2(boolean value) {
        adaptiveV2 = value;
    }
}
