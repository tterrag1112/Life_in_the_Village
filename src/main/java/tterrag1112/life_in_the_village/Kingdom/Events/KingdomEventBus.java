package tterrag1112.life_in_the_village.Kingdom.Events;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Track D1 (Phase 0) — kingdom-tier dispatch hub. Peer of
 * {@code NpcLifeEventBus} (NOT a subclass, NOT nested) — same wiring
 * style at {@code Kingdom.Events.KingdomEventBus} sibling-level so
 * future kingdom subsystems can register dispatchers via the same
 * idiom NPC code uses.
 *
 * <h3>D1 scope</h3>
 * The bus is live but {@link #registerDefaults()} is a no-op — no
 * dispatchers are subscribed in this phase. {@link KingdomEvent}
 * records define the taxonomy; D2 / D3 register real listeners
 * (stability / legitimacy decay drivers, office population logic,
 * kingdom-history archival). Code that wants to fire kingdom events
 * today can already do so safely; the events fan out to zero
 * subscribers and increment counters only.
 *
 * <h3>Threading</h3>
 * Synchronous fan-out, like {@code NpcLifeEventBus}. Each dispatcher
 * runs in a try-catch so one buggy listener can't cancel later ones.
 */
public final class KingdomEventBus {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<KingdomEventDispatcher> DISPATCHERS = new ArrayList<>();
    private static final Map<String, AtomicLong> EVENT_COUNTS = new LinkedHashMap<>();
    private static final List<Consumer<KingdomEvent>> LISTENERS = new ArrayList<>();
    private static volatile boolean defaultsRegistered = false;

    private KingdomEventBus() {}

    // ── Registration ───────────────────────────────────────────────────────

    public static synchronized void register(KingdomEventDispatcher dispatcher) {
        DISPATCHERS.add(dispatcher);
        EVENT_COUNTS.computeIfAbsent(dispatcher.name(), k -> new AtomicLong(0L));
        LOGGER.debug("[KingdomEventBus] Registered dispatcher: {}", dispatcher.name());
    }

    /**
     * D1 — no-op. D2 / D3 will register real dispatchers here. Idempotent.
     */
    public static synchronized void registerDefaults() {
        if (defaultsRegistered) return;
        // D1 (Phase 0) — no default subscribers. The taxonomy is live;
        // adding subscribers is a D2 / D3 concern.
        defaultsRegistered = true;
        LOGGER.info("[KingdomEventBus] Phase 0 — bus initialised with {} default dispatcher(s)",
                DISPATCHERS.size());
    }

    // ── Fire ───────────────────────────────────────────────────────────────

    /**
     * Synchronously fans the event out to every registered dispatcher.
     * Each dispatcher runs in a try-catch so one buggy dispatcher
     * cannot cancel later ones. Errors logged at debug-suppressed
     * cadence. Mirrors {@code NpcLifeEventBus.fire} exactly.
     */
    @SuppressWarnings("unchecked")
    public static void fire(KingdomEvent event) {
        if (event == null) return;
        Consumer<KingdomEvent>[] snapshot;
        synchronized (LISTENERS) {
            snapshot = LISTENERS.toArray(new Consumer[0]);
        }
        for (Consumer<KingdomEvent> l : snapshot) {
            try {
                l.accept(event);
            } catch (Throwable t) {
                LOGGER.debug("[KingdomEventBus] Listener threw on {}",
                        event.getClass().getSimpleName(), t);
            }
        }
        for (KingdomEventDispatcher d : DISPATCHERS) {
            try {
                d.onEvent(event);
                AtomicLong c = EVENT_COUNTS.get(d.name());
                if (c != null) c.incrementAndGet();
            } catch (Throwable t) {
                LOGGER.warn("[KingdomEventBus] Dispatcher '{}' threw on {}",
                        d.name(), event.getClass().getSimpleName(), t);
            }
        }
    }

    /** Convenience for posting a batch synchronously. */
    public static void fireBatch(List<KingdomEvent> events) {
        for (KingdomEvent e : events) fire(e);
    }

    // ── Diagnostics surfaces ────────────────────────────────────────────────

    public static List<String> dispatcherNames() {
        return DISPATCHERS.stream().map(KingdomEventDispatcher::name).toList();
    }

    public static long eventCount(String dispatcherName) {
        AtomicLong c = EVENT_COUNTS.get(dispatcherName);
        return c == null ? 0L : c.get();
    }

    /** Snapshot of all dispatcher counts. Insertion-ordered by registration. */
    public static Map<String, Long> allCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> e : EVENT_COUNTS.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Adds a transient listener that receives every fired event.
     * Mirrors {@code NpcLifeEventBus.addListener}. Used by debug
     * tooling and testing.
     */
    public static Consumer<KingdomEvent> addListener(Consumer<KingdomEvent> listener) {
        synchronized (LISTENERS) {
            LISTENERS.add(listener);
        }
        return listener;
    }

    public static boolean removeListener(Consumer<KingdomEvent> listener) {
        synchronized (LISTENERS) {
            return LISTENERS.remove(listener);
        }
    }

    public static int listenerCount() {
        synchronized (LISTENERS) {
            return LISTENERS.size();
        }
    }
}
