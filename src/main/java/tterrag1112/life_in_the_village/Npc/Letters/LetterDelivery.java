package tterrag1112.life_in_the_village.Npc.Letters;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

/**
 * Hand-off handler for player → NPC letter delivery (spec line 122).
 * Called from {@code NpcInteractionHandler} when the player
 * right-clicks an NPC with a {@code WrittenLetterItem} in main hand.
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li>If the letter has no payload, fail open.</li>
 *   <li>Wrong recipient: NPC refuses; if the seal was already broken,
 *       chat-warns the player about the breach.</li>
 *   <li>Right recipient with LITERACY ≥ 30: consume the letter and
 *       run {@link LetterEffects#onLetterReceived}.</li>
 *   <li>Right recipient but illiterate (LITERACY &lt; 30): NPC accepts
 *       the letter into their inventory unread. Spec line 148.</li>
 * </ul>
 */
public final class LetterDelivery {

    public static final int READ_LITERACY_THRESHOLD =
            tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds.READ_LITERACY_THRESHOLD;

    private LetterDelivery() {}

    public static InteractionResult handleHandoff(TownspersonMob npc,
                                                  ServerPlayer player,
                                                  ServerLevel level,
                                                  ItemStack stack) {
        LetterContent content = stack.get(ModDataComponents.LETTER_CONTENT.get());
        if (content == null) return InteractionResult.PASS;

        if (!content.isAddressedTo(npc.getUUID())) {
            // Wrong recipient — NPC refuses. If the player has already
            // broken the seal, warn them.
            if (content.sealBroken()) {
                player.displayClientMessage(
                        Component.literal("[" + npc.getNpcName()
                                        + "] This isn't for me. And the seal is broken.")
                                .withStyle(ChatFormatting.YELLOW), false);
            } else {
                player.displayClientMessage(
                        Component.literal("[" + npc.getNpcName()
                                        + "] That isn't addressed to me.")
                                .withStyle(ChatFormatting.GRAY), false);
            }
            return InteractionResult.SUCCESS;
        }

        // Recipient match — consume one letter from the stack.
        ItemStack consumed = stack.copyWithCount(1);
        stack.shrink(1);

        int literacy = npc.getSkills().getLevel(Skill.LITERACY);
        if (literacy < READ_LITERACY_THRESHOLD) {
            // Illiterate — letter goes to NPC inventory unread.
            // The NPC may later ask a scribe to read it (spec line 144).
            // v1 just stores it via Entity.spawnAtLocation through a
            // dropped-item path; held inventory on TownspersonMob isn't
            // wired for letters yet, so we drop at NPC's feet.
            net.minecraft.world.entity.item.ItemEntity drop =
                    new net.minecraft.world.entity.item.ItemEntity(level,
                            npc.getX(), npc.getY() + 0.5, npc.getZ(), consumed);
            level.addFreshEntity(drop);
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] Thank you. I cannot read it yet — I'll find a scribe.")
                            .withStyle(ChatFormatting.GRAY), false);
            return InteractionResult.SUCCESS;
        }

        // Literate recipient — read immediately and run effects.
        if (content.sealBroken() && content.isUnauthorisedReader(content.sealBrokenBy())) {
            player.displayClientMessage(
                    Component.literal("[" + npc.getNpcName()
                                    + "] The seal has been broken. Someone else read this first.")
                            .withStyle(ChatFormatting.RED), false);
        }
        LetterEffects.onLetterReceived(npc, content, level);
        player.displayClientMessage(
                Component.literal("[" + npc.getNpcName()
                                + "] Thank you. I've read it.")
                        .withStyle(ChatFormatting.GREEN), false);
        return InteractionResult.SUCCESS;
    }
}
