// New file: src/main/java/tterrag1112/life_in_the_village/Commands/FarmCommands.java

package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.PlayerWorkplace;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Economy.BusinessPurchaseManager;
import tterrag1112.life_in_the_village.Village.Economy.FarmBusinessLevel;

import java.util.List;

public class FarmCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("farm")
                .then(Commands.literal("accept")
                        .executes(FarmCommands::acceptInheritance))
                .then(Commands.literal("decline")
                        .executes(FarmCommands::declineInheritance))
                .then(Commands.literal("info")
                        .executes(FarmCommands::showFarmInfo))
                .then(Commands.literal("manage")
                        .executes(FarmCommands::openManagement))
        );
    }

    private static int acceptInheritance(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = ctx.getSource().getLevel();

        BusinessPurchaseManager.acceptInheritance(player, level);
        return 1;
    }

    private static int declineInheritance(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;

        String farmhouseIdStr = player.getPersistentData().getString("PendingFarmInheritance").get();
        if (farmhouseIdStr.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "You have no pending farm inheritance."), false);
            return 0;
        }

        player.getPersistentData().remove("PendingFarmInheritance");
        player.displayClientMessage(Component.literal(
                "You have declined the farm inheritance."), false);

        return 1;
    }

    private static int showFarmInfo(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData data = VillageSavedData.get(level);
        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        PlayerWorkplace.WorkplaceEntry workplace = profData.getWorkplace(PlayerProfession.FARMER)
                .orElse(null);

        if (workplace == null) {
            player.displayClientMessage(Component.literal(
                    "You are not employed at any farm."), false);
            return 0;
        }

        Building farmhouse = data.getBuildingById(workplace.buildingId()).orElse(null);
        if (farmhouse == null) {
            player.displayClientMessage(Component.literal(
                    "Farm building not found."), false);
            return 0;
        }

        FarmBusinessLevel businessLevel = data.getOrCreateFarmBusinessLevel(workplace.buildingId());
        businessLevel.updateMetrics(level);

        player.displayClientMessage(Component.literal(
                        "=== " + farmhouse.getName() + " ===")
                .withStyle(net.minecraft.ChatFormatting.GOLD), false);

        player.displayClientMessage(Component.literal(
                        "Role: " + (workplace.isOwner() ? "Owner" : "Farmhand"))
                .withStyle(net.minecraft.ChatFormatting.YELLOW), false);

        player.displayClientMessage(Component.literal(
                        "Business Level: " + businessLevel.getBusinessLevelName() +
                                " (Level " + businessLevel.getBusinessLevel() + ")")
                .withStyle(net.minecraft.ChatFormatting.GREEN), false);

        player.displayClientMessage(Component.literal(
                        "Total Revenue: " + businessLevel.getTotalRevenue() + " bronze")
                .withStyle(net.minecraft.ChatFormatting.AQUA), false);

        player.displayClientMessage(Component.literal(
                        "Weekly Revenue: " + businessLevel.getWeeklyRevenue() + " bronze")
                .withStyle(net.minecraft.ChatFormatting.AQUA), false);

        player.displayClientMessage(Component.literal(
                        "Farm Plots: " + businessLevel.getPlotCount())
                .withStyle(net.minecraft.ChatFormatting.WHITE), false);

        player.displayClientMessage(Component.literal(
                        "Farmhands: " + businessLevel.getFarmhandCount())
                .withStyle(net.minecraft.ChatFormatting.WHITE), false);

        player.displayClientMessage(Component.literal(
                        "Quality Rating: " + String.format("%.1f%%", businessLevel.getQualityRating() * 100))
                .withStyle(net.minecraft.ChatFormatting.WHITE), false);

        // Show plots
        List<FarmPlot> plots = data.getFarmPlotsForFarmhouse(workplace.buildingId());
        if (!plots.isEmpty()) {
            player.displayClientMessage(Component.literal(
                    "Plots:"), false);
            for (FarmPlot plot : plots) {
                player.displayClientMessage(Component.literal(
                        "  - " + plot.getName() + " (" + plot.getSubtype() + ", " +
                                plot.getCropType() + ")"), false);
            }
        }

        return 1;
    }

    private static int openManagement(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;

        PlayerProfessionData profData = player.getData(ModData.PROFESSION_DATA);

        if (!profData.getWorkplace(PlayerProfession.FARMER)
                .map(PlayerWorkplace.WorkplaceEntry::isOwner)
                .orElse(false)) {
            player.displayClientMessage(Component.literal(
                    "Only farm owners can access management features."), false);
            return 0;
        }

        player.displayClientMessage(Component.literal(
                "Farm management GUI will be implemented in the future."), false);
        player.displayClientMessage(Component.literal(
                "For now, use /farm info to view your farm details."), false);

        return 1;
    }
}