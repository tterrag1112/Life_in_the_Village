package tterrag1112.life_in_the_village.Npc.Gossip;

/**
 * The location/situation in which a {@link GossipChannel} is taking
 * place. Drives how many entries can be exchanged per session.
 *
 * <p>Spec: {@code docs/npc_redesign/12-gossip-rumor.md} (line 35).</p>
 */
public enum GossipContext {
    CASUAL_MEETING(1, 1),
    AT_WELL       (1, 2),
    AT_WORKPLACE  (1, 2),
    AT_MARKET     (1, 2),
    AT_FESTIVAL   (2, 4),
    AT_INN        (2, 4),
    AFTER_RITE    (1, 3);

    private final int minExchanges;
    private final int maxExchanges;

    GossipContext(int min, int max) {
        this.minExchanges = min;
        this.maxExchanges = max;
    }

    public int minExchanges() { return minExchanges; }
    public int maxExchanges() { return maxExchanges; }
}
