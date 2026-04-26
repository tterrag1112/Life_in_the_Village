package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Simulation.BuildingResourceProfile;
import tterrag1112.life_in_the_village.Village.Simulation.ResourceCategory;
import tterrag1112.life_in_the_village.Village.Simulation.VillageSimData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Optional;

/**
 * Phase 4 doc 25 debug surface. Subcommands:
 *
 * <ul>
 *   <li>{@code /sim resources <village>} — table of every category's
 *   production / consumption / net per day.</li>
 *   <li>{@code /sim category <village> <category>} — detail for one
 *   category.</li>
 *   <li>{@code /sim profile <buildingType>} — print the
 *   {@link BuildingResourceProfile#TABLE} entry.</li>
 * </ul>
 */
public final class SimDebugCommand {

    private SimDebugCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sim")
                .then(Commands.literal("resources")
                        .then(Commands.argument("village", StringArgumentType.greedyString())
                                .executes(SimDebugCommand::handleResources)))
                .then(Commands.literal("category")
                        .then(Commands.argument("village", StringArgumentType.string())
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (ResourceCategory r : ResourceCategory.values())
                                                b.suggest(r.name());
                                            return b.buildFuture();
                                        })
                                        .executes(SimDebugCommand::handleCategory))))
                .then(Commands.literal("profile")
                        .then(Commands.argument("buildingType", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    for (BuildingType t : BuildingType.values())
                                        b.suggest(t.name());
                                    return b.buildFuture();
                                })
                                .executes(SimDebugCommand::handleProfile)))
        );
    }

    // ── /sim resources <village> ──────────────────────────────────────────

    private static int handleResources(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;

        VillageSimData sim = VillageSavedData.get(level)
                .getSimData(village.getId()).orElse(null);
        if (sim == null) {
            src.sendFailure(Component.literal("No sim data for village " + village.getName()));
            return 0;
        }

        StringBuilder sb = new StringBuilder("§e=== Sim resources: ")
                .append(village.getName()).append(" §7(pop=")
                .append(sim.getSimulatedPopulation()).append(") ===§r");
        sb.append(String.format(Locale.ROOT,
                "%n  §7%-22s§f %10s§f %10s§f %10s",
                "category", "prod/d", "cons/d", "net/d"));
        for (ResourceCategory c : ResourceCategory.values()) {
            float prod = sim.production(c);
            float cons = sim.consumption(c);
            float net  = prod - cons;
            if (prod == 0f && cons == 0f) continue;
            String netCol = net > 0 ? "§a" : net < 0 ? "§c" : "§7";
            sb.append(String.format(Locale.ROOT,
                    "%n  §f%-22s §a%10.1f §c%10.1f " + netCol + "%+10.1f§r",
                    c.name(), prod, cons, net));
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /sim category <village> <category> ────────────────────────────────

    private static int handleCategory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        Village village = villageOrFail(ctx, level, src);
        if (village == null) return 0;
        ResourceCategory cat;
        try {
            cat = ResourceCategory.valueOf(
                    StringArgumentType.getString(ctx, "category").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown category"));
            return 0;
        }
        VillageSimData sim = VillageSavedData.get(level)
                .getSimData(village.getId()).orElse(null);
        if (sim == null) {
            src.sendFailure(Component.literal("No sim data for " + village.getName()));
            return 0;
        }
        float prod = sim.production(cat);
        float cons = sim.consumption(cat);
        float net  = prod - cons;
        StringBuilder sb = new StringBuilder("§e=== ").append(village.getName())
                .append(" — ").append(cat.name()).append(" ===§r");
        sb.append(String.format(Locale.ROOT, "%n  §7production:§a %10.2f", prod));
        sb.append(String.format(Locale.ROOT, "%n  §7consumption:§c %9.2f", cons));
        sb.append(String.format(Locale.ROOT, "%n  §7net:§f       %10.2f §7(%s)",
                net, sim.isExporterOf(cat) ? "exporter" :
                       sim.isImporterOf(cat) ? "importer" : "balanced"));
        sb.append(String.format(Locale.ROOT, "%n  §7isPhysical: §f%s", cat.isPhysical()));
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    // ── /sim profile <buildingType> ───────────────────────────────────────

    private static int handleProfile(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        BuildingType type;
        try {
            type = BuildingType.valueOf(
                    StringArgumentType.getString(ctx, "buildingType").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown building type"));
            return 0;
        }
        BuildingResourceProfile profile = BuildingResourceProfile.get(type);
        StringBuilder sb = new StringBuilder("§e=== Profile: ").append(type.name())
                .append(" ===§r");
        if (profile == null) {
            sb.append("\n  §7(no profile entry — neutral contribution)");
        } else {
            sb.append("\n  §aproduction:");
            if (profile.productionPerDay().isEmpty()) sb.append(" §7(none)");
            for (var e : profile.productionPerDay().entrySet()) {
                sb.append(String.format(Locale.ROOT,
                        "%n    §a%-22s§f %.2f", e.getKey().name(), e.getValue()));
            }
            sb.append("\n  §cconsumption:");
            if (profile.consumptionPerDay().isEmpty()) sb.append(" §7(none)");
            for (var e : profile.consumptionPerDay().entrySet()) {
                sb.append(String.format(Locale.ROOT,
                        "%n    §c%-22s§f %.2f", e.getKey().name(), e.getValue()));
            }
        }
        src.sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.WHITE), false);
        return 1;
    }

    private static Village villageOrFail(CommandContext<CommandSourceStack> ctx,
                                         ServerLevel level, CommandSourceStack src) {
        String name = StringArgumentType.getString(ctx, "village");
        Optional<Village> v = VillageSavedData.get(level).getVillageByName(name);
        if (v.isEmpty()) {
            src.sendFailure(Component.literal("No village " + name));
            return null;
        }
        return v.get();
    }
}
