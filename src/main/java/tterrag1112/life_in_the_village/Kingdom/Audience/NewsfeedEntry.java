package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Track D3.5C — single entry in a kingdom's rolling newsfeed
 * buffer.
 *
 * <p>{@code KingdomNewsfeed} captures recent kingdom-events
 * (petition resolutions, charter grants, treaty changes, intrigue
 * outcomes) into a bounded per-kingdom log so the AUDIENCE GUI
 * can surface them without subscribing to the full
 * {@code KingdomEventBus}. Stored on
 * {@link tterrag1112.life_in_the_village.Lore.KingdomHistoryData}
 * as a transient sibling to the long-form history (newsfeed is
 * recent + truncated; history is curated narrative). To avoid
 * exhausting the {@code KingdomGovernanceData} 16-field cap, the
 * rolling buffer lives directly on the {@code Kingdom} object —
 * not in the governance bundle — and round-trips via the
 * top-level Kingdom CODEC.
 *
 * @param tag      short event-type tag ("petition.approved",
 *                 "charter.granted", "treaty.broken", etc.); used
 *                 by the GUI to colour-tint the entry.
 * @param summary  one-line human-readable summary.
 * @param tick     server tick the event fired.
 */
public record NewsfeedEntry(String tag, String summary, long tick) {

    /** Per-kingdom rolling buffer cap (~30 in-game days at 1-2/day). */
    public static final int BUFFER_CAPACITY = 64;

    public static final Codec<NewsfeedEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("tag").forGetter(NewsfeedEntry::tag),
            Codec.STRING.fieldOf("summary").forGetter(NewsfeedEntry::summary),
            Codec.LONG.fieldOf("tick").forGetter(NewsfeedEntry::tick)
    ).apply(i, NewsfeedEntry::new));
}
