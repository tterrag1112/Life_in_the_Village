package tterrag1112.life_in_the_village.Npc.Dialogue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-NPC bounded memory of recently spoken dialogue lines, used by
 * {@link DialogueWalker} to down-weight repetition within a session.
 *
 * <p>Cleared on server restart (spec line 293 — "lives in a small
 * in-memory cache, cleared on server restart — not persisted").</p>
 */
final class DialogueRecencyCache {

    static final DialogueRecencyCache INSTANCE = new DialogueRecencyCache();
    private static final int RECENCY_WINDOW = 6;

    private final Map<UUID, Deque<String>> bySpeaker = new ConcurrentHashMap<>();

    private DialogueRecencyCache() {}

    boolean recentlySpoken(UUID speakerId, String text) {
        Deque<String> q = bySpeaker.get(speakerId);
        return q != null && q.contains(text);
    }

    void markSpoken(UUID speakerId, String text) {
        Deque<String> q = bySpeaker.computeIfAbsent(speakerId, k -> new ArrayDeque<>());
        q.addLast(text);
        while (q.size() > RECENCY_WINDOW) q.removeFirst();
    }

    /** Test-only reset. */
    void reset() { bySpeaker.clear(); }
}
