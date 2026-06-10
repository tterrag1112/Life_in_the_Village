package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Npc.Religion.Sacred.SacredSpace;

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

    /** F1a sub-stage 3b — the GOD a miracle belongs to (its religion's primary god;
     *  the god is the subject of the gift). Null for an unresolvable miracle. */
    public static God godFor(ServerLevel level, Miracle m) {
        Religion r = m == null ? null : Religions.get(level, m.religionId());
        return r == null ? null : GodRegistry.primaryGod(r).orElse(null);
    }

    /** Resolves a miracle's status for {@code player} right now (gated by the GOD). */
    public static Status status(ServerLevel level, ServerPlayer player, Miracle m, long now) {
        UUID pid = player.getUUID();
        if (cooldownRemaining(pid, m.id(), now) > 0) return Status.ON_COOLDOWN;
        God god = godFor(level, m);
        if (god == null) return Status.LOCKED_FAVOUR;
        PietyTier tier = DivineFavour.tierForGod(level, pid, god.id());
        if (tier.ordinal() < m.minTier().ordinal()) return Status.LOCKED_TIER;
        float favour = DivineFavour.current(level, pid, god.id(), now);
        // S1b — sacred ground EASES the access threshold (minFavour) by the god's
        // sacredness here; the tier gate and the real cost are unchanged (sacred
        // space eases, it never bypasses the gate or the cap).
        float effFavour = favour * SacredSpace.amplifierAt(level, god.id(), player.blockPosition());
        if (effFavour < m.minFavour() || favour < m.cost()) return Status.LOCKED_FAVOUR;
        return Status.AVAILABLE;
    }

    /**
     * Invokes {@code miracleId} for {@code player}: gate → spend the GOD's favour →
     * apply → arm the cooldown. Returns a {@link Result} with a clear message.
     */
    public static Result cast(ServerLevel level, ServerPlayer player, String miracleId, long now) {
        Miracle m = Miracles.byId(miracleId);
        if (m == null) return new Result(false, "No such miracle: " + miracleId);
        UUID pid = player.getUUID();
        God god = godFor(level, m);
        if (god == null) return new Result(false, "No god grants " + m.displayName() + ".");

        long cd = cooldownRemaining(pid, m.id(), now);
        if (cd > 0) {
            return new Result(false, m.displayName() + " is still gathering ("
                    + (cd / 20) + "s).");
        }
        PietyTier tier = DivineFavour.tierForGod(level, pid, god.id());
        if (tier.ordinal() < m.minTier().ordinal()) {
            return new Result(false, m.displayName() + " requires deeper devotion ("
                    + m.minTier().displayName() + ").");
        }
        float favour = DivineFavour.current(level, pid, god.id(), now);
        // S1b — sacred ground eases the access threshold (minFavour); the cost is
        // still paid from REAL favour, so sacred space never bypasses the spend.
        float effFavour = favour * SacredSpace.amplifierAt(level, god.id(), player.blockPosition());
        if (effFavour < m.minFavour() || favour < m.cost()) {
            return new Result(false, "Not enough favour for " + m.displayName()
                    + " (need " + Math.round(Math.max(m.minFavour(), m.cost()))
                    + ", have " + Math.round(favour) + ").");
        }

        // Spend the GOD's favour (the only cost). Defensive: bail if the spend fails.
        if (!DivineFavour.spend(level, pid, god.id(), m.cost(), now)) {
            return new Result(false, god.displayName() + " withholds " + m.displayName() + ".");
        }
        // Apply the favour-scaled effect, then arm the cooldown.
        m.effect().apply(level, player, favour);
        arm(pid, m.id(), now + m.cooldownTicks());
        return new Result(true, m.displayName() + " — granted."
                + (effFavour > favour ? " The ground is sacred to " + god.displayName() + "." : ""));
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
