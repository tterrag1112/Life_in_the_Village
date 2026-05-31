package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.TradeHandler;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;

/**
 * Phase 5b — the stall sign is the single entry point to a stall.
 * Right-clicking a sign that belongs to a {@link MarketStall} cancels the
 * vanilla sign-edit and routes:
 * <ul>
 *   <li><b>Owner</b> (PLAYER owner, matching UUID) → stall management.</li>
 *   <li><b>Non-owner</b> of a PLAYER stall → the trade screen for the
 *       stall's wares (no NPC needed).</li>
 *   <li><b>NPC-owned</b> stall → the NPC trade screen (the manning merchant
 *       handles pricing). Vacant → a "for rent" hint.</li>
 * </ul>
 * Non-stall signs fall through to vanilla behaviour untouched.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class StallSignInteractionHandler {

    private StallSignInteractionHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return; // server-side only
        if (!(player.level() instanceof ServerLevel level)) return;

        BlockPos pos = event.getPos();
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity)) return;

        VillageSavedData data = VillageSavedData.get(level);
        MarketStall stall = data.getStallBySignPos(pos).orElse(null);
        if (stall == null) return; // not a stall sign — vanilla sign edit proceeds

        // It's a stall sign: cancel vanilla edit + our own block-place, and route.
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
