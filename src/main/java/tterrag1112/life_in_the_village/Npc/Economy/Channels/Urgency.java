package tterrag1112.life_in_the_village.Npc.Economy.Channels;

/**
 * Buyer / seller urgency signal — used by {@link ChannelRouter} to bias
 * the score toward fast or cheap channels.
 *
 * <p>Spec mapping (line 164): {@code IMMEDIATE → fastest;
 * PATIENT → cheapest}. {@code NORMAL} sits in between with no extra
 * weight.</p>
 */
public enum Urgency {
    IMMEDIATE, NORMAL, PATIENT
}
