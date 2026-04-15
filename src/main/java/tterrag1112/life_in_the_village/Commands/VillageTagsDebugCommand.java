// src/main/java/tterrag1112/life_in_the_village/Commands/VillageTagsDebugCommand.java
package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

/**
 * /liv villagetype tags &lt;type&gt; — prints the tag set for a
 * village type, showing both manual + derived in the unified result.
 */
public class VillageTagsDebugCommand {
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("liv")
                .then(Commands.literal("villagetype")
                        .then(Commands.literal("tags")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(VillageTagsDebugCommand::run)))));
    }

    private static int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        String type = StringArgumentType.getString(ctx, "type");
        VillageTypeData data = VillageTypeRegistry.INSTANCE.getType(type);
        if (data == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown village type: " + type));
            return 0;
        }
        String msg = "Village type '" + type + "'\n"
                + "  shape:    " + data.getShapeProfile().shapeType() + "\n"
                + "  terrain:  " + data.getTerrainStrategy() + "\n"
                + "  tags:     " + data.getTags();
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }
}