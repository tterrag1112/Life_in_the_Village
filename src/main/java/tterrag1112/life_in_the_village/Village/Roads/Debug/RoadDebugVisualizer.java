package tterrag1112.life_in_the_village.Village.Roads.Debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Events.TickSubsystem;

import java.util.*;

/**
 * Manages timed particle visualization sessions for the road graph debug commands.
 *
 * <h3>Lifecycle</h3>
 * Sessions are transient — not persisted across server restarts. Each session
 * expires after {@link #DURATION_TICKS} ticks (30 seconds). A player can have
 * multiple concurrent sessions (e.g. show_graph + show_maintenance).
 *
 * <h3>Thread safety</h3>
 * All methods run on the server tick thread. No synchronization needed.
 *
 * <h3>Registration</h3>
 * The singleton {@link #INSTANCE} is registered in
 * {@link tterrag1112.life_in_the_village.Events.TickSubsystemRegistry#registerDefaults()}.
 * Command handlers call {@link #addSession} directly; no server-thread marshalling is
 * needed since Brigadier command execution runs on the server thread.
 */
public class RoadDebugVisualizer implements TickSubsystem {

    /** Singleton registered once in TickSubsystemRegistry. */
    public static final RoadDebugVisualizer INSTANCE = new RoadDebugVisualizer();

    /** Default session duration: 30 seconds at 20 ticks/s. */
    public static final int DURATION_TICKS = 600;

    /** Default particle emit interval: emit 5 times per second. */
    public static final int DEFAULT_EMIT_INTERVAL = 4;

    // keyed by player UUID → list of active sessions for that player
    private final Map<UUID, List<VisualizationSession>> activeSessions = new HashMap<>();

    private RoadDebugVisualizer() {}

    // ── TickSubsystem ────────────────────────────────────────────────────────

    @Override public String name()  { return "road-debug-viz"; }
    /** Run every tick so particles emit smoothly. */
    @Override public int interval() { return 1; }
    /** Run after gameplay systems — visualization has no downstream dependencies. */
    @Override public int priority() { return 200; }

    @Override
    public void tick(TickSubsystem.TickContext ctx) {
        if (activeSessions.isEmpty()) return;

        long tick       = ctx.tick();
        ServerLevel level = ctx.level();

        activeSessions.entrySet().removeIf(entry -> {
            List<VisualizationSession> sessions = entry.getValue();

            // Drop expired sessions
            sessions.removeIf(s -> s.endTick() < tick);
            if (sessions.isEmpty()) return true;

            // Resolve player — drop entry if they've gone offline
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;

            for (VisualizationSession session : sessions) {
                for (ParticleEmission emission : session.emissions()) {
                    if (tick % emission.emitEveryNTicks() == 0) {
                        sendParticle(level, player, emission.particle(),
                                emission.pos().getX() + 0.5,
                                emission.pos().getY() + 0.5,
                                emission.pos().getZ() + 0.5);
                    }
                }
            }
            return false;
        });
    }

    // ── Session management ───────────────────────────────────────────────────

    /**
     * Adds a new visualization session for the given player. The session will
     * emit its particles until {@code currentTick + DURATION_TICKS}.
     */
    public void addSession(UUID playerId, long currentTick, List<ParticleEmission> emissions) {
        activeSessions
                .computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(new VisualizationSession(
                        playerId, currentTick, currentTick + DURATION_TICKS, emissions));
    }

    /** Cancels all active sessions for the given player. */
    public void clearSessions(UUID playerId) {
        activeSessions.remove(playerId);
    }

    // ── Particle helper (package-accessible so command class can use it) ─────

    /**
     * Sends one particle to a specific player. The unchecked cast from
     * {@code ParticleOptions} to the generic {@code T} is safe at runtime
     * because all stored particles are concrete {@link net.minecraft.core.particles.SimpleParticleType}s.
     */
    @SuppressWarnings("unchecked")
    static <T extends ParticleOptions> void sendParticle(
            ServerLevel level, ServerPlayer player, ParticleOptions particle,
            double x, double y, double z) {
        level.sendParticles(player, (T) particle, false,true, x, y, z, 1, 0.0, 0.0, 0.0, 0.1);
    }

    // ── Data records ─────────────────────────────────────────────────────────

    /**
     * A single particle position that should be re-emitted on a regular schedule.
     *
     * @param pos              world position at which to emit
     * @param particle         the particle type to spawn
     * @param emitEveryNTicks  emit once every N ticks (4 = 5/s; 20 = 1/s; 2 = 10/s)
     */
    public record ParticleEmission(
            BlockPos pos,
            ParticleOptions particle,
            int emitEveryNTicks
    ) {}

    /**
     * One active visualization run for one player.
     *
     * @param playerId  the player to whom particles are sent
     * @param startTick server tick when this session was created
     * @param endTick   server tick after which this session is dropped
     * @param emissions the list of positions+particles to emit each cycle
     */
    public record VisualizationSession(
            UUID playerId,
            long startTick,
            long endTick,
            List<ParticleEmission> emissions
    ) {}
}
