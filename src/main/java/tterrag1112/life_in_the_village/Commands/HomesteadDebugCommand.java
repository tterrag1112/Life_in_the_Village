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
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.UUID;

/**
 * B2.6 — `/liv homestead &lt;village&gt;` debug listing.
 *
 * <p>Reports each HOUSE building in the village with: rolled
 * homestead plot type (if any), household members + roles, recent
 * production cycles. The roll-success rate scales by tier (HAMLET
 * 80% → CITY 10%) so cities show mostly empty homesteads while
 * hamlets show many.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class HomesteadDebugCommand {

    private HomesteadDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("homestead")
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
                    "/liv homestead must be run on a server level."));
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
        int houses = 0;
        int withPlot = 0;
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null || b.getType() != BuildingType.HOUSE) continue;
            houses++;
            HouseholdData hh = data.getHouseholdForBuilding(b.getId()).orElse(null);
            String plot = "—";
            if (hh != null && hh.hasHomestead()) {
                plot = hh.getHomesteadPlotType().name();
                withPlot++;
            } else {
                // Fall back to scanning AdjunctPlots: a HOUSE may have
                // a HOMESTEAD_* plot reserved even before the household
                // formed (e.g. transient state during spawn).
                for (AdjunctPlot ap : data.getAdjunctPlotsForBuilding(b.getId())) {
                    if (ap.type().name().startsWith("HOMESTEAD_")) {
                        plot = ap.type().name() + " (no household yet)";
                        break;
                    }
                }
            }
            int memberCount = hh != null ? hh.size() : 0;
            sb.append("  HOUSE ").append(b.getId().toString().substring(0, 8))
                    .append(" plot=").append(plot)
                    .append(" members=").append(memberCount)
                    .append("\n");
        }
        if (houses == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Village " + villageName + " has no HOUSE buildings."), false);
            return 0;
        }
        StringBuilder header = new StringBuilder();
        header.append("Homesteads in ").append(villageName).append(": ")
                .append(withPlot).append("/").append(houses)
                .append(" houses have a rolled plot (tier=")
                .append(village.getSizeTier()).append(")\n")
                .append(sb);
        ctx.getSource().sendSuccess(() -> Component.literal(header.toString()), false);
        return houses;
    }
}
