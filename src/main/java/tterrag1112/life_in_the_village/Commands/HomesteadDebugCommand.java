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

import java.util.EnumMap;
import java.util.UUID;

/**
 * B2.6 — `/liv homestead &lt;village&gt;` debug listing.
 *
 * <p>Reports each HOUSE building in the village with: rolled
 * homestead plot type (if any), household members + roles. The
 * roll-success rate scales by tier (HAMLET 80% → CITY 10%) so
 * cities show mostly empty homesteads while hamlets show many.</p>
 *
 * <p>B2.8 — empty-state output now reports actual tier +
 * inclination + a building-type breakdown so the user can see
 * exactly why a village has no HOUSEs (vs. having e.g. 12
 * FARMHOUSEs and 0 HOUSEs).</p>
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
        // B2.8 — also tally other BuildingTypes so the empty-HOUSE
        // case can show what IS in the village.
        EnumMap<BuildingType, Integer> typeCounts = new EnumMap<>(BuildingType.class);
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null) continue;
            typeCounts.merge(b.getType(), 1, Integer::sum);
            if (b.getType() != BuildingType.HOUSE) continue;
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
        String tierAndInclination = "tier=" + village.getSizeTier()
                + " inclination=" + (village.getInclination() != null
                        ? village.getInclination().name() : "(unset)");
        if (houses == 0) {
            // B2.8 — concrete empty-state output: tier, inclination,
            // building-type breakdown, and the specific reason.
            StringBuilder out = new StringBuilder();
            out.append("Village ").append(villageName).append(": no HOUSE buildings.\n")
                    .append("  ").append(tierAndInclination).append("\n");
            int totalBuildings = typeCounts.values().stream().mapToInt(Integer::intValue).sum();
            out.append("  building types (").append(totalBuildings).append(" total): ");
            if (typeCounts.isEmpty()) {
                out.append("(none)\n");
            } else {
                typeCounts.forEach((t, c) -> out.append(t.name()).append("×").append(c).append(" "));
                out.append("\n");
            }
            out.append("  reason: V2 BuildingSelector did not include HOUSE in the");
            out.append("\n          selection for this (tier, inclination) combination,");
            out.append("\n          OR ReconciliationEngine dropped HOUSE during cascade.");
            ctx.getSource().sendSuccess(() -> Component.literal(out.toString()), false);
            return 0;
        }
        StringBuilder header = new StringBuilder();
        header.append("Homesteads in ").append(villageName).append(": ")
                .append(withPlot).append("/").append(houses)
                .append(" houses have a rolled plot (")
                .append(tierAndInclination).append(")\n")
                .append(sb);
        ctx.getSource().sendSuccess(() -> Component.literal(header.toString()), false);
        return houses;
    }
}
