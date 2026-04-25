package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Npc.Events.GiftAppropriateness;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * Give-gift verb. Spec lines 151-173. Reads the player's main-hand
 * item, computes appropriateness, fires the bus event (memory + mood
 * + relationship producers handle the rest), then opens an accept /
 * reject dialogue tree.
 *
 * <p>Phase 1 appropriateness heuristic: every gift is APPROPRIATE.
 * Spec line 173 explicitly defers per-culture/profession/trait
 * preference tables to the Phase 5 content pass; a TODO is recorded
 * in {@code 09-player-verbs.md} Revision Notes.</p>
 */
public final class GiveGiftVerb implements PlayerVerb {

    public static final String VERB_ID = "give_gift";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Give gift (held item)"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Offer the item in your main hand."));
    }
    @Override public int displayOrder() { return 20; }
    @Override public int cooldownTicks() { return 24000; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        ItemStack held = ctx.player().getItemInHand(InteractionHand.MAIN_HAND);
        return held != null && !held.isEmpty();
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        ItemStack held = ctx.player().getItemInHand(InteractionHand.MAIN_HAND);
        if (held == null || held.isEmpty()) {
            return VerbResult.failure("No item in your main hand.");
        }
        GiftAppropriateness fit = appropriatenessOf(held, ctx);

        // Fire bus event — MemoryProducer / MoodProducer pick it up.
        NpcLifeEventBus.fire(new NpcLifeEvent.GiftReceived(
                ctx.npc(), ctx.player().getUUID(), true, held.copy(), fit));

        // Spec relationship deltas (line 161-165).
        int relDelta = switch (fit) {
            case FAVORITE -> 15;
            case APPROPRIATE -> 8;
            case OFF_BASE -> 4;
            case INSULTING -> -10;
        };
        ctx.npc().getRelationships().adjust(ctx.player().getUUID(), relDelta);

        // Consume one item from the player's stack.
        held.shrink(1);

        String tree = fit == GiftAppropriateness.INSULTING
                ? "insult.response.default"
                : "accept_help.player.default";
        return VerbResult.success(tree);
    }

    /**
     * Phase 1 stub: every gift is APPROPRIATE. Phase 5 content pass
     * fills in per-culture / profession / trait tables. Documented.
     */
    private static GiftAppropriateness appropriatenessOf(ItemStack stack, VerbContext ctx) {
        return GiftAppropriateness.APPROPRIATE;
    }
}
