package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasOcean;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasRegion;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

/**
 * Debug commands for inspecting the World Atlas. B2.7 consolidated
 * the previously-split {@code /atlas} (here / stats / sample) and
 * {@code /liv atlas region} trees into a single
 * {@code /liv atlas <subcommand>} surface.
 *
 * <h3>Subcommands</h3>
 * <ul>
 *   <li>{@code /liv atlas here} — show the cell at the player's
 *       current position.</li>
 *   <li>{@code /liv atlas stats} — total filled cells in this
 *       dimension.</li>
 *   <li>{@code /liv atlas sample} — force-sample the cell at the
 *       player and report it.</li>
 *   <li>{@code /liv atlas region [&lt;radius&gt;]} — bare form
 *       reports region/ocean info at the player; with {@code radius}
 *       counts cells within {@code radius} blocks of the player.</li>
 * </ul>
 *
 * <p>Registered through {@link tterrag1112.life_in_the_village.Events.ModModEvents}
 * to align with the rest of the command surface (the legacy
 * {@code @EventBusSubscriber} self-registration was dropped during
 * B2.7's command consolidation).</p>
 */
public final class AtlasDebugCommand {

    private AtlasDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liv")
                .then(Commands.literal("atlas")
                        .executes(AtlasDebugCommand::help)
                        .then(Commands.literal("here")
                                .executes(AtlasDebugCommand::here))
                        .then(Commands.literal("stats")
                                .executes(AtlasDebugCommand::stats))
                        .then(Commands.literal("sample")
                                .executes(AtlasDebugCommand::sample))
                        .then(Commands.literal("region")
                                .executes(AtlasDebugCommand::regionAtPlayer)
                                .then(Commands.argument("radius",
                                                IntegerArgumentType.integer(64, 8192))
                                        .executes(ctx -> regionWithinRadius(ctx,
                                                IntegerArgumentType.getInteger(ctx, "radius")))))));
    }

    // =========================================================================
    // Bare-invocation help
    // =========================================================================

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "/liv atlas — World Atlas inspection\n"
                        + "  here              show cell at player position\n"
                        + "  stats             total filled cells\n"
                        + "  sample            force-sample cell here\n"
                        + "  region            region/ocean at player\n"
                        + "  region <radius>   tally cells within radius (64-8192)"),
                false);
        return 1;
    }

    // =========================================================================
    // Subcommand handlers
    // =========================================================================

    private static int here(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ServerLevel level = (ServerLevel) p.level();
        WorldAtlas atlas = WorldAtlas.get(level);

        AtlasCell cell = atlas.getCellAtBlock(
                p.blockPosition().getX(), p.blockPosition().getZ());

        if (cell == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§eNo atlas cell here yet — try /liv atlas sample"), false);
            return 0;
        }
        sendCell(ctx, cell);
        return 1;
    }

    private static int sample(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ServerLevel level = (ServerLevel) p.level();
        WorldAtlas atlas = WorldAtlas.get(level);

        long startNanos = System.nanoTime();
        AtlasCell cell = atlas.ensureCell(level,
                p.blockPosition().getX(), p.blockPosition().getZ());
        long elapsed = (System.nanoTime() - startNanos) / 1000;

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aSampled in " + elapsed + " µs:"), false);
        sendCell(ctx, cell);
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ServerLevel level = (ServerLevel) p.level();
        WorldAtlas atlas = WorldAtlas.get(level);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§b=== World Atlas Stats ===\n"
                        + "§7Dimension: §f" + level.dimension().registry() + "\n"
                        + "§7Filled cells: §f" + atlas.size() + "\n"
                        + "§7Cell size: §f" + AtlasCell.CELL_SIZE + " blocks"
        ), false);
        return 1;
    }

    /** Reports region / ocean info at the player's current position
     *  (folds in the legacy {@code AtlasRegionDebugCommand}). */
    private static int regionAtPlayer(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Must be run by a player"));
            return 0;
        }
        ServerLevel level = player.level();
        WorldAtlas atlas = WorldAtlas.get(level);

        int bx = player.blockPosition().getX();
        int bz = player.blockPosition().getZ();
        int cx = WorldAtlas.blockToCell(bx);
        int cz = WorldAtlas.blockToCell(bz);

        atlas.ensureRegionFilled(level, bx, bz, 800, 50_000_000L);
        atlas.ensureRegionsIndexed(cx, cz, 20);

        AtlasCell cell = atlas.getCellByCoord(cx, cz);
        if (cell == null) {
            src.sendFailure(Component.literal("Cell not sampled at " + cx + "," + cz));
            return 0;
        }

        AtlasRegion region = atlas.getRegionAt(cx, cz);
        AtlasOcean  ocean  = atlas.getOceanAt(cx, cz);

        StringBuilder sb = new StringBuilder();
        sb.append("Cell (").append(cx).append(",").append(cz).append(") ")
                .append(cell.category()).append("\n");
        if (region != null) {
            sb.append("Region #").append(region.id())
                    .append(" (").append(region.category()).append("), ")
                    .append(region.size()).append(" cells");
        } else if (ocean != null) {
            sb.append("Ocean #").append(ocean.id())
                    .append(", ").append(ocean.size()).append(" cells");
        } else {
            sb.append("Not indexed (no region or ocean)");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    /** Tallies cells in a radius (legacy {@code /atlas region <r>} form). */
    private static int regionWithinRadius(CommandContext<CommandSourceStack> ctx,
                                          int radius) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ServerLevel level = (ServerLevel) p.level();
        WorldAtlas atlas = WorldAtlas.get(level);

        var nearby = atlas.cellsWithinRadius(
                p.blockPosition().getX(),
                p.blockPosition().getZ(),
                radius);

        java.util.EnumMap<BiomeCategory, Integer> tally
                = new java.util.EnumMap<>(BiomeCategory.class);
        int coast = 0, river = 0, steep = 0;
        for (AtlasCell c : nearby) {
            tally.merge(c.category(), 1, Integer::sum);
            if (c.isCoast())    coast++;
            if (c.isRiverAdj()) river++;
            if (c.isSteep())    steep++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§b=== Atlas region within ").append(radius)
                .append(" blocks ===\n");
        sb.append("§7Cells: §f").append(nearby.size()).append("\n");
        sb.append("§7Coast: §f").append(coast)
                .append("  §7River-adj: §f").append(river)
                .append("  §7Steep: §f").append(steep).append("\n");
        sb.append("§7Categories:\n");
        tally.forEach((cat, count) ->
                sb.append("  §f").append(cat.name())
                        .append(" §7× §f").append(count).append("\n"));

        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return nearby.size();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void sendCell(CommandContext<CommandSourceStack> ctx, AtlasCell c) {
        StringBuilder flags = new StringBuilder();
        if (c.isCoast())      flags.append(" COAST");
        if (c.isRiverAdj())   flags.append(" RIVER_ADJ");
        if (c.isFreshwater()) flags.append(" FRESH");
        if (c.isSteep())      flags.append(" STEEP");
        if (c.has(AtlasCell.FLAG_HAS_RIVER)) flags.append(" HAS_RIVER");
        if (c.has(AtlasCell.FLAG_HAS_OCEAN)) flags.append(" HAS_OCEAN");
        if (c.has(AtlasCell.FLAG_HAS_BEACH)) flags.append(" HAS_BEACH");
        if (flags.length() == 0) flags.append(" (none)");

        String msg = "§b=== Atlas Cell ===\n"
                + "§7Coord: §f(" + c.cellX() + ", " + c.cellZ() + ")\n"
                + "§7Block centre: §f(" + c.blockCenterX()
                + ", " + c.centerY() + ", " + c.blockCenterZ() + ")\n"
                + "§7Y: §f" + c.centerY()
                + " §7(min " + c.minY() + ", max " + c.maxY()
                + ", slope " + c.slope() + ")\n"
                + "§7Biome: §f" + c.biomeKey() + "\n"
                + "§7Category: §f" + c.category() + "\n"
                + "§7Flags:§f" + flags;

        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
