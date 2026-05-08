package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * B2.7 — `/liv adjuncts &lt;village&gt;` debug listing.
 *
 * <p>Reports every reserved {@link AdjunctPlot} for the named
 * village: parent building, plot type, world origin, footprint
 * dimensions, facing. The command surfaces the cumulative output
 * of the B2.1 reservation framework + the B2.3 / B2.6 content
 * registrations + the B2.6 HOMESTEAD_* probability rolls — useful
 * for confirming that V2's adjunct planning is producing the
 * expected mix per village.</p>
 *
 * <p>Self-registers via {@code @EventBusSubscriber} matching the
 * convention the B2 debug commands settled on (decoration / parks
 * / farms / homestead).</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class AdjunctDebugCommand {

    private AdjunctDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("adjuncts")
                                .then(Commands.argument("village",
                                                StringArgumentType.string())
                                        .executes(ctx -> list(ctx,
                                                StringArgumentType.getString(ctx, "village")))))
        );
    }

    private static int list(CommandContext<CommandSourceStack> ctx,
                            String villageName) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal(
                    "/liv adjuncts must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null) continue;
            for (AdjunctPlot plot : data.getAdjunctPlotsForBuilding(bid)) {
                sb.append("  ").append(plot.type().name())
                        .append(" parent=").append(b.getType()).append("/")
                        .append(b.getId().toString().substring(0, 8))
                        .append(" origin=(")
                        .append(plot.origin().getX()).append(",")
                        .append(plot.origin().getY()).append(",")
                        .append(plot.origin().getZ()).append(")")
                        .append(" size=").append(plot.width()).append("×").append(plot.length())
                        .append(" facing=").append(plot.facingFromParent().name())
                        .append("\n");
                total++;
            }
        }
        if (total == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Village " + villageName + " has no reserved adjunct plots."), false);
            return 0;
        }
        StringBuilder header = new StringBuilder();
        header.append(total).append(" adjunct plot(s) in ")
                .append(villageName).append(":\n").append(sb);
        ctx.getSource().sendSuccess(() -> Component.literal(header.toString()), false);
        return total;
    }
}
