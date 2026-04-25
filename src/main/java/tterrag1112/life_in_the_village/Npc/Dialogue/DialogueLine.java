package tterrag1112.life_in_the_village.Npc.Dialogue;

import java.util.List;

/**
 * One spoken line plus its side effects and optional player follow-up
 * options. Spec: {@code 08-dialogue-tree.md} (line 47).
 *
 * <p>{@code text} carries {@code {placeholder}} tokens resolved by
 * {@link PlaceholderResolver} after the walker selects this line.
 * Effects are applied in declaration order (locked Phase 1 decision —
 * documented in 08 Revision Notes).</p>
 */
public record DialogueLine(String text,
                           List<DialogueEffect> effects,
                           List<DialogueOption> options) {

    public DialogueLine {
        if (text == null) text = "";
        if (effects == null) effects = List.of();
        else effects = List.copyOf(effects);
        if (options == null) options = List.of();
        else options = List.copyOf(options);
    }

    /** Convenience: line with no effects and no follow-up options. */
    public static DialogueLine of(String text) {
        return new DialogueLine(text, List.of(), List.of());
    }

    public static DialogueLine of(String text, List<DialogueEffect> effects) {
        return new DialogueLine(text, effects, List.of());
    }
}
