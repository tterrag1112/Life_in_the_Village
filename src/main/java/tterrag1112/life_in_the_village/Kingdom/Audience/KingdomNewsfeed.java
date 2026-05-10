package tterrag1112.life_in_the_village.Kingdom.Audience;

import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;

/**
 * Track D3.5C — helper for appending entries to a kingdom's
 * rolling newsfeed buffer.
 *
 * <p>The newsfeed lives on
 * {@link tterrag1112.life_in_the_village.Lore.KingdomHistoryData}
 * (codec round-trip) and is bounded to
 * {@link NewsfeedEntry#BUFFER_CAPACITY}. This helper centralises
 * the append + corresponding {@link KingdomEvent.NewsfeedAppended}
 * event fire so callers don't drift on the format.
 */
public final class KingdomNewsfeed {

    private KingdomNewsfeed() {}

    /**
     * Appends an entry to the kingdom's newsfeed and fires the
     * matching event. No-op when {@code kingdom} is null.
     */
    public static void append(Kingdom kingdom, String tag, String summary, long tick) {
        if (kingdom == null) return;
        kingdom.getHistory().appendNewsfeed(new NewsfeedEntry(tag, summary, tick));
        KingdomEventBus.fire(new KingdomEvent.NewsfeedAppended(
                kingdom.getId(), tag, summary, tick));
    }
}
