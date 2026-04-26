package tterrag1112.life_in_the_village.Npc.Verbs.Impl;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Npc.Letters.BookEffects;
import tterrag1112.life_in_the_village.Npc.Letters.ExtendedBookContent;
import tterrag1112.life_in_the_village.Npc.Verbs.PlayerVerb;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbContext;
import tterrag1112.life_in_the_village.Npc.Verbs.VerbResult;

import java.util.Optional;

/**
 * Forces the targeted NPC to "read" the written book held in the
 * player's main hand and applies {@link BookEffects#onBookRead}.
 * Spec line 331.
 *
 * <p>Phase 2 v1: the book stays in the player's inventory; the NPC
 * simply has the read effects applied. A real "lend then return"
 * loop is the {@code BorrowBookVerb} territory from task 17.</p>
 */
public final class ReadBookVerb implements PlayerVerb {

    public static final String VERB_ID = "read_book";

    @Override public String id() { return VERB_ID; }
    @Override public Component label() { return Component.literal("Read book"); }
    @Override public Optional<Component> tooltip() {
        return Optional.of(Component.literal("Have this NPC read the book in your hand."));
    }
    @Override public int displayOrder() { return 74; }
    @Override public int cooldownTicks() { return 200; }

    @Override
    public boolean isAvailable(VerbContext ctx) {
        ItemStack hand = ctx.player().getItemInHand(InteractionHand.MAIN_HAND);
        return hand.is(Items.WRITTEN_BOOK)
                && hand.has(ModDataComponents.EXTENDED_BOOK_CONTENT.get());
    }

    @Override
    public VerbResult invoke(VerbContext ctx) {
        ItemStack hand = ctx.player().getItemInHand(InteractionHand.MAIN_HAND);
        ExtendedBookContent ext = hand.get(ModDataComponents.EXTENDED_BOOK_CONTENT.get());
        if (ext == null) return VerbResult.failure("This book has no extended content.");
        BookEffects.ReadOutcome outcome = BookEffects.onBookRead(ctx.npc(), ext, ctx.level());
        if (!outcome.read()) {
            ctx.player().displayClientMessage(
                    Component.literal("[" + ctx.npc().getNpcName()
                                    + "] I cannot read this. The script is too difficult.")
                            .withStyle(ChatFormatting.GRAY), false);
            return VerbResult.success("book.read.illiterate");
        }
        ctx.player().displayClientMessage(
                Component.literal("[" + ctx.npc().getNpcName()
                                + "] I've read it. " + outcome.topicsAdded()
                                + " topic(s) learned, " + outcome.xpGranted() + " XP gained"
                                + (outcome.partial() ? " (partial literacy)" : "")
                                + (outcome.buffApplied() ? " §6[buff active]§r" : "") + ".")
                        .withStyle(ChatFormatting.GREEN), false);
        return VerbResult.success("book.read");
    }
}
