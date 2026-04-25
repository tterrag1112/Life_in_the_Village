package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Mood.MoodCategory;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * Challenge verb. Spec lines 198-218. Phase 1 stub per spec line 351
 * ("stub in Phase 1 (dialogue + skill roll, no combat mechanic); full
 * duel mini-game in Phase 5"). Eligibility: relationship ≥ −30, NPC
 * Courage &gt; −0.4. Acceptance dialogue surfaces; Phase 5 wires the
 * actual contest mechanics.
 */
public final class ChallengeVerb implements PlayerVerb {

    public static final String VERB_ID = "challenge";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Challenge"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Propose a contest of skill."));
    }
    @Override public int displayOrder() { return 80; }
    @Override public int cooldownTicks() { return 72000; } // 3 in-game days

    @Override
    public boolean isAvailable(VerbContext ctx) {
        int rel = ctx.npc().getRelationships().getDelta(ctx.player().getUUID());
        if (rel < -30) return false;
        return ctx.npc().getTraitVector().get(TraitAxis.COURAGE) > -0.4f;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        // Distressed NPCs decline (spec line 211).
        if (ctx.npc().getMood().category() == MoodCategory.DISTRESSED) {
            return VerbResult.success("farewell.player.distressed");
        }
        // Accept-decline routing falls through to compliment.response.default
        // for now (Phase 5 ships dedicated challenge.accept / challenge.decline
        // trees as part of the content pass).
        return VerbResult.success("compliment.response.default");
    }
}
