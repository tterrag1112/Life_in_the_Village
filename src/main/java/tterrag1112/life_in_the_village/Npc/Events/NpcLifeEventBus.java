package tterrag1112.life_in_the_village.Npc.Events;

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
 * Central synchronous dispatch hub for {@link NpcLifeEvent}. Producers
 * fire events here; registered {@link EventDispatcher}s receive every
 * event in registration order.
 *
 * <p>Spec: {@code docs/npc_redesign/10-phase1-integration.md} — "Event
 * dispatch pattern" / "Open decisions". v1 dispatch is synchronous and
 * fire-and-forget; events fired against unloaded NPCs are silently
 * dropped (rare in practice).</p>
 *
 * <p>Phase 1 registers MemoryProducer, MoodProducer, TraitDriftProducer
 * at mod startup. Phase 2 will add relationship + gossip dispatchers
 * via the same {@link #register} surface.</p>
 */
public final class NpcLifeEventBus {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<EventDispatcher> DISPATCHERS = new ArrayList<>();
    private static final Map<String, AtomicLong> EVENT_COUNTS = new LinkedHashMap<>();
    private static final List<Consumer<NpcLifeEvent>> LISTENERS = new ArrayList<>();
    private static volatile boolean defaultsRegistered = false;

    private NpcLifeEventBus() {}

    // ── Registration ───────────────────────────────────────────────────────

    public static synchronized void register(EventDispatcher dispatcher) {
        DISPATCHERS.add(dispatcher);
        EVENT_COUNTS.computeIfAbsent(dispatcher.name(), k -> new AtomicLong(0L));
        LOGGER.debug("[NpcLifeEventBus] Registered dispatcher: {}", dispatcher.name());
    }

    /**
     * Wires the three Phase 1 dispatchers (memory / mood / trait drift).
     * Idempotent — safe to call from multiple init paths.
     */
    public static synchronized void registerDefaults() {
        if (defaultsRegistered) return;
        register(new tterrag1112.life_in_the_village.Npc.Events.Producers.MemoryProducer());
        register(new tterrag1112.life_in_the_village.Npc.Events.Producers.MoodProducer());
        register(new tterrag1112.life_in_the_village.Npc.Events.Producers.TraitDriftProducer());
        defaultsRegistered = true;
        LOGGER.info("[NpcLifeEventBus] Registered {} default dispatchers", DISPATCHERS.size());
    }

    // ── Fire ───────────────────────────────────────────────────────────────

    /**
     * Synchronously fans the event out to every registered dispatcher.
     * Each dispatcher runs in a try-catch so one buggy dispatcher cannot
     * cancel later ones. Errors logged at debug-suppressed cadence.
     */
    public static void fire(NpcLifeEvent event) {
        if (event == null) return;
        // Snapshot listeners outside the dispatch loop so a listener that
        // registers itself during dispatch doesn't get notified mid-stream.
        Consumer<NpcLifeEvent>[] snapshot;
        synchronized (LISTENERS) {
            snapshot = LISTENERS.toArray(new Consumer[0]);
        }
        for (Consumer<NpcLifeEvent> l : snapshot) {
            try {
                l.accept(event);
            } catch (Throwable t) {
                LOGGER.debug("[NpcLifeEventBus] Listener threw on {}", event.getClass().getSimpleName(), t);
            }
        }
        for (EventDispatcher d : DISPATCHERS) {
            try {
                d.onEvent(event);
                AtomicLong c = EVENT_COUNTS.get(d.name());
                if (c != null) c.incrementAndGet();
            } catch (Throwable t) {
                LOGGER.warn("[NpcLifeEventBus] Dispatcher '{}' threw on {}",
                        d.name(), event.getClass().getSimpleName(), t);
            }
        }
    }

    /** Convenience for posting a batch synchronously. */
    public static void fireBatch(List<NpcLifeEvent> events) {
        for (NpcLifeEvent e : events) fire(e);
    }

    // ── Diagnostics surfaces (for /npc events stats / listen) ──────────────

    public static List<String> dispatcherNames() {
        return DISPATCHERS.stream().map(EventDispatcher::name).toList();
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
     * Adds a transient listener that receives every fired event. Used by
     * {@code /npc events listen}. Returns the listener so callers can
     * remove it later.
     */
    public static Consumer<NpcLifeEvent> addListener(Consumer<NpcLifeEvent> listener) {
        synchronized (LISTENERS) {
            LISTENERS.add(listener);
        }
        return listener;
    }

    public static boolean removeListener(Consumer<NpcLifeEvent> listener) {
        synchronized (LISTENERS) {
            return LISTENERS.remove(listener);
        }
    }

    public static int listenerCount() {
        synchronized (LISTENERS) {
            return LISTENERS.size();
        }
    }

    /** Test-only reset. */
    static synchronized void resetForTest() {
        DISPATCHERS.clear();
        EVENT_COUNTS.clear();
        synchronized (LISTENERS) { LISTENERS.clear(); }
        defaultsRegistered = false;
    }
}
