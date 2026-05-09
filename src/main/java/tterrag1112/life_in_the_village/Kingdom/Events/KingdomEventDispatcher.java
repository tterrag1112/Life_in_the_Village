package tterrag1112.life_in_the_village.Kingdom.Events;

/**
 * Track D1 — listener interface for {@link KingdomEventBus}. Mirrors
 * {@code Npc.Events.EventDispatcher} exactly. D1 has no
 * implementations registered; D2 / D3 will add ones that drive
 * stability / legitimacy decay, office population, and history
 * archival.
 */
public interface KingdomEventDispatcher {

    /** Short identifier — surfaced in {@code /litv kingdom debug events stats}. */
    String name();

    /** React to an event. Implementations should be cheap and exception-safe. */
    void onEvent(KingdomEvent event);
}
