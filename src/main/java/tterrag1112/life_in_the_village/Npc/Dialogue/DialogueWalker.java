package tterrag1112.life_in_the_village.Npc.Dialogue;

import com.mojang.logging.LogUtils;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Walks a {@link DialogueTree} from its root, evaluating
 * {@link DialogueNode.Branch} predicates against the
 * {@link DialogueContext} until it lands on a {@link DialogueNode.Lines}
 * leaf, then picks one line from the pool with mild
 * recency-down-weighting.
 *
 * <p>Spec: {@code 08-dialogue-tree.md} (section "Tree walker", line 154).
 * The recency cache (per-NPC last-N-spoken) lives in-memory, cleared on
 * server restart.</p>
 */
public final class DialogueWalker {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** How many predicate-only nodes we'll chase before bailing — guards cycles. */
    private static final int MAX_DEPTH = 64;

    private DialogueWalker() {}

    public static DialogueLine select(DialogueTree tree, DialogueContext ctx) {
        DialogueNode node = tree.root();
        Deque<String> visitedTrees = new ArrayDeque<>();
        visitedTrees.push(tree.treeId());

        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (node instanceof DialogueNode.Lines leaf) {
                return pickFromPool(ctx, leaf.pool());
            }
            if (node instanceof DialogueNode.Branch b) {
                node = b.predicate().test(ctx) ? b.trueChild() : b.falseChild();
                continue;
            }
            if (node instanceof DialogueNode.Ref ref) {
                if (visitedTrees.contains(ref.treeId())) {
                    LOGGER.warn("[Dialogue] Ref cycle detected at '{}'; falling back",
                            ref.treeId());
                    return DialogueLine.of("...");
                }
                visitedTrees.push(ref.treeId());
                DialogueTree sub = DialogueRegistry.get(ref.treeId()).orElse(null);
                if (sub == null) {
                    LOGGER.warn("[Dialogue] Ref to missing tree '{}'", ref.treeId());
                    return DialogueLine.of("...");
                }
                node = sub.root();
            }
        }
        LOGGER.warn("[Dialogue] Walker max-depth exceeded in tree '{}'", tree.treeId());
        return DialogueLine.of("...");
    }

    /**
     * Pool-pick: deterministic-ish (server RNG) with a small bonus for
     * lines that the speaker hasn't recently used. Recency tracking is
     * per-NPC and lives in {@link DialogueRecencyCache}.
     */
    private static DialogueLine pickFromPool(DialogueContext ctx, List<DialogueLine> pool) {
        if (pool.isEmpty()) return DialogueLine.of("...");
        if (pool.size() == 1) return pool.get(0);
        RandomSource rng = ctx.speaker() != null
                ? ctx.speaker().getRandom() : RandomSource.create();
        DialogueRecencyCache cache = DialogueRecencyCache.INSTANCE;
        // Weight: 1.0 baseline, 0.25 if recently spoken.
        float[] weights = new float[pool.size()];
        float total = 0f;
        java.util.UUID speakerId = ctx.speaker() == null ? null : ctx.speaker().getUUID();
        for (int i = 0; i < pool.size(); i++) {
            String text = pool.get(i).text();
            float w = 1f;
            if (speakerId != null && cache.recentlySpoken(speakerId, text)) w = 0.25f;
            weights[i] = w;
            total += w;
        }
        float roll = rng.nextFloat() * total;
        float acc = 0f;
        DialogueLine chosen = pool.get(0);
        for (int i = 0; i < pool.size(); i++) {
            acc += weights[i];
            if (roll <= acc) { chosen = pool.get(i); break; }
        }
        if (speakerId != null) cache.markSpoken(speakerId, chosen.text());
        return chosen;
    }
}
