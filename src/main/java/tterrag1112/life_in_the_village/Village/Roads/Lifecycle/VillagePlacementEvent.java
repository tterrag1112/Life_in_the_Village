package tterrag1112.life_in_the_village.Village.Roads.Lifecycle;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Phase 9 — fire-and-forget hook fired whenever a village is placed via the
 * kingdom seeder or the in-game realisation system. The event carries the
 * road-network alignment score that contributed to the placement decision so
 * future systems (analytics, lore generators, in-game notifications) can react
 * to network-aware growth.
 *
 * <p>Listener exceptions are caught and printed to stderr so a faulty
 * subscriber can't crash the placement pipeline.
 */
public final class VillagePlacementEvent {

    private VillagePlacementEvent() {}

    public record Event(
            UUID villageId,
            String villageType,
            BlockPos position,
            boolean isCapital,
            float networkScore,
            long tick
    ) {}

    public interface Listener { void onVillagePlaced(Event event); }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void subscribe(Listener listener) { LISTENERS.add(listener); }

    public static void unsubscribe(Listener listener) { LISTENERS.remove(listener); }

    public static void fire(Event event) {
        for (Listener l : LISTENERS) {
            try { l.onVillagePlaced(event); }
            catch (Exception e) {
                System.err.println("[VillagePlacementEvent] listener threw: " + e);
            }
        }
    }
}
