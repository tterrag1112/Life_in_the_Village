package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Per-{@link VillageEvent.EventType} hook for the Phase 5 doc 32 event
 * catalogue.
 *
 * <p>The Phase-3 originals (HARVEST_FESTIVAL / MARKET_DAY / etc.) keep
 * their bespoke logic in {@link EventEffects}; everything Phase 5 adds
 * registers a handler here so the scheduler stays a thin dispatcher.</p>
 *
 * <p>Phase 5 ships handlers as functional but content-thin. Per-type
 * flavor text and elaborate effects belong to a later content pass
 * (Phase 5 doc 34).</p>
 */
public interface EventHandler {

    VillageEvent.EventType type();

    /**
     * Fired when the scheduler advances {@link VillageEvent.EventStatus#ANNOUNCED}
     * → {@link VillageEvent.EventStatus#ACTIVE}. Apply ambient effects,
     * spawn helpers, etc.
     */
    default void onStart(VillageEvent event, ServerLevel level,
                         Village village, VillageSavedData data) {}

    /**
     * Fired when the scheduler advances {@link VillageEvent.EventStatus#ACTIVE}
     * → {@link VillageEvent.EventStatus#ENDED}. Post memories / mood
     * triggers / gossip seeds for actual attendees here.
     */
    default void onComplete(VillageEvent event, ServerLevel level,
                            Village village, VillageSavedData data) {}
}
