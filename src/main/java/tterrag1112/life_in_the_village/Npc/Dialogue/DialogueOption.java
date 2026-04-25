package tterrag1112.life_in_the_village.Npc.Dialogue;

import java.util.List;

/**
 * A clickable / selectable response the player can pick on a
 * {@link DialogueLine}. {@code leadsTo} is either a tree id (run that
 * tree as a sub-walk) or a node id within the same tree.
 *
 * <p>Phase 1 ships dialogue as one-shot greetings via chat (per spec
 * line 280); rich option-driven flows are reserved for specific
 * high-value interactions in later phases.</p>
 */
public record DialogueOption(String label, String leadsTo, List<DialogueEffect> effects) {

    public DialogueOption {
        if (effects == null) effects = List.of();
        else effects = List.copyOf(effects);
    }

    public DialogueOption(String label, String leadsTo) {
        this(label, leadsTo, List.of());
    }
}
