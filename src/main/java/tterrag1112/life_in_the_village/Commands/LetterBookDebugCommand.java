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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Components.ModDataComponents;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Letters.BookCategory;
import tterrag1112.life_in_the_village.Npc.Letters.BookEffects;
import tterrag1112.life_in_the_village.Npc.Letters.ExtendedBookContent;
import tterrag1112.life_in_the_village.Npc.Letters.LetterContent;
import tterrag1112.life_in_the_village.Npc.Letters.LetterDelivery;
import tterrag1112.life_in_the_village.Npc.Letters.LetterSpecial;
import tterrag1112.life_in_the_village.Npc.Letters.ProceduralBookFactory;
import tterrag1112.life_in_the_village.Npc.Letters.StarterTextbookLibrary;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2 task 18 debug commands. Two Brigadier roots: {@code /letter}
 * and {@code /book}.
 */
public final class LetterBookDebugCommand {

    private LetterBookDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("letter")
                // /letter create <author> <recipient> <special> <body>
                .then(Commands.literal("create")
                        .then(Commands.argument("author", UuidArgument.uuid())
                                .then(Commands.argument("recipient", UuidArgument.uuid())
                                        .then(Commands.argument("special", StringArgumentType.word())
                                                .then(Commands.argument("body", StringArgumentType.greedyString())
                                                        .executes(LetterBookDebugCommand::handleLetterCreate))))))
                // /letter deliver <recipient>  (uses letter in player main hand)
                .then(Commands.literal("deliver")
                        .then(Commands.argument("recipient", UuidArgument.uuid())
                                .executes(LetterBookDebugCommand::handleLetterDeliver))));

        dispatcher.register(Commands.literal("book")
                // /book create <author> <category> <title> <topics-csv>
                .then(Commands.literal("create")
                        .then(Commands.argument("author", StringArgumentType.word())
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .then(Commands.argument("title", StringArgumentType.string())
                                                .then(Commands.argument("topics", StringArgumentType.greedyString())
                                                        .executes(LetterBookDebugCommand::handleBookCreate))))))
                // /book read <readerNpc>  (uses book in player main hand)
                .then(Commands.literal("read")
                        .then(Commands.argument("readerNpc", UuidArgument.uuid())
                                .executes(LetterBookDebugCommand::handleBookRead)))
                // /book starter <id>  — mints a starter textbook into the source's inventory
                .then(Commands.literal("starter")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(LetterBookDebugCommand::handleBookStarter)))
                // /book ledger <villageId>  — generates the procedural village ledger
                .then(Commands.literal("ledger")
                        .then(Commands.argument("villageId", UuidArgument.uuid())
                                .executes(LetterBookDebugCommand::handleBookLedger))));
    }

    // =========================================================================
    // /letter create
    // =========================================================================

    private static int handleLetterCreate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID authorId = UuidArgument.getUuid(ctx, "author");
        UUID recipientId = UuidArgument.getUuid(ctx, "recipient");
        String specialStr = StringArgumentType.getString(ctx, "special");
        String body = StringArgumentType.getString(ctx, "body");

        Optional<LetterSpecial> special = parseSpecial(specialStr);
        ServerLevel level = src.getLevel();
        String authorName = TownspersonMob.findByUUID(level, authorId)
                .map(TownspersonMob::getNpcName).orElse("Unknown");
        String recipientName = TownspersonMob.findByUUID(level, recipientId)
                .map(TownspersonMob::getNpcName).orElse("Unknown");

        ItemStack stack = ScribalItems.letter(body,
                authorId, authorName,
                recipientId, recipientName,
                level.getGameTime(), false, special);
        return giveOrDrop(src, stack, "Letter created");
    }

    // =========================================================================
    // /letter deliver <recipient>  — delivers main-hand letter
    // =========================================================================

    private static int handleLetterDeliver(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID recipientId = UuidArgument.getUuid(ctx, "recipient");
        if (!(src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            src.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        ItemStack hand = player.getMainHandItem();
        LetterContent content = hand.get(ModDataComponents.LETTER_CONTENT.get());
        if (content == null) {
            src.sendFailure(Component.literal("You aren't holding a letter."));
            return 0;
        }
        ServerLevel level = src.getLevel();
        TownspersonMob recipient = TownspersonMob.findByUUID(level, recipientId).orElse(null);
        if (recipient == null) {
            src.sendFailure(Component.literal("No NPC with UUID " + recipientId));
            return 0;
        }
        // Force the recipient match by rewriting the content's recipientId
        // when it was unset — saves a /letter create with exact UUIDs.
        if (content.recipientId().equals(LetterContent.NIL_UUID)) {
            LetterContent rewritten = new LetterContent(
                    content.letterId(), content.authorId(), content.authorName(),
                    recipientId, recipient.getNpcName(),
                    content.writtenTick(), content.pages(),
                    content.sealed(), content.sealBroken(), content.sealBrokenBy(),
                    content.special());
            hand.set(ModDataComponents.LETTER_CONTENT.get(), rewritten);
        }
        LetterDelivery.handleHandoff(recipient, player, level, hand);
        return 1;
    }

    // =========================================================================
    // /book create
    // =========================================================================

    private static int handleBookCreate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String author = StringArgumentType.getString(ctx, "author");
        String categoryStr = StringArgumentType.getString(ctx, "category");
        String title = StringArgumentType.getString(ctx, "title");
        String topicsCsv = StringArgumentType.getString(ctx, "topics");

        BookCategory category;
        try { category = BookCategory.valueOf(categoryStr.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown category: " + categoryStr));
            return 0;
        }
        java.util.List<String> topics = topicsCsv.isEmpty()
                ? java.util.List.of()
                : java.util.Arrays.stream(topicsCsv.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();

        ItemStack stack = ScribalItems.book(
                title, author,
                "(authored " + src.getLevel().getGameTime() + ")",
                category, topics, Optional.empty(), Optional.empty(),
                src.getLevel().getGameTime());
        return giveOrDrop(src, stack, "Book created: " + title);
    }

    // =========================================================================
    // /book read <readerNpc>
    // =========================================================================

    private static int handleBookRead(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID readerId = UuidArgument.getUuid(ctx, "readerNpc");
        if (!(src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            src.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }
        ServerLevel level = src.getLevel();
        TownspersonMob reader = TownspersonMob.findByUUID(level, readerId).orElse(null);
        if (reader == null) {
            src.sendFailure(Component.literal("No NPC with UUID " + readerId));
            return 0;
        }
        ItemStack hand = player.getMainHandItem();
        ExtendedBookContent ext = hand.get(ModDataComponents.EXTENDED_BOOK_CONTENT.get());
        if (ext == null) {
            src.sendFailure(Component.literal("Hold an extended book in main hand."));
            return 0;
        }
        BookEffects.ReadOutcome outcome = BookEffects.onBookRead(reader, ext, level);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s read '%s': read=%s partial=%s topics=%d xp=%d buff=%s",
                reader.getNpcName(), ext.title(),
                outcome.read(), outcome.partial(),
                outcome.topicsAdded(), outcome.xpGranted(), outcome.buffApplied()))
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // =========================================================================
    // /book starter <id>
    // =========================================================================

    private static int handleBookStarter(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        Optional<ItemStack> stack = StarterTextbookLibrary.mint(id, src.getLevel().getGameTime());
        if (stack.isEmpty()) {
            src.sendFailure(Component.literal("No starter textbook with id: " + id));
            return 0;
        }
        return giveOrDrop(src, stack.get(), "Starter textbook minted: " + id);
    }

    // =========================================================================
    // /book ledger <villageId>
    // =========================================================================

    private static int handleBookLedger(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        UUID villageId = UuidArgument.getUuid(ctx, "villageId");
        ServerLevel level = src.getLevel();
        Optional<ItemStack> book = ProceduralBookFactory.generateVillageLedger(
                level, villageId, src.getTextName(), null);
        if (book.isEmpty()) {
            src.sendFailure(Component.literal("No village with ID " + villageId));
            return 0;
        }
        return giveOrDrop(src, book.get(), "Village ledger generated.");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int giveOrDrop(CommandSourceStack src, ItemStack stack, String msg) {
        if (src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            if (!player.getInventory().add(stack)) {
                ItemEntity drop = new ItemEntity(src.getLevel(),
                        player.getX(), player.getY() + 0.5, player.getZ(), stack);
                src.getLevel().addFreshEntity(drop);
            }
        } else {
            // No player target — drop at command source position.
            ItemEntity drop = new ItemEntity(src.getLevel(),
                    src.getPosition().x, src.getPosition().y, src.getPosition().z, stack);
            src.getLevel().addFreshEntity(drop);
        }
        src.sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static Optional<LetterSpecial> parseSpecial(String s) {
        if (s == null || s.isEmpty() || s.equalsIgnoreCase("none")) return Optional.empty();
        try { return Optional.of(LetterSpecial.valueOf(s.toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
