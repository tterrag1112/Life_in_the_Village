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
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Farms.Complex.FarmComplex;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.UUID;

/**
 * Detour A — `/liv farms &lt;village&gt;` debug listing.
 *
 * <p>Reports the village's {@link FarmComplex} list (one per
 * farmhouse). For each complex: region polygon vertex count + area,
 * path segment count, tool-shed-present indicator, then per-plot
 * detail: id, crop, polygon vertex count, owning farmhouse. The
 * pre-Stage-5 sector listing was 1:N (one sector with N plots);
 * this is N×1 (N complexes with their own plots) — the format
 * mirrors the new structure.
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

        List<FarmComplex> complexes = data.getFarmComplexesForVillage(village.getId());
        var tier = village.getSizeTier();
        var inclination = village.getInclination();
        String tierStr = "tier=" + tier
                + " inclination=" + (inclination != null ? inclination.name() : "(unset)");

        int farmhouseCount = 0;
        for (UUID bid : village.getBuildingIds()) {
            var b = data.getBuildingById(bid).orElse(null);
            if (b != null && b.getType() == tterrag1112.life_in_the_village
                    .Village.Buildings.BuildingType.FARMHOUSE) {
                farmhouseCount++;
            }
        }

        if (complexes.isEmpty()) {
            String reason = farmhouseCount == 0
                    ? "no FARMHOUSE buildings in this village"
                    : "FarmComplexPlanner found no viable claim — terrain may be too steep, "
                            + "biome-blocked, or below the arable-cell minimum. "
                            + "Farmhouses present: " + farmhouseCount;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Village " + villageName + ": no farm complexes.\n"
                            + "  " + tierStr + "\n"
                            + "  reason: " + reason),
                    false);
            return 0;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(complexes.size()).append(" farm complex(es) in ")
                .append(villageName).append(" (").append(tierStr).append("):\n");
        for (FarmComplex c : complexes) {
            int verts = c.region().vertices().size();
            int area = (int) Polygon.area(c.region());
            int pathSegs = c.pathSegments().size();
            int gates = c.gatePositions().size();
            boolean hasShed = c.toolShedPosition() != null;
            sb.append("  Complex ").append(shortId(c.id()))
                    .append(" farmhouse=").append(shortId(c.farmhouseId()))
                    .append(" — region: ").append(verts).append("v area≈").append(area)
                    .append(", plots=").append(c.plotIds().size())
                    .append(", paths=").append(pathSegs)
                    .append(", gates=").append(gates)
                    .append(", shed=").append(hasShed ? "yes" : "no")
                    .append("\n");
            for (UUID plotId : c.plotIds()) {
                FarmPlot p = data.getFarmPlotById(plotId).orElse(null);
                if (p == null) {
                    sb.append("    Plot ").append(shortId(plotId))
                            .append(" (missing from farmPlots index)\n");
                    continue;
                }
                sb.append("    Plot ").append(shortId(p.getId()))
                        .append(" crop=").append(p.getCropType().name())
                        .append(" subtype=").append(p.getSubtype().name())
                        .append(" origin=(").append(p.getOrigin().getX()).append(",")
                        .append(p.getOrigin().getZ()).append(")")
                        .append(p.getPolygon() != null
                                ? " poly=" + p.getPolygon().vertices().size() + "v"
                                : " poly=none")
                        .append("\n");
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return complexes.size();
    }

    private static String shortId(UUID id) {
        if (id == null) return "(null)";
        return id.toString().substring(0, 8);
    }
}
