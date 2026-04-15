// src/main/java/tterrag1112/life_in_the_village/Commands/AtlasRegionDebugCommand.java
package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasOcean;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasRegion;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

/**
 * /liv atlas region — reports region/ocean info at the player's position.
 * Triggers a fill + index build around the player if needed.
 */
public class AtlasRegionDebugCommand {
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("liv")
                .then(Commands.literal("atlas")
                        .then(Commands.literal("region")
                                .executes(AtlasRegionDebugCommand::run))));
    }

    private static int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
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

        // Ensure atlas + index are populated here
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
}