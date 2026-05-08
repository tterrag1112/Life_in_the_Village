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
import tterrag1112.life_in_the_village.Village.Decoration.Parks.GardenPlot;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

/**
 * B2.4 — `/liv parks &lt;village&gt;` debug listing.
 *
 * <p>Shows reserved {@link GardenPlot}s for a village: id, style,
 * bounds, area, and how much of the plot the renderer preserved
 * vs composed. Without this command the only signal is log lines;
 * the command lets a tester confirm reservation without grepping
 * the server log.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class ParkDebugCommand {

    private ParkDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("parks")
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
                    "/liv parks must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }

        List<GardenPlot> plots = data.getGardenPlotsForVillage(village.getId());
        var tier = village.getSizeTier();
        var inclination = village.getInclination();
        String tierStr = "tier=" + tier
                + " inclination=" + (inclination != null ? inclination.name() : "(unset)");
        if (plots.isEmpty()) {
            // B2.8 — narrow the empty-state reason to the actual
            // gating that fired.
            String reason;
            if (tier == tterrag1112.life_in_the_village.Village.Decoration
                    .VillageSizeTier.HAMLET) {
                reason = "tier limit: HAMLET caps parks at 0";
            } else {
                reason = "ParkCandidateFinder found no candidates in this terrain "
                        + "(check forest / water proximity, slope < 3)";
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Village " + villageName + ": no reserved garden plots.\n"
                            + "  " + tierStr + "\n"
                            + "  reason: " + reason),
                    false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(plots.size()).append(" garden plots in ")
                .append(villageName).append(" (").append(tierStr).append("):\n");
        for (GardenPlot p : plots) {
            sb.append("  ").append(p.style().name())
                    .append(" @ (")
                    .append(p.bounds().minX()).append(",")
                    .append(p.bounds().minZ()).append(")..(")
                    .append(p.bounds().maxX()).append(",")
                    .append(p.bounds().maxZ()).append(")")
                    .append(" size=").append(p.width()).append("×").append(p.length())
                    .append(" preserveBias=").append(String.format(
                            java.util.Locale.ROOT, "%.2f", p.preserveBias()))
                    .append(" preserved=").append(p.preservedFeatures().size())
                    .append(" composed=").append(p.composedPrimitives().size())
                    .append(" id=").append(p.plotId().toString().substring(0, 8))
                    .append("\n");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return plots.size();
    }
}
