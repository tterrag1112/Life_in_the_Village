package tterrag1112.life_in_the_village.Items;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Npc.Religion.GodRegistry;
import tterrag1112.life_in_the_village.Npc.Religion.Sacred.SacredSpaceSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.Saints.Intercession;
import tterrag1112.life_in_the_village.Npc.Religion.Saints.RelicData;

import java.util.function.Consumer;

/**
 * Saints &amp; Relics SR4 — a saint's <b>relic</b>: a real, holdable item carrying a
 * fragment of the saint's holiness ({@link RelicData}). Three interactions:
 * <ul>
 *   <li><b>carried</b> — a small standing with the saint's god (the SR1 {@code
 *       SaintFactor} folds the carried bonus in);</li>
 *   <li><b>right-click (air)</b> — a portable intercession anywhere (reuses {@link
 *       Intercession#prayWithRelic});</li>
 *   <li><b>use on a block</b> — enshrines it there: a permanent consecrated imprint
 *       (reuses the SR2 permanent imprint).</li>
 * </ul>
 * Because it is a true item it can be picked up, given, taken and moved — the
 * "fought over" flavour falls out of its tangibility (no theft AI here).
 */
public class RelicItem extends Item {

    /** An enshrined relic site is strongly sacred (MAJOR-tier), like a saint's grave. */
    private static final float ENSHRINE_STRENGTH = 2.0f;

    public RelicItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltipAdder, TooltipFlag flag) {
        RelicData data = stack.get(ModDataComponents.RELIC_DATA.get());
        if (data == null) {
            tooltipAdder.accept(Component.literal("A featureless relic").withStyle(ChatFormatting.GRAY));
            return;
        }
        String godName = GodRegistry.find(data.godId()).map(g -> g.displayName()).orElse(data.godId());
        tooltipAdder.accept(Component.literal("Relic of St. " + data.saintName())
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltipAdder.accept(Component.literal("Holy to " + godName).withStyle(ChatFormatting.GOLD));
        tooltipAdder.accept(Component.literal("Carry it for the saint's favour; right-click to pray, "
                + "use on a block to enshrine.").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Right-click (air) → portable intercession to the relic's saint. */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        RelicData data = player.getItemInHand(hand).get(ModDataComponents.RELIC_DATA.get());
        if (data == null) return InteractionResult.SUCCESS;
        Intercession.Result r = Intercession.prayWithRelic(sl, sp, data.saintId(), sl.getGameTime());
        sp.displayClientMessage(Component.literal((r.ok() ? "§d" : "§c") + r.message()), false);
        return InteractionResult.SUCCESS;
    }

    /** Use on a block → enshrine the relic (a permanent consecrated imprint there). */
    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl) || !(ctx.getPlayer() instanceof ServerPlayer sp)) {
            return InteractionResult.SUCCESS;
        }
        RelicData data = ctx.getItemInHand().get(ModDataComponents.RELIC_DATA.get());
        if (data == null) return InteractionResult.PASS;
        BlockPos pos = ctx.getClickedPos().above();
        SacredSpaceSavedData.get(sl)
                .addPermanentImprint(data.godId(), pos, sl.getGameTime(), ENSHRINE_STRENGTH);
        // F2a-2 — enshrining advances any ENSHRINE objective for the relic's god.
        tterrag1112.life_in_the_village.Quests.QuestEvents.notify(sp,
                tterrag1112.life_in_the_village.Quests.QuestContext.enshrine(data.godId()));
        sp.displayClientMessage(Component.literal("§dThe relic of St. " + data.saintName()
                + " is enshrined here — this ground is now sacred."), false);
        return InteractionResult.SUCCESS;
    }
}
