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
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Farms.FarmSector;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;

/**
 * B2.5 — `/liv farms &lt;village&gt;` debug listing.
 *
 * <p>Reports the village's reserved {@link FarmSector} (zero or one
 * per village) plus the per-farmhouse plot allocation: id, crop
 * type, polygon vertex count, owning farmhouse. Without this
 * command testers would have to grep server logs for sector lines.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class FarmDebugCommand {

    private FarmDebugCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("liv")
                        .then(Commands.literal("farms")
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
                    "/liv farms must be run on a server level."));
            return 0;
        }
        VillageSavedData data = VillageSavedData.get(level);
        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }

        List<FarmSector> sectors = data.getFarmSectorsForVillage(village.getId());
        var tier = village.getSizeTier();
        var inclination = village.getInclination();
        String tierStr = "tier=" + tier
                + " inclination=" + (inclination != null ? inclination.name() : "(unset)");
        // B2.8 — count farmhouses to narrow empty-state reason.
        int farmhouseCount = 0;
        for (java.util.UUID bid : village.getBuildingIds()) {
            var b = data.getBuildingById(bid).orElse(null);
            if (b != null && b.getType() == tterrag1112.life_in_the_village
                    .Village.Buildings.BuildingType.FARMHOUSE) {
                farmhouseCount++;
            }
        }
        if (sectors.isEmpty()) {
            String reason = farmhouseCount == 0
                    ? "no FARMHOUSE buildings in this village (sectors gate on farmhouseCount >= 1)"
                    : "FarmSectorPlanner found no arable centre — terrain may be too steep / wet / "
                            + "obstructed. Farmhouses present: " + farmhouseCount;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Village " + villageName + ": no reserved farm sectors.\n"
                            + "  " + tierStr + "\n"
                            + "  reason: " + reason),
                    false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(sectors.size()).append(" farm sector(s) in ")
                .append(villageName).append(" (").append(tierStr).append("):\n");
        for (FarmSector s : sectors) {
            sb.append("  Sector ").append(s.sectorId().toString().substring(0, 8))
                    .append(" — ").append(s.bounds().vertices().size()).append(" vertices, ")
                    .append("area≈").append(s.approximateArea())
                    .append(", farmhouses=").append(s.farmhouseIds().size())
                    .append(", plots=").append(s.plotIds().size())
                    .append("\n");
            for (java.util.UUID plotId : s.plotIds()) {
                FarmPlot p = data.getFarmPlotById(plotId).orElse(null);
                if (p == null) continue;
                sb.append("    Plot ").append(p.getId().toString().substring(0, 8))
                        .append(" crop=").append(p.getCropType().name())
                        .append(" subtype=").append(p.getSubtype().name())
                        .append(" origin=(").append(p.getOrigin().getX()).append(",")
                        .append(p.getOrigin().getZ()).append(")")
                        .append(" radius=").append(p.getRadius())
                        .append(p.getPolygon() != null
                                ? " poly=" + p.getPolygon().vertices().size() + "v"
                                : " poly=none")
                        .append(p.getFarmhouseId() != null
                                ? " farmhouse=" + p.getFarmhouseId().toString().substring(0, 8)
                                : "")
                        .append("\n");
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return sectors.size();
    }
}
