package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad;

import java.util.List;

/**
 * Debug commands for testing trade road aging and repair.
 *
 * <ul>
 *   <li>{@code /liv roads degrade} — instantly convert all road blocks to dirt
 *       (maximum weathering for visual testing)</li>
 *   <li>{@code /liv roads step} — degrade one stage: cobblestone→gravel,
 *       gravel→dirt_path, dirt_path→dirt</li>
 *   <li>{@code /liv roads repair} — restore all road blocks to cobblestone</li>
 * </ul>
 */
public class RoadDebugCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("liv")
                        .then(Commands.literal("roads")
                                .then(Commands.literal("degrade")
                                        .executes(RoadDebugCommand::degradeAllRoads))
                                .then(Commands.literal("step")
                                        .executes(RoadDebugCommand::stepAllRoads))
                                .then(Commands.literal("repair")
                                        .executes(RoadDebugCommand::repairAllRoads))
                        )
        );
    }

    /** Instantly convert all road surface blocks to dirt (fully weathered). */
    private static int degradeAllRoads(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        int roads = 0, blocks = 0;
        for (TradeRoad road : data.getAllTradeRoads()) {
            List<BlockPos> roadBlocks = road.getBlocks();
            if (roadBlocks.isEmpty()) continue;
            roads++;
            for (BlockPos pos : roadBlocks) {
                BlockPos block = pos.below();
                BlockState state = level.getBlockState(block);
                if (state.is(Blocks.COBBLESTONE)
                        || state.is(Blocks.GRAVEL)
                        || state.is(Blocks.DIRT_PATH)) {
                    level.setBlock(block, Blocks.DIRT.defaultBlockState(), 3);
                    blocks++;
                }
            }
        }

        int r = roads, b = blocks;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Fully degraded " + r + " roads (" + b + " blocks → dirt)"), false);
        return roads;
    }

    /** Advance all roads one degradation stage (cobbblestone→gravel→dirt_path→dirt). */
    private static int stepAllRoads(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        int roads = 0, blocks = 0;
        for (TradeRoad road : data.getAllTradeRoads()) {
            List<BlockPos> roadBlocks = road.getBlocks();
            if (roadBlocks.isEmpty()) continue;
            roads++;
            for (BlockPos pos : roadBlocks) {
                BlockPos block = pos.below();
                BlockState state = level.getBlockState(block);
                if (state.is(Blocks.COBBLESTONE)) {
                    level.setBlock(block, Blocks.GRAVEL.defaultBlockState(), 3);
                    blocks++;
                } else if (state.is(Blocks.GRAVEL)) {
                    level.setBlock(block, Blocks.DIRT_PATH.defaultBlockState(), 3);
                    blocks++;
                } else if (state.is(Blocks.DIRT_PATH)) {
                    level.setBlock(block, Blocks.DIRT.defaultBlockState(), 3);
                    blocks++;
                }
            }
        }

        int r = roads, b = blocks;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Stepped " + r + " roads one degradation stage (" + b + " blocks changed)"), false);
        return roads;
    }

    /** Restore all road surface blocks to cobblestone. */
    private static int repairAllRoads(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        int roads = 0, blocks = 0;
        for (TradeRoad road : data.getAllTradeRoads()) {
            List<BlockPos> roadBlocks = road.getBlocks();
            if (roadBlocks.isEmpty()) continue;
            roads++;
            for (BlockPos pos : roadBlocks) {
                BlockPos block = pos.below();
                BlockState state = level.getBlockState(block);
                if (state.is(Blocks.GRAVEL)
                        || state.is(Blocks.DIRT_PATH)
                        || state.is(Blocks.DIRT)) {
                    level.setBlock(block, Blocks.COBBLESTONE.defaultBlockState(), 3);
                    blocks++;
                }
            }
        }

        int r = roads, b = blocks;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Repaired " + r + " roads (" + b + " blocks → cobblestone)"), false);
        return roads;
    }
}
