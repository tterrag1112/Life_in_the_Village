package tterrag1112.life_in_the_village.Entities.custom;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-NPC, per-player relationship score. Adds a small personal bias on top
 * of the village-wide {@link tterrag1112.life_in_the_village.Village.Reputation.VillageReputation}
 * so individual NPCs can remember gifts, insults, and conversations.
 *
 * <p>Deltas are clamped to {@link #MIN_DELTA}..{@link #MAX_DELTA}. Persistence
 * is left to the owning mob — use {@link #encode()}/{@link #decode(String)}
 * to move state in and out of a flat string tag, matching the style of
 * {@link FamilyComponent} and {@link AppearanceComponent} serialization.
 */
public class NpcRelationshipComponent {

    /** Per-player clamp bounds. Kept narrow so village rep still dominates. */
    public static final int MIN_DELTA = -20;
    public static final int MAX_DELTA =  20;

    private final Map<UUID, Integer> deltas = new HashMap<>();

    /** Returns the stored delta for this player, or 0 if none. */
    public int getDelta(UUID playerId) {
        return deltas.getOrDefault(playerId, 0);
    }

    /**
     * Adds {@code amount} to this player's delta and clamps into
     * [{@link #MIN_DELTA}, {@link #MAX_DELTA}]. Returns the new value.
     */
    public int adjust(UUID playerId, int amount) {
        int next = clamp(getDelta(playerId) + amount);
        if (next == 0) deltas.remove(playerId);
        else           deltas.put(playerId, next);
        return next;
    }

    /** Overwrites the delta for this player (clamped to the legal range). */
    public void set(UUID playerId, int value) {
        int v = clamp(value);
        if (v == 0) deltas.remove(playerId);
        else        deltas.put(playerId, v);
    }

    /** Unmodifiable view of all stored deltas, for snapshotting or debug. */
    public Map<UUID, Integer> getAll() {
        return Collections.unmodifiableMap(deltas);
    }

    public void clear() { deltas.clear(); }

    // =========================================================================
    // Persistence — flat string "uuid:delta,uuid:delta,…"
    // =========================================================================

    /** Encodes the map as a single string, or "" when empty. */
    public String encode() {
        if (deltas.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<UUID, Integer> e : deltas.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** Replaces the current state with entries parsed from {@link #encode()}. */
    public void decode(String raw) {
        deltas.clear();
        if (raw == null || raw.isEmpty()) return;
        for (String entry : raw.split(",")) {
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) continue;
            try {
                UUID id   = UUID.fromString(entry.substring(0, colon));
                int value = Integer.parseInt(entry.substring(colon + 1));
                if (value != 0) deltas.put(id, clamp(value));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private static int clamp(int v) {
        return Math.max(MIN_DELTA, Math.min(MAX_DELTA, v));
    }
}
