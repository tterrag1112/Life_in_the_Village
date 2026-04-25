package tterrag1112.life_in_the_village.Npc.Verbs;

import tterrag1112.life_in_the_village.Npc.Dialogue.DialogueEffect;

import java.util.List;

/**
 * Outcome of a verb invocation. The caller (UI handler or debug
 * command) is responsible for surfacing {@code statusText} to the
 * player and routing the dialogue tree.
 *
 * @param success           did the verb run? false on cooldown / invalid args
 * @param resultTreeId      tree id to open after invocation, or {@code ""}
 * @param additionalEffects extra effects to layer on top of the tree's own
 * @param opensExternalScreen if true, the host should bring up the screen
 *                            named in {@code statusText}
 * @param statusText        short message to display ("On cooldown",
 *                          "No item in hand", etc.)
 */
public record VerbResult(boolean success,
                         String resultTreeId,
                         List<DialogueEffect> additionalEffects,
                         boolean opensExternalScreen,
                         String statusText) {

    public VerbResult {
        if (resultTreeId == null) resultTreeId = "";
        if (additionalEffects == null) additionalEffects = List.of();
        else additionalEffects = List.copyOf(additionalEffects);
        if (statusText == null) statusText = "";
    }

    public static VerbResult success(String treeId) {
        return new VerbResult(true, treeId, List.of(), false, "");
    }

    public static VerbResult success(String treeId, List<DialogueEffect> effects) {
        return new VerbResult(true, treeId, effects, false, "");
    }

    public static VerbResult opensScreen(String screenName) {
        return new VerbResult(true, "", List.of(), true, screenName);
    }

    public static VerbResult failure(String reason) {
        return new VerbResult(false, "", List.of(), false, reason);
    }
}
