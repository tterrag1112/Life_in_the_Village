package tterrag1112.life_in_the_village.Gui.Framework;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side LRU cache mapping NPC UUIDs to their preview entities.
 * Capped at {@value MAX_ENTRIES} entries; clears on world disconnect.
 */
public final class PortraitCache {

    private static final int MAX_ENTRIES = 16;

    private static final LinkedHashMap<UUID, TownspersonMob> CACHE =
            new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, TownspersonMob> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private PortraitCache() {}

    /**
     * Returns a cached preview entity for {@code npcId}, creating one from
     * {@code snap} if not already present.
     */
    @Nullable
    public static TownspersonMob getOrCreate(UUID npcId, Portrait.AppearanceSnapshot snap) {
        return CACHE.computeIfAbsent(npcId, id -> ClientNpcPreview.create(snap));
    }

    /**
     * Forcibly replaces the cached entry (called when appearance changes).
     * Returns the new entity, or {@code null} when the client level is absent.
     */
    @Nullable
    public static TownspersonMob rebuild(UUID npcId, Portrait.AppearanceSnapshot snap) {
        TownspersonMob entity = ClientNpcPreview.create(snap);
        if (entity != null) {
            CACHE.put(npcId, entity);
        } else {
            CACHE.remove(npcId);
        }
        return entity;
    }

    /** Drops all cached entities — call on world disconnect. */
    public static void clear() {
        CACHE.clear();
    }
}
