package tterrag1112.life_in_the_village.Items;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import tterrag1112.life_in_the_village.Components.ModDataComponents;

import java.util.function.Consumer;

/**
 * Universal hire / commission / apprenticeship / business-recruit contract.
 * Right-clicked on an NPC, the contract's {@link JobContractTerms} drive the
 * dispatcher in {@code NpcInteractionHandler}. Plays no role in air —
 * an in-hand right-click in air just inspects tooltip.
 *
 * <p>Minted by SCRIBE through the ScribeCounter compose flow (hire/
 * apprentice/commission) or by the Business Management GUI
 * (business-recruit via {@code BusinessActionPacket.MINT_HIRE_CONTRACT}).
 * The underlying mint helper for scribe-issued contracts lives in
 * {@link tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems#contract}.</p>
 */
public class JobContractItem extends Item {

    public JobContractItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack,
                                TooltipContext context,
                                TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltipAdder,
                                TooltipFlag flag) {
        JobContractTerms terms = stack.get(ModDataComponents.JOB_CONTRACT_TERMS.get());
        if (terms == null) {
            tooltipAdder.accept(Component.literal("Blank contract")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        // Business-recruit flavor: show business info, not profession
        if (terms.isBusinessRecruit()) {
            tooltipAdder.accept(Component.literal("Business Hire Contract")
                    .withStyle(ChatFormatting.GOLD));
            terms.recruiterRole().ifPresent(role ->
                    tooltipAdder.accept(Component.literal("Role: " + role)
                            .withStyle(ChatFormatting.AQUA)));
            tooltipAdder.accept(Component.literal("Right-click an unemployed villager to hire them.")
                    .withStyle(ChatFormatting.GRAY));
            tooltipAdder.accept(Component.literal("One use — consumed on successful hire.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        tooltipAdder.accept(Component.literal("Profession: " + terms.profession().name())
                .withStyle(ChatFormatting.GOLD));
        if (terms.rank() != JobContractTerms.Rank.NONE) {
            tooltipAdder.accept(Component.literal("Rank: " + terms.rank().name())
                    .withStyle(ChatFormatting.AQUA));
        }
        terms.targetItemId().ifPresent(id ->
                tooltipAdder.accept(Component.literal("Commission: " + id)
                        .withStyle(ChatFormatting.LIGHT_PURPLE)));
        if (terms.salaryBronze() > 0) {
            tooltipAdder.accept(Component.literal("Salary: " + terms.salaryBronze() + " br")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (terms.durationTicks() > 0) {
            long days = terms.durationTicks() / 24000L;
            tooltipAdder.accept(Component.literal("Duration: " + days + " day(s)")
                    .withStyle(ChatFormatting.GRAY));
        }
        terms.masterUuid().ifPresent(uuid ->
                tooltipAdder.accept(Component.literal("Master bound")
                        .withStyle(ChatFormatting.DARK_AQUA)));
    }
}
