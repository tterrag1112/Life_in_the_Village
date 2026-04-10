package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.Planning.Rules.ShapeRule;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

/**
 * Debug commands for inspecting the shape rule system.
 *
 * <h3>Commands</h3>
 * <ul>
 *   <li>{@code /shaperules types} — list registered rule type names</li>
 *   <li>{@code /shaperules show <villageType>} — print the rule stack
 *       for a specific village type (either parsed from JSON or
 *       synthesized from the legacy enum)</li>
 * </ul>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class ShapeRuleDebugCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("shaperules")
                        .then(Commands.literal("types")
                                .executes(ShapeRuleDebugCommand::listTypes))
                        .then(Commands.literal("show")
                                .then(Commands.argument("villageType",
                                                StringArgumentType.word())
                                        .executes(ctx -> show(ctx,
                                                StringArgumentType.getString(
                                                        ctx, "villageType")))))
        );
    }

    private static int listTypes(CommandContext<CommandSourceStack> ctx) {
        var types = ShapeRule.Registry.registeredTypes();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§bRegistered rule types (" + types.size() + "):\n§f"
                        + String.join(", ", types)), false);
        return types.size();
    }

    private static int show(CommandContext<CommandSourceStack> ctx,
                            String villageType) {
        VillageTypeData type = VillageTypeRegistry.INSTANCE.getType(villageType);
        if (type == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "Unknown village type: " + villageType));
            return 0;
        }

        var rules = type.getShapeRules();
        if (rules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§eVillage type '" + villageType
                            + "' has an empty rule stack"), false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§bRule stack for '").append(villageType)
                .append("' (").append(rules.size()).append(" rules):\n");
        int i = 1;
        for (ShapeRule r : rules) {
            sb.append("§7").append(i++).append(". §f")
                    .append(r.typeName()).append(" §7")
                    .append(r.getClass().getSimpleName()).append("\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return rules.size();
    }
}