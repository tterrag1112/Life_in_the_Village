package tterrag1112.life_in_the_village.Npc.Dialogue;

/**
 * Named, registered dialogue tree. {@code treeId} follows the spec's
 * dotted hierarchy {@code intent.listenerType.role.modifier}; the
 * registry's lookup walks progressively shorter prefixes.
 *
 * <p>Spec: {@code 08-dialogue-tree.md} line 122.</p>
 */
public record DialogueTree(String treeId, DialogueNode root) {
    public DialogueTree {
        if (treeId == null || treeId.isEmpty()) {
            throw new IllegalArgumentException("treeId must not be empty");
        }
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
    }
}
