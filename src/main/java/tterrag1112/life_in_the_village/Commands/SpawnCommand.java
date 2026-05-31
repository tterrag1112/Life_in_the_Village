package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Village.Planning.V2.V2VillageSpawnerAdapter;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * {@code /litv spawn} — runs the full V2 stack at the player's
 * position and physically spawns the village with all post-passes
 * (decoration, parks, farms, homesteads, NPC population, trade
 * routes, simulation baseline, guild bootstrap, history seeding,
 * initial laws).
 *
 * <p><b>B2.7 fix:</b> previously this command duplicated Layers
 * 1–4 manually and called the Layer 5 spawner directly,
 * skipping every Layer 4 post-pass that B2.1–B2.6 wired into
 * {@link V2VillageSpawnerAdapter}. The duplication is gone; the
 * command is now a thin wrapper that delegates to the canonical
 * adapter the same way {@code VillageSpawner.spawn} does.</p>
 */
public final class SpawnCommand {

    private static final String DEFAULT_VILLAGE_TYPE = "default";

    private SpawnCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("spawn")
                        .executes(SpawnCommand::run)));
    }

    private static int run(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        ServerLevel level = src.getLevel();
        BlockPos centre = player.blockPosition();

        long t0 = System.currentTimeMillis();
        String villageName = "v2_" + Long.toHexString(level.getSeed())
                + "_" + centre.getX() + "_" + centre.getZ();
        send(src, "[litv-spawn] running full V2 pipeline at "
                + centre.getX() + "," + centre.getZ() + " ...");

        Optional<Village> result = V2VillageSpawnerAdapter.spawn(
                level, centre, DEFAULT_VILLAGE_TYPE, villageName);
        long elapsed = System.currentTimeMillis() - t0;

        if (result.isEmpty()) {
            send(src, "[litv-spawn] no village spawned (check log for "
                    + "site-too-close-to-existing-village or unviable terrain)");
            return 0;
        }
        Village village = result.get();
        send(src, "[litv-spawn] spawned '" + village.getName() + "' tier="
                + village.getSizeTier() + " buildings=" + village.getBuildingIds().size()
                + " in " + elapsed + "ms");
        send(src, "  full pipeline ran: roads + buildings + decoration + parks + "
                + "farms + homesteads + NPCs + trade routes + simulation baseline");
        return 1;
    }

    private static void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
