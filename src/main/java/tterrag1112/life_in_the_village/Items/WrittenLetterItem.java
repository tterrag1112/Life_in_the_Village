package tterrag1112.life_in_the_village.Items;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Npc.Letters.LetterContent;

import java.util.List;

/**
 * Sealed letter item — the v1 home for {@link LetterContent}. Spec
 * line 24.
 *
 * <p>Player right-click in air opens the body in chat; right-click
 * on an NPC delivers (handled in
 * {@code NpcInteractionHandler}). Tooltip shows author and recipient
 * names plus a sealed/broken indicator.</p>
 */
public class WrittenLetterItem extends Item {

    public WrittenLetterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        LetterContent content = stack.get(ModDataComponents.LETTER_CONTENT.get());
        if (content == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        // Sealed-letter tampering: opening as a non-recipient flips
        // the seal flag and chat-warns the player.
        if (content.isUnauthorisedReader(player.getUUID())) {
            player.displayClientMessage(
                    Component.literal("You broke the seal on a letter not addressed to you.")
                            .withStyle(ChatFormatting.RED), false);
            stack.set(ModDataComponents.LETTER_CONTENT.get(),
                    content.withBrokenSeal(player.getUUID()));
        }

        // Display the body. v1 shows in chat — Phase 5 polish opens a
        // proper UI panel.
        player.displayClientMessage(
                Component.literal("Letter from ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(content.authorName())
                                .withStyle(ChatFormatting.WHITE))
                        .append(" to ")
                        .append(Component.literal(content.recipientName())
                                .withStyle(ChatFormatting.WHITE)),
                false);
        for (String page : content.pages()) {
            player.displayClientMessage(
                    Component.literal(page).withStyle(ChatFormatting.GRAY), false);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack,
                                TooltipContext context,
                                List<Component> tooltip,
                                TooltipFlag flag) {
        LetterContent content = stack.get(ModDataComponents.LETTER_CONTENT.get());
        if (content == null) return;
        tooltip.add(Component.literal("From: " + content.authorName())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("To: " + content.recipientName())
                .withStyle(ChatFormatting.GRAY));
        if (content.special().isPresent()) {
            tooltip.add(Component.literal("Type: " + content.special().get().name())
                    .withStyle(ChatFormatting.AQUA));
        }
        if (content.sealed() && !content.sealBroken()) {
            tooltip.add(Component.literal("Sealed")
                    .withStyle(ChatFormatting.GOLD));
        } else if (content.sealBroken()) {
            tooltip.add(Component.literal("Seal broken")
                    .withStyle(ChatFormatting.RED));
        }
    }
}
