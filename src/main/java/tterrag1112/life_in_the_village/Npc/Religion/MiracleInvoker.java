package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Divine Layer V2 — the single miracle <b>invocation path</b>: gate (tier +
 * favour + cooldown) → {@link DivineFavour#spend} → apply the effect. Player-primary.
 * The favour spend is the miracle's only cost (piety is never touched). The
 * per-player, per-miracle cooldown lives in a small in-memory map (no brain memory,
 * no persistence needed — a short anti-spam timer; resetting on restart is fine).
 */
public final class MiracleInvoker {

    private MiracleInvoker() {}

    /** playerId → (miracleId → tick the cooldown expires). */
    private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();

    /** A miracle's current availability for a player (for surfacing + denial). */
    public enum Status { AVAILABLE, LOCKED_TIER, LOCKED_FAVOUR, ON_COOLDOWN }

    public record Result(boolean success, String message) {}

    /** Resolves a miracle's status for {@code player} right now. */
    public static Status status(ServerLevel level, ServerPlayer player, Miracle m, long now) {
        UUID pid = player.getUUID();
        if (cooldownRemaining(pid, m.id(), now) > 0) return Status.ON_COOLDOWN;
        PietyTier tier = DivineFavour.tierIn(level, pid, m.religionId());
        if (tier.ordinal() < m.minTier().ordinal()) return Status.LOCKED_TIER;
        float favour = DivineFavour.current(level, pid, m.religionId(), now);
        if (favour < m.minFavour() || favour < m.cost()) return Status.LOCKED_FAVOUR;
        return Status.AVAILABLE;
    }

    /**
     * Invokes {@code miracleId} for {@code player}: gate → spend → apply → arm the
     * cooldown. Returns a {@link Result} with a clear message either way.
     */
    public static Result cast(ServerLevel level, ServerPlayer player, String miracleId, long now) {
        Miracle m = Miracles.byId(miracleId);
        if (m == null) return new Result(false, "No such miracle: " + miracleId);
        UUID pid = player.getUUID();

        long cd = cooldownRemaining(pid, m.id(), now);
        if (cd > 0) {
            return new Result(false, m.displayName() + " is still gathering ("
                    + (cd / 20) + "s).");
        }
        PietyTier tier = DivineFavour.tierIn(level, pid, m.religionId());
        if (tier.ordinal() < m.minTier().ordinal()) {
            return new Result(false, m.displayName() + " requires deeper devotion ("
                    + m.minTier().displayName() + ").");
        }
        float favour = DivineFavour.current(level, pid, m.religionId(), now);
        if (favour < m.minFavour() || favour < m.cost()) {
            return new Result(false, "Not enough favour for " + m.displayName()
                    + " (need " + Math.round(Math.max(m.minFavour(), m.cost()))
                    + ", have " + Math.round(favour) + ").");
        }

        // Spend the favour (the only cost). Defensive: bail if the spend fails.
        if (!DivineFavour.spend(level, pid, m.religionId(), m.cost(), now)) {
            return new Result(false, "The deity withholds its favour.");
        }
        // Apply the favour-scaled effect, then arm the cooldown.
        m.effect().apply(level, player, favour);
        arm(pid, m.id(), now + m.cooldownTicks());
        return new Result(true, m.displayName() + " — granted.");
    }

    // ── Cooldown bookkeeping ─────────────────────────────────────────────────

    public static long cooldownRemaining(UUID playerId, String miracleId, long now) {
        Long until = COOLDOWNS.getOrDefault(playerId, Map.of()).get(miracleId);
        return until == null ? 0L : Math.max(0L, until - now);
    }

    private static void arm(UUID playerId, String miracleId, long untilTick) {
        COOLDOWNS.computeIfAbsent(playerId, k -> new HashMap<>()).put(miracleId, untilTick);
    }
}
