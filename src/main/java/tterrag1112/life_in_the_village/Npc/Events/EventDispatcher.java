package tterrag1112.life_in_the_village.Npc.Events;

/**
 * Listener interface for {@link NpcLifeEventBus}. Phase 1 has three
 * implementations (memory, mood, trait drift); Phase 2 adds the
 * relationship-ledger and gossip-seeder dispatchers.
 *
 * <p>Dispatchers receive every event posted to the bus and decide
 * internally which to act on (typically via a switch on the sealed
 * subtype). The bus runs them synchronously in registration order.</p>
 */
public interface EventDispatcher {

    /** Short identifier — surfaced in {@code /npc events stats}. */
    String name();

    /** React to an event. Implementations should be cheap and exception-safe. */
    void onEvent(NpcLifeEvent event);
}
