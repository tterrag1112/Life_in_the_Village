package tterrag1112.life_in_the_village.Npc.Tasks;

/**
 * Master feature flag for the Task System. Default <b>OFF</b>: with the
 * flag off, {@link DoTaskBehavior} is never added to the NPC brain, so
 * the brain is byte-identical to pre-Task-System main and there is zero
 * in-game change.
 *
 * <p>Mirrors the {@code AutoDumpConfig} pattern (LitV has no
 * ModConfigSpec for game-logic toggles): a JVM property
 * {@code -Dlitv.tasks.enabled=true} enables it at launch, and the static
 * setter allows a future runtime command to toggle. Because the brain is
 * built at entity construction, toggling at runtime only affects NPCs
 * spawned afterward — acceptable for a dev/migration gate.</p>
 */
public final class TaskSystemConfig {

    public static final String SYSTEM_PROPERTY = "litv.tasks.enabled";

    /** Default false — see class doc. */
    public static volatile boolean ENABLED = readInitial();

    private TaskSystemConfig() {}

    private static boolean readInitial() {
        String raw = System.getProperty(SYSTEM_PROPERTY);
        if (raw == null) return false;
        return Boolean.parseBoolean(raw);
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void setEnabled(boolean value) {
        ENABLED = value;
    }
}
