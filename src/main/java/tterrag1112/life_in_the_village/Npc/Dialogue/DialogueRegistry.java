package tterrag1112.life_in_the_village.Npc.Dialogue;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static registry of every {@link DialogueTree}. Trees are registered
 * once at mod init by {@link StarterTrees#registerAll()} (Phase 1
 * code-defined per spec line 272).
 *
 * <p>{@link #lookupWithFallback} implements the spec's hierarchy walk
 * (line 138): try the most specific composite id, then drop the
 * rightmost dotted segment, repeating until something matches. The
 * always-present {@code "fallback.universal"} tree backs the very
 * last resort.</p>
 */
public final class DialogueRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, DialogueTree> TREES = new LinkedHashMap<>();
    private static volatile boolean initialised = false;

    /** Tree id every consumer can rely on existing. */
    public static final String UNIVERSAL_FALLBACK = "fallback.universal";

    private DialogueRegistry() {}

    public static synchronized void register(DialogueTree tree) {
        if (TREES.containsKey(tree.treeId())) {
            LOGGER.warn("[DialogueRegistry] Duplicate tree id: {}", tree.treeId());
        }
        TREES.put(tree.treeId(), tree);
    }

    public static Optional<DialogueTree> get(String treeId) {
        ensureInit();
        return Optional.ofNullable(TREES.get(treeId));
    }

    public static List<DialogueTree> all() {
        ensureInit();
        return List.copyOf(TREES.values());
    }

    public static List<String> allIds() {
        ensureInit();
        return List.copyOf(TREES.keySet());
    }

    /**
     * Resolves a composite {@code treeId} to a tree, walking the
     * dotted-suffix fallback chain. Always returns a tree —
     * {@link #UNIVERSAL_FALLBACK} is the floor.
     */
    public static DialogueTree lookupWithFallback(String treeId) {
        ensureInit();
        String current = treeId;
        while (current != null && !current.isEmpty()) {
            DialogueTree direct = TREES.get(current);
            if (direct != null) return direct;
            int lastDot = current.lastIndexOf('.');
            if (lastDot < 0) break;
            current = current.substring(0, lastDot);
        }
        DialogueTree universal = TREES.get(UNIVERSAL_FALLBACK);
        if (universal != null) return universal;
        // Never-should-happen safety net.
        return new DialogueTree(UNIVERSAL_FALLBACK,
                DialogueNode.lines(DialogueLine.of("...")));
    }

    public static synchronized void ensureInit() {
        if (initialised) return;
        StarterTrees.registerAll();
        initialised = true;
        LOGGER.info("[DialogueRegistry] Registered {} dialogue trees", TREES.size());
    }

    /** Test-only reset. */
    static synchronized void resetForTest() {
        TREES.clear();
        initialised = false;
    }
}
