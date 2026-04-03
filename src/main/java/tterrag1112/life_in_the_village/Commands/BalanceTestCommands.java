// New file: src/main/java/tterrag1112/life_in_the_village/Commands/BalanceTestCommands.java

package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Village.Economy.EconomicBalance;

/**
 * Debug commands for testing economic balance
 */
public class BalanceTestCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("farmbalance")
                .then(Commands.literal("calculate")
                        .then(Commands.argument("plots", IntegerArgumentType.integer(1, 20))
                                .then(Commands.argument("farmhands", IntegerArgumentType.integer(0, 10))
                                        .then(Commands.argument("avgYield", IntegerArgumentType.integer(1, 100))
                                                .executes(BalanceTestCommands::calculateBalance)))))
                .then(Commands.literal("suggest")
                        .then(Commands.argument("plots", IntegerArgumentType.integer(1, 20))
                                .executes(BalanceTestCommands::suggestFarmhands)))
        );
    }

    private static int calculateBalance(CommandContext<CommandSourceStack> ctx) {
        int plots = IntegerArgumentType.getInteger(ctx, "plots");
        int farmhands = IntegerArgumentType.getInteger(ctx, "farmhands");
        int avgYield = IntegerArgumentType.getInteger(ctx, "avgYield");

        long revenue = EconomicBalance.calculateExpectedWeeklyRevenue(plots, avgYield);
        long costs = EconomicBalance.calculateExpectedWeeklyCosts(farmhands, plots);
        long profit = revenue - costs;
        double margin = EconomicBalance.calculateProfitMargin(plots, farmhands, avgYield);
        boolean viable = EconomicBalance.isFarmViable(plots, farmhands, avgYield);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "=== Farm Economic Analysis ===\n" +
                        "Plots: " + plots + " | Farmhands: " + farmhands + " | Avg Yield: " + avgYield + "\n" +
                        "Weekly Revenue: " + revenue + " bronze\n" +
                        "Weekly Costs: " + costs + " bronze\n" +
                        "Weekly Profit: " + profit + " bronze\n" +
                        "Profit Margin: " + String.format("%.1f%%", margin) + "\n" +
                        "Viable: " + (viable ? "§aYES" : "§cNO")
        ), false);

        return 1;
    }

    private static int suggestFarmhands(CommandContext<CommandSourceStack> ctx) {
        int plots = IntegerArgumentType.getInteger(ctx, "plots");
        int suggested = EconomicBalance.suggestFarmhandCount(plots);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "For " + plots + " plots, suggested farmhand count: " + suggested
        ), false);

        return 1;
    }
}