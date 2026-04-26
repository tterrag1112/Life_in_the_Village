package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry;
import tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Map;
import java.util.Optional;

/**
 * "Tell a rumor" — player passes a rumor to the NPC, who adds it to
 * their knowledge ledger. Spec line 226. Gated on relationship ≥ +20
 * (player→NPC reputation).
 *
 * <p>Phase 2 v1 expects {@code topic} and {@code content} string args
 * supplied by the GUI's rumor-picker menu; without them the verb
 * returns a failure prompting the player to choose. Free-form text is
 * intentionally out of scope (spec "Open decisions" line 350).</p>
 */
public final class TellRumorVerb implements PlayerVerb {

    public static final String VERB_ID = "tell_rumor";
    /** Spec line 281: relationship ≥ +20 (player→NPC personal delta). */
    public static final int MIN_RELATIONSHIP = 20;
    /** Per-witness fidelity stamped on player-told rumors. */
    public static final float TOLD_FIDELITY = 0.7f;

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Tell a rumor"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Share a rumor with this person."));
    }
    @Override public int displayOrder() { return 55; }
    @Override public int cooldownTicks() { return 600; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getRelationships().getDelta(ctx.player().getUUID())
                >= MIN_RELATIONSHIP;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.failure("Pick a rumor to tell from the menu.");
    }

    @Override
    public VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        String topic = args == null ? null : args.get("topic");
        if (topic == null || topic.isEmpty()) {
            return VerbResult.failure("Pick a rumor to tell from the menu.");
        }
        String content = args.getOrDefault("content", "");
        KnowledgeEntry entry = KnowledgeEntry.create(
                topic,
                KnowledgeCategory.PERSONAL,
                TOLD_FIDELITY,
                KnowledgeSource.RUMOR_HEARD,
                ctx.tick(),
                content,
                ctx.player().getUUID());
        ctx.npc().getKnowledge().add(entry);
        return VerbResult.success("rumor.told.default");
    }
}
