package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

/**
 * Phase 5b — right-clicking <em>any</em> block of a {@link MarketStall}'s
 * footprint opens it (the sign-only entry point proved too fiddly once the
 * stall template switched to a hanging sign, whose right-click is captured
 * by the vanilla sign editor). Routes:
 * <ul>
 *   <li><b>Owner</b> (PLAYER owner, matching UUID) → stall management.</li>
 *   <li><b>Non-owner</b> of a PLAYER stall → the trade screen for the
 *       stall's wares (no NPC needed).</li>
 *   <li><b>NPC-owned</b> stall → the NPC trade screen (the manning merchant
 *       handles pricing). Vacant → a "for rent" hint.</li>
 * </ul>
 * <b>Sneak-right-click bypasses</b> the intercept so the owner can still
 * open the stall chest, edit the sign, or place blocks inside the footprint.
 * Blocks outside any stall footprint are untouched.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class StallSignInteractionHandler {

    private StallSignInteractionHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return; // server-side only
        if (!(player.level() instanceof ServerLevel level)) return;
        if (player.isShiftKeyDown()) return; // sneak bypasses → vanilla block use

        BlockPos pos = event.getPos();
        VillageSavedData data = VillageSavedData.get(level);
        MarketStall stall = data.getStallContaining(pos).orElse(null);
        if (stall == null) return; // not a stall block — vanilla behaviour proceeds

        // It's a stall: cancel the vanilla interaction (sign edit / block use)
        // and route to the stall screen.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        routeStallInteraction(player, level, data, stall);
    }

    private static void routeStallInteraction(ServerPlayer player, ServerLevel level,
                                              VillageSavedData data, MarketStall stall) {
        if (stall.isVacant()) {
            player.displayClientMessage(Component.literal(
                    "This stall is for rent — speak to the market merchant to lease it."),
                    false);
            return;
        }

        switch (stall.getOwnerType()) {
            case PLAYER -> {
                if (stall.getOwnerUUID().equals(player.getUUID())) {
                    TradeHandler.openStallManagement(player, stall);   // owner → manage
                } else {
                    TradeHandler.openTradeScreenForStall(player, stall); // visitor → trade
                }
            }
            case NPC, WANDERING_TRADER -> {
                // NPC-owned stall: route to the manning merchant's trade screen
                // (the existing NPC trade path; stall-keyed trade is PLAYER-only).
                var merchant = level.getEntity(stall.getOwnerUUID());
                if (merchant instanceof tterrag1112.life_in_the_village.Entities.custom
                        .TownspersonMob mob) {
                    TradeHandler.openTradeScreen(player, mob);
                } else {
                    player.displayClientMessage(Component.literal(
                            "The stallholder isn't here right now."), false);
                }
            }
        }
    }
}
