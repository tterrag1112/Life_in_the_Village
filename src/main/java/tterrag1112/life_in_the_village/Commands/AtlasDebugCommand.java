package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

/**
 * Debug commands for inspecting the World Atlas.
 *
 * <h3>Commands</h3>
 * <ul>
 *   <li>{@code /atlas here} — show the cell at the player's current position</li>
 *   <li>{@code /atlas stats} — show total filled cells in this dimension</li>
 *   <li>{@code /atlas sample} — force-sample the cell at the player and report it</li>
 *   <li>{@code /atlas region &lt;radius&gt;} — count and summarise cells within
 *       a radius (in blocks) of the player</li>
 * </ul>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class AtlasDebugCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("atlas")
                        .then(Commands.literal("here").executes(AtlasDebugCommand::here))
                        .then(Commands.literal("stats").executes(AtlasDebugCommand::stats))
                        .then(Commands.literal("sample").executes(AtlasDebugCommand::sample))
                        .then(Commands.literal("region")
                                .then(Commands.argument("radius",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .integer(64, 8192))
                                        .executes(ctx -> region(ctx,
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(ctx, "radius")))))
        );
    }

    // =========================================================================
    // Handlers
    // =========================================================================

    private static int here(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ServerLevel level = (ServerLevel) p.level();
        WorldAtlas atlas = WorldAtlas.get(level);

        AtlasCell cell = atlas.getCellAtBlock(
                p.blockPosition().getX(), p.blockPosition().getZ());

        if (cell == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§eNo atlas cell here yet — try /atlas sample"), false);
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

    private static int region(CommandContext<CommandSourceStack> ctx, int radius) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return 0;
        ServerLevel level = (ServerLevel) p.level();
        WorldAtlas atlas = WorldAtlas.get(level);

        var nearby = atlas.cellsWithinRadius(
                p.blockPosition().getX(),
                p.blockPosition().getZ(),
                radius);

        // Tally categories
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