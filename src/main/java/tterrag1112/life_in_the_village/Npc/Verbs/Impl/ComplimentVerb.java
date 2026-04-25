package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compliment verb. Spec lines 64-83. The matching-trait map is small
 * for Phase 1; Phase 5 content pass replaces with a richer per-culture
 * mapping. Default topic when none supplied is {@code "kindness"}.
 *
 * <p>Eligibility: relationship ≥ −10. Cooldown: 1 in-game day per
 * (NPC, player) pair (spec line 254).</p>
 */
public final class ComplimentVerb implements PlayerVerb {

    public static final String VERB_ID = "compliment";

    /**
     * Compliment topic → trait axis. Topics absent from this map are
     * "appearance"/"family"/etc. — never trait-matching, always hollow.
     * Documented in {@code 09-player-verbs.md} Revision Notes.
     */
    private static final Map<String, TraitAxis> TOPIC_TRAIT = Map.of(
            "work", TraitAxis.INDUSTRY,
            "craft", TraitAxis.INDUSTRY,
            "bravery", TraitAxis.COURAGE,
            "honor", TraitAxis.HONESTY,
            "honesty", TraitAxis.HONESTY,
            "kindness", TraitAxis.COMPASSION,
            "wit", TraitAxis.SOCIABILITY,
            "patience", TraitAxis.TEMPERANCE,
            "ambition", TraitAxis.AMBITION,
            "generosity", TraitAxis.GENEROSITY);

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Compliment"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Praise their work, bravery, kindness, or wit."));
    }
    @Override public int displayOrder() { return 50; }
    @Override public int cooldownTicks() { return 24000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        // Relationship gate: very-hostile NPCs reject compliments.
        return ctx.npc().getRelationships().getDelta(ctx.player().getUUID()) >= -10;
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        return invoke(ctx, Map.of("topic", "kindness"));
    }

    @Override
    public VerbResult invoke(VerbContext ctx, Map<String, String> args) {
        String topic = args.getOrDefault("topic", "kindness").toLowerCase(Locale.ROOT);
        TraitAxis axis = TOPIC_TRAIT.get(topic);
        boolean matched = axis != null
                && ctx.npc().getTraitVector().get(axis) >= 0.4f;

        // Bus event drives memory + mood producers.
        NpcLifeEventBus.fire(new NpcLifeEvent.Complimented(
                ctx.npc(), ctx.player().getUUID(), true, matched));

        // Small relationship bump per spec table.
        ctx.npc().getRelationships().adjust(ctx.player().getUUID(),
                matched ? 5 : 1);

        return VerbResult.success("compliment.response.default");
    }
}
