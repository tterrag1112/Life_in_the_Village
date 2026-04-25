package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * "Ask about their life" — spec lines 108-125. Opens the
 * {@code ask_life.player.default} tree which surfaces the NPC's
 * primary goal via the {@code {goal.primary.narrative}} placeholder.
 * Eligibility is gated at relationship ≥ +10 for the full reveal;
 * the dialogue tree itself further branches on relationship and
 * Honesty for vague vs. revealing variants.
 */
public final class AskAboutLifeVerb implements PlayerVerb {

    public static final String VERB_ID = "ask_life";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Ask about their life"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Learn what they're working toward."));
    }
    @Override public int displayOrder() { return 30; }
    @Override public int cooldownTicks() { return 24000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        // Vague answers below +10 are still useful — keep verb available
        // and let the tree surface the dodge.
        return ctx.npc().getRelationships().getDelta(ctx.player().getUUID()) >= -10;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return VerbResult.success("ask_life.player.default");
    }
}
