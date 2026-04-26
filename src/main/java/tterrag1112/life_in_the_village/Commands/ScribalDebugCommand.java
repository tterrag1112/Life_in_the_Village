package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.AuthorStatus;
import tterrag1112.life_in_the_village.Npc.Scribal.BookRecord;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue;
import tterrag1112.life_in_the_village.Npc.Scribal.LibraryCatalogue;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeCommission;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2 task 17 debug commands. Three Brigadier roots — one each
 * for the scribe, library, and scholar surfaces.
 */
public final class ScribalDebugCommand {

    private ScribalDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /scribe commissions <npc>
        dispatcher.register(Commands.literal("scribe")
                .then(Commands.literal("commissions")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(ScribalDebugCommand::handleScribeCommissions))));

        // /library catalogue <building>
        dispatcher.register(Commands.literal("library")
                .then(Commands.literal("catalogue")
                        .then(Commands.argument("buildingId", UuidArgument.uuid())
                                .executes(ScribalDebugCommand::handleLibraryCatalogue)))
                .then(Commands.literal("catalogue-by-name")
                        .then(Commands.argument("buildingName", StringArgumentType.greedyString())
                                .executes(ScribalDebugCommand::handleLibraryCatalogueByName))));

        // /scholar books <npc>
        dispatcher.register(Commands.literal("scholar")
                .then(Commands.literal("books")
                        .then(Commands.argument("uuid", UuidArgument.uuid())
                                .executes(ScribalDebugCommand::handleScholarBooks))));
    }

    // =========================================================================
    // /scribe commissions <npc>
    // =========================================================================

    private static int handleScribeCommissions(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        ServerLevel level = src.getLevel();
        TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC with UUID " + id));
            return 0;
        }
        UUID workshopId = npc.getAssignedBuildingId().orElse(null);
        if (workshopId == null) {
            src.sendFailure(Component.literal(npc.getNpcName() + " has no workshop."));
            return 0;
        }
        CommissionQueue queue = VillageSavedData.get(level).getOrCreateCommissionQueue(workshopId);
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Commissions for ").append(npc.getNpcName())
                .append(" §7(").append(queue.size()).append(" total, ")
                .append(queue.pendingCount()).append(" pending)§e ===\n");
        if (queue.isEmpty()) {
            sb.append("  §7(empty)\n");
        } else {
            for (ScribeCommission c : queue.all()) {
                sb.append(String.format(Locale.ROOT,
                        "  %-12s %-10s prio=%d fee=%db client=%s%n    \"%s\"%n",
                        c.product().name(), c.status().name(),
                        c.priority(), c.bronzeFee(),
                        c.clientId().toString().substring(0, 8),
                        truncate(c.content(), 80)));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // /library catalogue <building>
    // =========================================================================

    private static int handleLibraryCatalogue(CommandContext<CommandSourceStack> ctx) {
        UUID id = UuidArgument.getUuid(ctx, "buildingId");
        return printCatalogue(ctx.getSource(), id);
    }

    private static int handleLibraryCatalogueByName(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "buildingName");
        ServerLevel level = ctx.getSource().getLevel();
        Optional<Building> b = VillageSavedData.get(level).getBuildingByName(name);
        if (b.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No building named '" + name + "'."));
            return 0;
        }
        return printCatalogue(ctx.getSource(), b.get().getId());
    }

    private static int printCatalogue(CommandSourceStack src, UUID buildingId) {
        ServerLevel level = src.getLevel();
        Optional<LibraryCatalogue> cat = VillageSavedData.get(level).getLibraryCatalogue(buildingId);
        if (cat.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No catalogue for building " + buildingId), false);
            return 1;
        }
        LibraryCatalogue c = cat.get();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Library catalogue ").append(buildingId.toString(), 0, 8)
                .append(" §7(").append(c.size()).append(" books, ")
                .append(c.activeLoans().size()).append(" loans)§e ===\n");
        for (BookRecord book : c.all()) {
            sb.append(String.format(Locale.ROOT,
                    "  %-30s by %-20s pages=%d copies=%d%n",
                    truncate(book.title(), 30), truncate(book.author(), 20),
                    book.pageCount(), book.copyCount()));
        }
        if (!c.activeLoans().isEmpty()) {
            sb.append("§6Active loans:§r\n");
            c.activeLoans().values().forEach(l ->
                    sb.append(String.format(Locale.ROOT,
                            "  book=%s borrower=%s due=%d%n",
                            l.bookId().toString().substring(0, 8),
                            l.borrowerId().toString().substring(0, 8),
                            l.dueTick())));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // /scholar books <npc>
    // =========================================================================

    private static int handleScholarBooks(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID id = UuidArgument.getUuid(ctx, "uuid");
        ServerLevel level = src.getLevel();
        TownspersonMob npc = TownspersonMob.findByUUID(level, id).orElse(null);
        if (npc == null) {
            src.sendFailure(Component.literal("No NPC with UUID " + id));
            return 0;
        }
        AuthorStatus author = npc.getAuthorStatus();
        var prog = npc.getScholarProgress();
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Scholar status: ").append(npc.getNpcName()).append(" §e===\n");
        sb.append("§6Author:§r ").append(author.isAuthor() ? "yes" : "no")
                .append(author.isRenowned() ? " §6[renowned]§r" : "").append("\n");
        sb.append("§6Prestige:§r ").append(author.prestige())
                .append(" / ").append(AuthorStatus.MAX_PRESTIGE).append("\n");
        sb.append("§6Published books:§r ").append(author.publishedCount()).append("\n");
        for (UUID bookId : author.publishedBookIds()) {
            sb.append("  ").append(bookId).append("\n");
        }
        sb.append("§6Research progress:§r ").append(prog.researchPoints())
                .append(" / ").append(tterrag1112.life_in_the_village.Npc.Scribal
                        .ScholarProgress.PUBLISH_THRESHOLD).append("\n");
        sb.append("§6Current topic:§r ").append(prog.currentTopic().isEmpty()
                ? "§7(none)" : prog.currentTopic()).append("\n");
        sb.append("§6Topics covered:§r ").append(prog.coveredTopics().size()).append("\n");
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
