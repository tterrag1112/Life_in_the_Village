package tterrag1112.life_in_the_village.Npc.Scribal;

import com.mojang.serialization.Codec;

/**
 * Lifecycle of a {@link ScribeCommission}. Spec doesn't explicitly
 * enumerate the states; the four below cover the queue → progress →
 * delivery flow used by {@code ScribeWorkGoal}.
 */
public enum CommissionStatus {
    /** Sitting in the queue waiting to be picked up. */
    PENDING,
    /** Scribe has started work; consumed inputs. */
    IN_PROGRESS,
    /** Output produced; awaiting client pickup. */
    READY,
    /** Delivered to client (or stashed in workshop chest). Terminal. */
    DELIVERED,
    /** Past due / refunded; terminal failure state. */
    EXPIRED;

    public boolean isTerminal() { return this == DELIVERED || this == EXPIRED; }

    public static final Codec<CommissionStatus> CODEC =
            Codec.STRING.xmap(CommissionStatus::valueOf, CommissionStatus::name);
}
