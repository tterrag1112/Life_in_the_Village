package tterrag1112.life_in_the_village.Kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Track D3.2a — wires the modifier infrastructure D1 deferred. A
 * {@link KingdomModifier} is a named, deltas-on-stability-and-legitimacy
 * record with optional expiry. Subsystems add modifiers in response
 * to events (recent crime, succession unrest, completed quests, etc.);
 * the kingdom's effective scalar is the base value plus the sum of
 * all active modifier deltas (computed by callers — see
 * {@code Kingdom.stabilityModifierSum} / {@code legitimacyModifierSum}).
 *
 * <p>Inert at D3.2a close beyond persistence: D3.2b registers the
 * tick subsystem that calls {@code Kingdom.pruneExpiredModifiers}
 * and emits the actual modifier records from the various drivers
 * (succession turbulence, crime, war, etc.).
 *
 * <h3>Identifier discipline</h3>
 * The {@code id} field is a string namespace (e.g. {@code "succession.disputed"},
 * {@code "war.active"}, {@code "modifier.recent_crime"}). Callers use
 * {@code Kingdom.removeModifier(id)} to revoke without iterating;
 * adding a second modifier with the same id is permitted (multiple
 * concurrent crime events can stack).
 *
 * @param id              namespaced identifier ({@code "succession.disputed"} etc.).
 * @param description     human-readable label rendered in debug + GUIs.
 * @param stabilityDelta  signed integer added to base stability while active.
 * @param legitimacyDelta signed integer added to base legitimacy while active.
 * @param appliedAtTick   server tick the modifier was added.
 * @param expiresAtTick   tick at which the modifier becomes eligible
 *                        for pruning, or {@code 0L} for a permanent
 *                        modifier (caller revokes explicitly).
 */
public record KingdomModifier(
        String id,
        String description,
        int stabilityDelta,
        int legitimacyDelta,
        long appliedAtTick,
        long expiresAtTick
) {

    public static final Codec<KingdomModifier> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(KingdomModifier::id),
            Codec.STRING.optionalFieldOf("description", "").forGetter(KingdomModifier::description),
            Codec.INT.optionalFieldOf("stabilityDelta", 0).forGetter(KingdomModifier::stabilityDelta),
            Codec.INT.optionalFieldOf("legitimacyDelta", 0).forGetter(KingdomModifier::legitimacyDelta),
            Codec.LONG.optionalFieldOf("appliedAtTick", 0L).forGetter(KingdomModifier::appliedAtTick),
            Codec.LONG.optionalFieldOf("expiresAtTick", 0L).forGetter(KingdomModifier::expiresAtTick)
    ).apply(i, KingdomModifier::new));

    /** Convenience constructor for permanent modifiers (no expiry). */
    public static KingdomModifier permanent(String id, String description,
                                            int stabilityDelta, int legitimacyDelta,
                                            long appliedAtTick) {
        return new KingdomModifier(id, description, stabilityDelta, legitimacyDelta,
                appliedAtTick, 0L);
    }

    /** Convenience constructor for an expiring modifier. */
    public static KingdomModifier expiring(String id, String description,
                                           int stabilityDelta, int legitimacyDelta,
                                           long appliedAtTick, long durationTicks) {
        return new KingdomModifier(id, description, stabilityDelta, legitimacyDelta,
                appliedAtTick, appliedAtTick + Math.max(1L, durationTicks));
    }

    public boolean isExpired(long currentTick) {
        return expiresAtTick > 0 && expiresAtTick <= currentTick;
    }

    public boolean isPermanent() {
        return expiresAtTick == 0L;
    }
}
