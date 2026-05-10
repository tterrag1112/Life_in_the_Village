package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Track D3.5A — a single player's standing with a single kingdom.
 *
 * <p>Per the user-confirmed design "standing kingdom-side
 * authoritative": stored on each {@code Kingdom}'s
 * {@code playerStandings} map keyed by player UUID. The player
 * doesn't carry a mirror — their reputation is whatever the
 * kingdom remembers.
 *
 * <h3>Score range and semantics</h3>
 * <ul>
 *   <li>{@code +100} ↔ {@code -100} integer score.</li>
 *   <li>{@code +50} = "trusted ally"; petitions auto-favoured.</li>
 *   <li>{@code 0}  = "stranger"; baseline.</li>
 *   <li>{@code -50} = "hostile"; petitions auto-denied.</li>
 * </ul>
 *
 * <h3>Decay</h3>
 * Every 7 in-game days, the score moves 1 step toward 0 (whichever
 * direction). Decay only applies when no standing-affecting events
 * fired in the last interval. {@link #lastDecayTick} tracks the
 * last applied decay.
 *
 * @param playerUuid     the player.
 * @param score          current standing.
 * @param lastDecayTick  tick of the last applied decay step.
 * @param lifetimeFavour cumulative positive standing earned (for
 *                       achievement / GUI purposes; never decays).
 * @param lifetimeWrath  cumulative negative standing earned.
 */
public record PlayerStanding(
        UUID playerUuid,
        int score,
        long lastDecayTick,
        int lifetimeFavour,
        int lifetimeWrath,
        /**
         * Track D3.5D — last tick this player submitted a petition
         * to this kingdom. Drives the per-petitioner rate limit
         * checked in {@link AudienceDriver#submit}. 0 means "never
         * submitted" — back-compat for pre-D3.5D records.
         */
        long lastSubmitTick
) {

    public static final int MIN_SCORE = -100;
    public static final int MAX_SCORE = 100;
    public static final int TRUSTED_THRESHOLD = 50;
    public static final int HOSTILE_THRESHOLD = -50;

    /** Decay one step every 7 in-game days. */
    public static final long DECAY_INTERVAL_TICKS = 7L * 24000L;

    /**
     * Per-petitioner submit rate limit. Track D3.5D: 1 in-game
     * hour cooldown between submissions to the same kingdom.
     * Trusted petitioners get a halved cooldown; hostile ones
     * get a doubled cooldown.
     */
    public static final long SUBMIT_COOLDOWN_TICKS = 1000L;

    public static final Codec<PlayerStanding> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("playerUuid").forGetter(PlayerStanding::playerUuid),
            Codec.INT.optionalFieldOf("score", 0).forGetter(PlayerStanding::score),
            Codec.LONG.optionalFieldOf("lastDecayTick", 0L).forGetter(PlayerStanding::lastDecayTick),
            Codec.INT.optionalFieldOf("lifetimeFavour", 0).forGetter(PlayerStanding::lifetimeFavour),
            Codec.INT.optionalFieldOf("lifetimeWrath", 0).forGetter(PlayerStanding::lifetimeWrath),
            Codec.LONG.optionalFieldOf("lastSubmitTick", 0L).forGetter(PlayerStanding::lastSubmitTick)
    ).apply(i, PlayerStanding::new));

    public boolean isTrusted() { return score >= TRUSTED_THRESHOLD; }
    public boolean isHostile() { return score <= HOSTILE_THRESHOLD; }

    public PlayerStanding withScore(int newScore) {
        int clamped = Math.max(MIN_SCORE, Math.min(MAX_SCORE, newScore));
        int delta = clamped - score;
        int newFavour = lifetimeFavour + Math.max(0, delta);
        int newWrath  = lifetimeWrath + Math.max(0, -delta);
        return new PlayerStanding(playerUuid, clamped, lastDecayTick,
                newFavour, newWrath, lastSubmitTick);
    }

    public PlayerStanding addDelta(int delta) {
        return withScore(score + delta);
    }

    /** Stamps a fresh submit tick for the rate limit check. */
    public PlayerStanding withSubmitAt(long tick) {
        return new PlayerStanding(playerUuid, score, lastDecayTick,
                lifetimeFavour, lifetimeWrath, tick);
    }

    /**
     * True when {@code currentTick} is past
     * {@code lastSubmitTick + cooldown}, with cooldown adjusted
     * for trust band (trusted halved, hostile doubled, neutral
     * baseline).
     */
    public boolean canSubmitAt(long currentTick) {
        if (lastSubmitTick == 0L) return true;
        long cooldown = SUBMIT_COOLDOWN_TICKS;
        if (isTrusted())      cooldown /= 2;
        else if (isHostile()) cooldown *= 2;
        return currentTick - lastSubmitTick >= cooldown;
    }

    /**
     * Applies decay if {@code currentTick - lastDecayTick} has met
     * one or more decay intervals. Each elapsed interval moves the
     * score 1 step toward 0. Returns the updated record.
     */
    public PlayerStanding applyDecay(long currentTick) {
        if (lastDecayTick == 0L) {
            // First-touch baseline; don't move.
            return new PlayerStanding(playerUuid, score, currentTick,
                    lifetimeFavour, lifetimeWrath, lastSubmitTick);
        }
        long elapsed = currentTick - lastDecayTick;
        if (elapsed < DECAY_INTERVAL_TICKS) return this;
        long steps = elapsed / DECAY_INTERVAL_TICKS;
        int next = score;
        for (long s = 0; s < steps && next != 0; s++) {
            next += (next > 0) ? -1 : 1;
        }
        return new PlayerStanding(playerUuid, next,
                lastDecayTick + steps * DECAY_INTERVAL_TICKS,
                lifetimeFavour, lifetimeWrath, lastSubmitTick);
    }

    public static PlayerStanding fresh(UUID playerUuid, long tick) {
        return new PlayerStanding(playerUuid, 0, tick, 0, 0, 0L);
    }
}
