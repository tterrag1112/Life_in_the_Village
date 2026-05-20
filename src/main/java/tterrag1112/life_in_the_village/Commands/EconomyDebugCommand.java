package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /economy} debug commands per spec line 246. Three subcommands:
 *
 * <ul>
 *   <li>{@code /economy quote <actor> <buy|sell> <item> <qty> [urgency]}
 *       — print all channel quotes for an intent, scored, descending.</li>
 *   <li>{@code /economy channels} — list every registered channel and
 *       its current availability per loaded village.</li>
 *   <li>{@code /economy migrate-status} — print which legacy trade
 *       callers were migrated this session.</li>
 * </ul>
 */
public final class EconomyDebugCommand {

    private EconomyDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("economy")
                .then(Commands.literal("quote")
                        .then(Commands.argument("actor", UuidArgument.uuid())
                                .then(Commands.argument("direction", StringArgumentType.word())
                                        .suggests((c, b) -> { b.suggest("buy"); b.suggest("sell"); return b.buildFuture(); })
                                        .then(Commands.argument("item", StringArgumentType.string())
                                                .then(Commands.argument("qty", IntegerArgumentType.integer(1, 1000))
                                                        .executes(EconomyDebugCommand::handleQuote)
                                                        .then(Commands.argument("urgency", StringArgumentType.word())
                                                                .suggests((c, b) -> { b.suggest("immediate"); b.suggest("normal"); b.suggest("patient"); return b.buildFuture(); })
                                                                .executes(EconomyDebugCommand::handleQuote)))))))
                .then(Commands.literal("channels")
                        .executes(EconomyDebugCommand::handleChannels))
                .then(Commands.literal("migrate-status")
                        .executes(EconomyDebugCommand::handleMigrateStatus))
        );
    }

    // ── /economy quote ─────────────────────────────────────────────────────

    private static int handleQuote(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        UUID actorId = UuidArgument.getUuid(ctx, "actor");
        String dir = StringArgumentType.getString(ctx, "direction").toLowerCase(Locale.ROOT);
        String itemId = StringArgumentType.getString(ctx, "item");
        int qty = IntegerArgumentType.getInteger(ctx, "qty");
        String urg = "normal";
        try { urg = StringArgumentType.getString(ctx, "urgency").toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException ignored) { /* optional arg */ }

        Item item = BuiltInRegistries.ITEM.get(Identifier.parse(itemId)).map(h -> h.value()).orElse(null);
        if (item == null) {
            src.sendFailure(Component.literal("Unknown item: " + itemId));
            return 0;
        }

        TownspersonMob actor = TownspersonMob.findByUUID(level, actorId).orElse(null);
        VillageSavedData data = VillageSavedData.get(level);
        Village village = actor == null ? null : actor.getAssignedVillageName()
                .flatMap(data::getVillageByName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal(
                    "Actor " + actorId + " is not assigned to a village (need this for the intent)."));
            return 0;
        }

        Urgency urgency = switch (urg) {
            case "immediate" -> Urgency.IMMEDIATE;
            case "patient"   -> Urgency.PATIENT;
            default          -> Urgency.NORMAL;
        };

        TradeIntent intent = "sell".equals(dir)
                ? TradeIntent.sell(item, qty, actorId,
                        actor.getAssignedBuildingId().orElse(null), village.getId(), 0L, urgency)
                : TradeIntent.buy(item, qty, actorId,
                        actor.getAssignedBuildingId().orElse(null), village.getId(), Long.MAX_VALUE,
                        urgency, Set.of());

        List<ChannelQuote> quotes = ChannelRouter.rankAllChannels(intent, village, data, level);
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Quotes for ").append(intent.shortDescription())
                .append(" §7in ").append(village.getName()).append("§e ===");
        if (quotes.isEmpty()) {
            sb.append("\n  §8(no channel can fill this intent)");
        }
        for (ChannelQuote q : quotes) {
            double score = ChannelRouter.score(q, intent);
            sb.append(String.format(Locale.ROOT,
                    "%n  §a%-15s§r price=§f%4d§r/u qty=§f%d§r travel=§f%dt§r score=§f%.2f",
                    q.channel().name(), q.pricePerUnit(), q.availableQuantity(),
                    q.travelTimeTicks(), score));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /economy channels ──────────────────────────────────────────────────

    private static int handleChannels(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);
        long now = level.getGameTime();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Channels (priority + per-village availability) ===");
        for (EconomicChannel c : ChannelRouter.registeredChannels()) {
            sb.append("\n  §a").append(c.type().name())
                    .append("§7 (priority ").append(c.basePriority()).append(")");
            for (Village v : data.getAllVillages()) {
                boolean avail = c.isAvailable(v, data, level, now);
                sb.append(String.format(Locale.ROOT, "%n      %s%s§7 — %s",
                        avail ? "§2✓ " : "§8✗ ", v.getName(), v.getId()));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /economy migrate-status ────────────────────────────────────────────

    /**
     * Static catalogue of legacy callers migrated to {@link ChannelRouter}.
     * Update this list when a new caller migrates so the command stays
     * informative. The "PHASE-3-MIGRATION-23:" tag in source comments
     * mirrors this table.
     */
    private static final List<String> MIGRATED_CALLERS = List.of(
            "Entities/HouseholdWealthManager.tickHouseholdSpending",
            "Entities/Goals/Profession/Workshop/AbstractWorkstationProductionGoal.executeBuy",
            "Npc/Brain/Behaviors/BuyGoodsBehavior (Phase 6.2.a, migrated from BuyGoodsGoal/BuyFromMarketGoal)"
    );

    private static int handleMigrateStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Trade-caller migration status (Phase 3 / spec doc 23) ===");
        sb.append("\n§7Migrated to ChannelRouter:");
        for (String c : MIGRATED_CALLERS) sb.append("\n  §a✓ §f").append(c);
        sb.append("\n§7Not yet migrated (intentional — own flow per spec line 214):");
        sb.append("\n  §8• CraftingOrderManager / commission flow");
        sb.append("\n  §8• Player ↔ NPC TradeHandler UI (uses MarketChannel pricing internally)");
        src.sendSuccess(() -> Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE), false);
        return 1;
    }
}
