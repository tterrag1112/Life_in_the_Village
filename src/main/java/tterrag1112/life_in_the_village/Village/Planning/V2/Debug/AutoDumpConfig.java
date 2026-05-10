package tterrag1112.life_in_the_village.Village.Planning.V2.Debug;

/**
 * Track E1 follow-up — runtime toggle for auto-dump-on-spawn.
 *
 * <p>Default-on. The JVM property
 * {@code -Dlitv.debug.autoDumpLayouts=false} disables auto-dump at
 * launch; the runtime command
 * {@code /litv layout debug autodump <on|off|status>} toggles
 * without restart. The on-demand
 * {@code /litv layout debug dump <name>} command bypasses this
 * toggle and always runs.
 *
 * <p>LitV has no NeoForge ModConfigSpec for game-logic toggles
 * (only {@code ModConfiguration} for the AI assistant API key);
 * adding one isn't justified for a single dev-debug switch. The
 * JVM property + runtime command pair handles dev workflow
 * cleanly without infra investment. If more toggles arrive, a
 * proper ModConfigSpec can absorb this one without changing the
 * dump-call contract.
 */
public final class AutoDumpConfig {

    public static final String SYSTEM_PROPERTY = "litv.debug.autoDumpLayouts";

    private static volatile boolean enabled = readInitial();

    private AutoDumpConfig() {}

    private static boolean readInitial() {
        String raw = System.getProperty(SYSTEM_PROPERTY);
        if (raw == null) return true; // default-on per spec
        return Boolean.parseBoolean(raw);
    }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) { enabled = value; }
}
