package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Npc.Letters.LetterContent;
import tterrag1112.life_in_the_village.Npc.Letters.LetterDelivery;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * "Send letter" — convenience verb that hands the letter currently
 * held in main hand to the targeted NPC. Spec line 330.
 *
 * <p>Equivalent to right-click delivery (handled in
 * {@code NpcInteractionHandler}); exists as a verb so the action
 * surfaces in the verb bar without the player remembering the
 * right-click affordance.</p>
 */
public final class SendLetterVerb implements PlayerVerb {

    public static final String VERB_ID = "send_letter";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Send letter"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Hand the letter you're holding to this NPC."));
    }
    @Override public int displayOrder() { return 72; }
    @Override public int cooldownTicks() { return 100; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        ItemStack hand = ctx.player().getItemInHand(InteractionHand.MAIN_HAND);
        return hand.is(ModItems.WRITTEN_LETTER.get());
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        ItemStack hand = ctx.player().getItemInHand(InteractionHand.MAIN_HAND);
        if (!hand.is(ModItems.WRITTEN_LETTER.get())) {
            return VerbResult.failure("You aren't holding a letter.");
        }
        LetterContent content = hand.get(ModDataComponents.LETTER_CONTENT.get());
        if (content == null) {
            return VerbResult.failure("That letter has no content.");
        }
        LetterDelivery.handleHandoff(ctx.npc(), ctx.player(), ctx.level(), hand);
        return VerbResult.success("letter.sent");
    }
}
