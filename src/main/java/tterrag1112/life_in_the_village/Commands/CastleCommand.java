package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleGenerator;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleLayout;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyle;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyleLoader;

/**
 * Test command for spawning castles in-game.
 *
 * Registration — call CastleCommand.register() inside your
 * RegisterCommandsEvent handler:
 *
 *   @SubscribeEvent
 *   public static void onRegisterCommands(RegisterCommandsEvent event) {
 *       CastleCommand.register(event.getDispatcher());
 *   }
 *
 * Commands:
 *
 *   /castle spawn
 *       Spawns a castle at your feet using a random built-in style.
 *
 *   /castle spawn <style>
 *       Spawns a castle using a named style.
 *       Style names: norman, moorish, fantasy, ruin
 *       Or any loaded style by full id: life_in_the_village:norman
 *
 *   /castle spawn <style> <seed>
 *       Spawns with a specific numeric seed — useful for reproducing a layout.
 *
 *   /castle styles
 *       Lists all currently loaded styles from the datapack.
 */
public class CastleCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("castle")

                        // /castle spawn
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawnCastle(ctx, "norman", -1L))

                                // /castle spawn <style>
                                .then(Commands.argument("style", StringArgumentType.word())
                                        .suggests(STYLE_SUGGESTIONS)
                                        .executes(ctx -> spawnCastle(ctx,
                                                StringArgumentType.getString(ctx, "style"), -1L))

                                        // /castle spawn <style> <seed>
                                        .then(Commands.argument("seed",
                                                        com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                                .executes(ctx -> spawnCastle(ctx,
                                                        StringArgumentType.getString(ctx, "style"),
                                                        com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "seed")))
                                        )
                                )
                        )

                        // /castle styles
                        .then(Commands.literal("styles")
                                .executes(CastleCommand::listStyles))
        );
    }

    // -------------------------------------------------------------------------
    // Spawn
    // -------------------------------------------------------------------------

    private static int spawnCastle(CommandContext<CommandSourceStack> ctx,
                                   String styleArg, long seedArg) {
        CommandSourceStack source = ctx.getSource();

        // Must be run by a player
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        ServerLevel level = player.level().getServer().getLevel(Level.OVERWORLD);

        // Resolve style
        CastleStyle style = resolveStyle(styleArg, source);
        if (style == null) return 0;

        // Origin at the player's feet
        BlockPos origin = player.blockPosition();

        // Seed: use provided seed, or mix player pos + world time for variety
        long seed = (seedArg == -1L)
                ? origin.asLong() ^ level.getGameTime()
                : seedArg;
        ChunkPos chunkPos = player.chunkPosition();
        RandomSource rng = RandomSource.create(chunkPos.toLong() ^ style.styleSeed());

        source.sendSuccess(() -> Component.literal(
                        String.format("Generating castle '%s' at %d %d %d (seed: %d)...",
                                styleArg, origin.getX(), origin.getY(), origin.getZ(), seed)),
                true);

        // Generate
        try {
            CastleLayout layout = CastleGenerator.generateWithLayout(level, origin, style, rng);

            // Report what was built
            CastleLayout.BoundingBox bounds = layout.getApproximateBounds();
            source.sendSuccess(() -> Component.literal(
                            String.format("Done. Towers: %d. Bounds: (%d,%d) to (%d,%d). " +
                                            "Has donjon: %s. Has inner ward: %s.",
                                    layout.getOuterTowers().size(),
                                    bounds.min().getX(), bounds.min().getZ(),
                                    bounds.max().getX(), bounds.max().getZ(),
                                    layout.hasDonjon(),
                                    layout.hasInnerWard())),
                    true);
        } catch (Exception e) {
            source.sendFailure(Component.literal(
                    "Castle generation threw an exception: " + e.getMessage() +
                            " — check the server log for the full stack trace."));
            throw e; // rethrow so it appears in log with stack trace
        }

        return 1;
    }

    // -------------------------------------------------------------------------
    // List styles
    // -------------------------------------------------------------------------

    private static int listStyles(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        // Built-in styles always available
        source.sendSuccess(() -> Component.literal("Built-in styles: norman, moorish, fantasy, ruin"),
                false);

        // Datapack-loaded styles
        var loaded = CastleStyleLoader.INSTANCE.getAll();
        if (loaded.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No datapack styles loaded."), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "Datapack styles (" + loaded.size() + "):"), false);
            loaded.keySet().forEach(id ->
                    source.sendSuccess(() -> Component.literal("  " + id), false));
        }

        return 1;
    }

    // -------------------------------------------------------------------------
    // Style resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves a style name to a CastleStyle.
     *
     * Resolution order:
     *   1. Built-in named styles (norman, moorish, fantasy, ruin)
     *   2. Datapack-loaded styles by full ResourceLocation
     *   3. Datapack-loaded styles by short name (assumes your mod's namespace)
     */
    private static CastleStyle resolveStyle(String arg, CommandSourceStack source) {
        // 1. Built-in names
        /*CastleStyle builtin = switch (arg.toLowerCase()) {
            case "norman"  -> CastleStyles.NORMAN;
            case "moorish" -> CastleStyles.MOORISH;
            case "fantasy" -> CastleStyles.FANTASY;
            case "ruin"    -> CastleStyles.RUIN;
            default        -> null;
        };
        if (builtin != null) return builtin;

         */

        // 2. Full ResourceLocation from datapack loader
        try {
            Identifier id = Identifier.parse(arg);
            var found = CastleStyleLoader.INSTANCE.get(id);
            if (found.isPresent()) return found.get();
        } catch (Exception ignored) {}

        // 3. Short name with mod namespace
        var shortFound = CastleStyleLoader.INSTANCE.get(
                Identifier.fromNamespaceAndPath("life_in_the_village", arg));
        if (shortFound.isPresent()) return shortFound.get();

        source.sendFailure(Component.literal(
                "Unknown castle style '" + arg + "'. " +
                        "Use /castle styles to see available styles."));
        return null;
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    private static final SuggestionProvider<CommandSourceStack> STYLE_SUGGESTIONS =
            (ctx, builder) -> {
                // Built-in styles
               /* for (String name : new String[]{"norman", "moorish", "fantasy", "ruin"}) {
                    if (name.startsWith(builder.getRemaining().toLowerCase())) {
                        builder.suggest(name);
                    }
                }

                */
                // Datapack styles
                CastleStyleLoader.INSTANCE.getAll().keySet().forEach(id -> {
                    String full  = id.toString();
                    String short_ = id.getPath();
                    if (full.startsWith(builder.getRemaining())) builder.suggest(full);
                    if (short_.startsWith(builder.getRemaining())) builder.suggest(short_);
                });
                return builder.buildFuture();
            };
}