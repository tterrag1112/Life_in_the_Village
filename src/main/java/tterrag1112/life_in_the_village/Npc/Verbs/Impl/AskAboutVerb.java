package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Gossip.RumorFabricator;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "Ask about someone" — spec lines 128-148. Player picks another NPC
 * (passed as the {@code subjectId} parameter); the speaker queries
 * their own knowledge ledger for entries about that subject.
 *
 * <p>Phase 1 simplification: the verb opens the
 * {@code ask_about.other.default} tree without filtering by topic
 * fidelity. Phase 5 content pass refines per-relationship-tone
 * variants (positive / rival / neutral) and adds knowledge-share
 * effects on the resulting line.</p>
 */
public final class AskAboutVerb implements PlayerVerb {

    public static final String VERB_ID = "ask_about";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Ask about…"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Inquire about another person."));
    }
    @Override public int displayOrder() { return 40; }
    @Override public int cooldownTicks() { return 24000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        return ctx.npc().getRelationships().getDelta(ctx.player().getUUID()) >= 0;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.success("ask_about.other.default");
    }

    @Override
    public VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        // Spec line 329 edge case: filter self-target.
        UUID subject = parseUuid(args.get("subjectId"));
        if (subject != null && subject.equals(ctx.npc().getUUID())) {
            return VerbResult.failure("Asking them about themselves doesn't make sense.");
        }
        // Phase 2 task 12: low-honesty / high-sociability NPCs may
        // fabricate a rumor when asked about an unknown topic. Spec
        // {@code 12-gossip-rumor.md} line 187. The fabricated entry
        // lands on the NPC's own ledger and propagates via gossip.
        String topic = args.get("topic");
        if (topic != null && !topic.isEmpty()) {
            RumorFabricator.maybeFabricate(ctx.npc(), topic, subject, ctx.tick());
        }
        return VerbResult.success("ask_about.other.default");
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
