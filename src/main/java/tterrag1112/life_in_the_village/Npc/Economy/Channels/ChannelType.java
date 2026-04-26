package tterrag1112.life_in_the_village.Npc.Economy.Channels;

/**
 * Discriminator for {@link EconomicChannel} implementations. Spec line 54.
 *
 * <p>Phase 3 implements MARKET / DIRECT_BUSINESS / CARAVAN / STOCKPILE
 * fully; GUILD_REQUEST + VISITOR are stubbed (always-unavailable
 * channels) so the enum remains complete and Phase 4 only needs to fill
 * the implementations.</p>
 */
public enum ChannelType {
    MARKET,
    DIRECT_BUSINESS,
    CARAVAN,
    GUILD_REQUEST,
    STOCKPILE,
    VISITOR
}
