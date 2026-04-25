package tterrag1112.life_in_the_village.Npc.Dialogue;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

/**
 * One-shot driver that runs a tree against a context, fires effects,
 * and returns the resolved line text. Phase 1 sessions are
 * fire-and-forget chat lines per spec line 280.
 *
 * <p>The static {@link #lineFor(TownspersonMob, ServerPlayer, ServerLevel, String)}
 * entry point produces a final string for callers like
 * {@link tterrag1112.life_in_the_village.Entities.NpcDialogue} and
 * {@link tterrag1112.life_in_the_village.Entities.NpcProfileSnapshotBuilder}.</p>
 *
 * <p>Multi-turn option-driven flows are reserved for high-value
 * interactions in later phases; the {@link DialogueLine#options()}
 * field lives on the data model but isn't surfaced through chat in v1.</p>
 */
public final class DialogueRunner {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Hard floor when nothing else works. */
    public static final String SAFETY_FALLBACK = "...";

    private DialogueRunner() {}

    /**
     * Resolves the best tree for {@code treeId} (with the spec's
     * fallback chain), runs the walker, applies effects, and returns
     * placeholder-resolved text. Never throws; on any error returns
     * {@link #SAFETY_FALLBACK}.
     */
    public static String lineFor(TownspersonMob speaker,
                                 ServerPlayer player,
                                 ServerLevel level,
                                 String treeId) {
        try {
            DialogueRegistry.ensureInit();
            DialogueTree tree = DialogueRegistry.lookupWithFallback(treeId);
            DialogueContext ctx = DialogueContext.forPlayer(speaker, player, level);
            DialogueLine line = DialogueWalker.select(tree, ctx);
            // Apply effects (declaration order; END_CONVERSATION is
            // honoured by short-circuiting the rest of this line's effects).
            boolean[] ended = { false };
            EffectDispatcher.apply(line.effects(), ctx, e -> ended[0] = e);
            if (ended[0]) {
                LOGGER.debug("[Dialogue] tree '{}' ended via END_CONVERSATION effect", treeId);
            }
            return PlaceholderResolver.resolve(line.text(), ctx);
        } catch (Throwable t) {
            LOGGER.warn("[Dialogue] lineFor('{}') threw; returning fallback: {}",
                    treeId, t.getMessage());
            return SAFETY_FALLBACK;
        }
    }

    /**
     * Convenience for callers that want to send the line directly to
     * a player. Posts a chat message of the form
     * {@code <speakerName> says: "<line>"}.
     */
    public static void runAndSendChat(TownspersonMob speaker,
                                      ServerPlayer player,
                                      ServerLevel level,
                                      String treeId) {
        String line = lineFor(speaker, player, level, treeId);
        if (player == null) return;
        String name = speaker == null ? "?"
                : (speaker.getCustomName() != null
                        ? speaker.getCustomName().getString()
                        : speaker.getName().getString());
        player.sendSystemMessage(Component.literal("<" + name + "> " + line));
    }
}
