package tterrag1112.life_in_the_village.Npc.Dialogue;

import java.util.List;

/**
 * Sealed node hierarchy for {@link DialogueTree}. Spec:
 * {@code 08-dialogue-tree.md} (line 31).
 *
 * <ul>
 *   <li>{@link Branch} — predicate-driven binary split.</li>
 *   <li>{@link Lines} — leaf node holding a pool of candidate lines;
 *       the walker picks one weighted by recency.</li>
 *   <li>{@link Ref} — pointer into another registered tree, walked
 *       inline. Lets common sub-trees be reused across many parents.</li>
 * </ul>
 */
public sealed interface DialogueNode permits DialogueNode.Branch,
        DialogueNode.Lines,
        DialogueNode.Ref {

    record Branch(DialoguePredicate predicate,
                  DialogueNode trueChild,
                  DialogueNode falseChild) implements DialogueNode {}

    record Lines(List<DialogueLine> pool) implements DialogueNode {
        public Lines {
            if (pool == null || pool.isEmpty()) {
                throw new IllegalArgumentException("DialogueNode.Lines pool must not be empty");
            }
            pool = List.copyOf(pool);
        }
    }

    record Ref(String treeId) implements DialogueNode {}

    // ── Construction helpers ──────────────────────────────────────────────

    static DialogueNode lines(DialogueLine... pool) {
        return new Lines(List.of(pool));
    }

    static DialogueNode lines(String... texts) {
        DialogueLine[] out = new DialogueLine[texts.length];
        for (int i = 0; i < texts.length; i++) out[i] = DialogueLine.of(texts[i]);
        return new Lines(List.of(out));
    }

    static DialogueNode branch(DialoguePredicate p, DialogueNode t, DialogueNode f) {
        return new Branch(p, t, f);
    }

    static DialogueNode ref(String treeId) { return new Ref(treeId); }
}
