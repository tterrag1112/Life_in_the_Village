package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleGenerator;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyle;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyles;

import java.util.Random;

public class CastleCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("castle")
                .then(Commands.literal("place")
                        .then(Commands.argument("origin", BlockPosArgument.blockPos())
                                .then(Commands.argument("style", StringArgumentType.string())
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    ServerLevel level = ctx.getSource().getLevel();
                                                    RandomSource rng = RandomSource.create();
                                                    BlockPos origin = BlockPosArgument.getBlockPos(ctx, "pos");
                                                    String  style = StringArgumentType.getString(ctx, "style");
                                                    CastleGenerator.generate(level, origin, CastleStyles.NORMAN, rng);


                                                    return 1;
                                                }))))));
    }
}
