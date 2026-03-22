// src/main/java/tterrag1112/life_in_the_village/Commands/OrderCommand.java
package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.CraftingOrderInteraction;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;

import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;


public final class OrderCommand {

    private OrderCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("order")
                .then(Commands.literal("list")
                        .executes(ctx -> handleList(ctx.getSource())))
                .then(Commands.literal("claim")
                        .then(Commands.argument("orderId", UuidArgument.uuid())
                                .executes(ctx -> handleClaim(
                                        ctx.getSource(),
                                        UuidArgument.getUuid(ctx, "orderId")))))
                .then(Commands.literal("cancel")
                        .executes(ctx -> handleCancel(ctx.getSource())))
        );
    }

    // -------------------------------------------------------------------------
    // /order list
    // -------------------------------------------------------------------------

    private static int handleList(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();

        // Find nearest village leader to anchor the list to a village
        TownspersonMob nearestLeader = findNearestLeader(player, level);
        if (nearestLeader == null) {
            player.displayClientMessage(
                    Component.literal("You must be near a village to view its order board."),
                    false);
            return 0;
        }

        CraftingOrderInteraction.showOrderBoard(player, nearestLeader, level);
        return 1;
    }

    // -------------------------------------------------------------------------
    // /order claim <orderId>
    // -------------------------------------------------------------------------

    private static int handleClaim(CommandSourceStack src, UUID orderId) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        boolean claimed = CraftingOrderManager.claimOrder(player, orderId, level);
        return claimed ? 1 : 0;
    }

    // -------------------------------------------------------------------------
    // /order cancel
    // -------------------------------------------------------------------------

    private static int handleCancel(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        // Find and cancel the player's active claimed order in any nearby village
        Optional<Village> nearbyVillage = data.getAllVillages().stream()
                .filter(v -> v.getBounds(data)
                        .map(b -> b.inflate(128).contains(
                                player.getX(), player.getY(), player.getZ()))
                        .orElse(false))
                .findFirst();

        if (nearbyVillage.isEmpty()) {
            player.displayClientMessage(
                    Component.literal("You are not near any village."), false);
            return 0;
        }

        boolean cancelled = data.getOrdersForVillage(nearbyVillage.get().getId())
                .stream()
                .filter(o -> o.isClaimedBy(player.getUUID()))
                .findFirst()
                .map(order -> {
                    order.cancel();
                    data.markDirty();
                    player.displayClientMessage(
                            Component.literal("Order cancelled. The items are no longer required."),
                            false);
                    return true;
                })
                .orElse(false);

        if (!cancelled) {
            player.displayClientMessage(
                    Component.literal("You have no active order to cancel here."), false);
        }

        return cancelled ? 1 : 0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TownspersonMob findNearestLeader(ServerPlayer player,
                                                    ServerLevel level) {
        return level.getEntitiesOfClass(
                        TownspersonMob.class,
                        player.getBoundingBox().inflate(256),
                        npc -> npc.getProfession() == Profession.VILLAGE_LEADER)
                .stream()
                .min((a, b) -> Double.compare(
                        a.distanceTo(player),
                        b.distanceTo(player)))
                .orElse(null);
    }
}