package tterrag1112.life_in_the_village.Npc.Memory;

import com.mojang.serialization.Codec;

/**
 * Situational-event categories a {@link NpcMemory} can represent. Types are
 * chosen to be orthogonal to persisted family / employment / household data
 * — memory is for things that would otherwise vanish (a gift, a crime
 * witnessed, a shared festival), not facts that already live on their own
 * component.
 *
 * <p>See {@code docs/npc_redesign/02-memory-system.md} for the definitive
 * type list, initial-value table, and polarity guidance.</p>
 */
public enum MemoryType {
    // ── Player ↔ NPC events ────────────────────────────────────────────────
    TRADED_WITH        (Polarity.NEUTRAL),
    RECEIVED_GIFT      (Polarity.POSITIVE),
    GAVE_GIFT          (Polarity.POSITIVE),
    SAVED_BY           (Polarity.POSITIVE),
    SAVED              (Polarity.POSITIVE),
    INSULTED_BY        (Polarity.NEGATIVE),
    COMPLIMENTED_BY    (Polarity.POSITIVE),
    WITNESSED_CRIME_BY (Polarity.NEGATIVE),
    VICTIM_OF_CRIME_BY (Polarity.NEGATIVE),
    DEFENDED_BY        (Polarity.POSITIVE),
    RECEIVED_LETTER    (Polarity.POSITIVE),
    COMMISSIONED_WORK  (Polarity.NEUTRAL),
    FAVOR_DONE_FOR     (Polarity.POSITIVE),
    FAVOR_RECEIVED_FROM(Polarity.POSITIVE),
    PROMISED_BY        (Polarity.NEUTRAL),

    // ── Multi-participant events ───────────────────────────────────────────
    SHARED_FESTIVAL    (Polarity.NEUTRAL),
    SHARED_HARDSHIP    (Polarity.POSITIVE),
    WITNESSED_DEATH_OF (Polarity.NEGATIVE),
    TAUGHT_BY          (Polarity.POSITIVE),
    TAUGHT             (Polarity.POSITIVE),
    OFFICIATED_BY      (Polarity.POSITIVE),
    OFFICIATED_FOR     (Polarity.POSITIVE);

    private enum Polarity { POSITIVE, NEUTRAL, NEGATIVE }

    private final Polarity polarity;

    MemoryType(Polarity polarity) {
        this.polarity = polarity;
    }

    /**
     * {@code +1} for positive-polarity memories (gift, rescue, compliment),
     * {@code -1} for negative (insult, theft, witnessed crime), {@code 0} for
     * neutral (trade, shared festival). Used by mood / relationship routing
     * in later phases; stable contract for Phase 0 callers to depend on.
     */
    public int polarity() {
        return switch (polarity) {
            case POSITIVE -> +1;
            case NEUTRAL -> 0;
            case NEGATIVE -> -1;
        };
    }

    public static final Codec<MemoryType> CODEC =
            Codec.STRING.xmap(MemoryType::valueOf, MemoryType::name);
}
