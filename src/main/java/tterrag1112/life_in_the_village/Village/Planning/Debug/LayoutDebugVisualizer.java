package tterrag1112.life_in_the_village.Village.Planning.Debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Events.TickSubsystem;

import java.util.*;

/**
 * Manages timed particle visualization sessions for the village-internal
 * RoadGraph debug commands.
 *
 * <h3>Lifecycle</h3>
 * Sessions are transient — not persisted across server restarts. Each session
 * expires after {@link #DURATION_TICKS} ticks (30 seconds). A player can have
 * multiple concurrent sessions.
 *
 * <h3>Registration</h3>
 * The singleton {@link #INSTANCE} is registered in
 * {@link tterrag1112.life_in_the_village.Events.TickSubsystemRegistry#registerDefaults()}.
 */
public class LayoutDebugVisualizer implements TickSubsystem {

    /** Singleton registered once in TickSubsystemRegistry. */
    public static final LayoutDebugVisualizer INSTANCE = new LayoutDebugVisualizer();

    /** Default session duration: 30 seconds at 20 ticks/s. */
    public static final int DURATION_TICKS = 600;

    /** Default particle emit interval: emit 5 times per second. */
    public static final int DEFAULT_EMIT_INTERVAL = 4;

    private final Map<UUID, List<VisualizationSession>> activeSessions = new HashMap<>();

    private LayoutDebugVisualizer() {}

    // ── TickSubsystem ────────────────────────────────────────────────────────

    @Override public String name()  { return "layout-debug-viz"; }
    @Override public int interval() { return 1; }
    @Override public int priority() { return 200; }

    @Override
    public void tick(TickSubsystem.TickContext ctx) {
        if (activeSessions.isEmpty()) return;

        long tick         = ctx.tick();
        ServerLevel level = ctx.level();

        activeSessions.entrySet().removeIf(entry -> {
            List<VisualizationSession> sessions = entry.getValue();

            sessions.removeIf(s -> s.endTick() < tick);
            if (sessions.isEmpty()) return true;

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

    public void addSession(UUID playerId, long currentTick, List<ParticleEmission> emissions) {
        activeSessions
                .computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(new VisualizationSession(
                        playerId, currentTick, currentTick + DURATION_TICKS, emissions));
    }

    public void clearSessions(UUID playerId) {
        activeSessions.remove(playerId);
    }

    // ── Particle helper ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static <T extends ParticleOptions> void sendParticle(
            ServerLevel level, ServerPlayer player, ParticleOptions particle,
            double x, double y, double z) {
        level.sendParticles(player, (T) particle, false, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.1);
    }

    // ── Data records ─────────────────────────────────────────────────────────

    public record ParticleEmission(
            BlockPos pos,
            ParticleOptions particle,
            int emitEveryNTicks
    ) {}

    public record VisualizationSession(
            UUID playerId,
            long startTick,
            long endTick,
            List<ParticleEmission> emissions
    ) {}
}
