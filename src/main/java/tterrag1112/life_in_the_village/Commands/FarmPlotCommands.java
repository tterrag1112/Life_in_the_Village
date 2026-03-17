package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FarmPlotCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("farmplot")
                        // /farmplot create <name> <radius>
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                .executes(ctx -> createPlot(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        IntegerArgumentType.getInteger(ctx, "radius")
                                                ))
                                        )
                                )
                        )

                        // /farmplot assign <plotname> <farmhousename>
                        .then(Commands.literal("assign")
                                .then(Commands.argument("plotname", StringArgumentType.word())
                                        .then(Commands.argument("farmhousename", StringArgumentType.word())
                                                .executes(ctx -> assignPlot(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "plotname"),
                                                        StringArgumentType.getString(ctx, "farmhousename")
                                                ))
                                        )
                                )
                        )

                        // /farmplot list <villagename>
                        .then(Commands.literal("list")
                                .then(Commands.argument("villagename", StringArgumentType.word())
                                        .executes(ctx -> listPlots(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "villagename")
                                        ))
                                )
                        )

                        // /farmplot remove <name>
                        .then(Commands.literal("remove")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> removePlot(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")
                                        ))
                                )
                        )

                        // /farmplot setcrop <plotname> <croptype>
                        .then(Commands.literal("setcrop")
                                .then(Commands.argument("plotname", StringArgumentType.word())
                                        .then(Commands.argument("croptype", StringArgumentType.word())
                                                .executes(ctx -> setCrop(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "plotname"),
                                                        StringArgumentType.getString(ctx, "croptype")
                                                ))
                                        )
                                )
                        )
        );
    }

    private static int createPlot(CommandSourceStack src, String name, int radius) {
        try {
            ServerLevel level = src.getLevel();
            BlockPos pos = BlockPos.containing(src.getPosition());
            VillageSavedData data = VillageSavedData.get(level);

            if (data.getFarmPlotByName(name).isPresent()) {
                src.sendFailure(Component.literal("A farm plot named '" + name + "' already exists."));
                return 0;
            }

            FarmPlot plot = new FarmPlot(UUID.randomUUID(), name, pos, radius, FarmPlot.CropType.MIXED);
            data.addFarmPlot(plot);

            src.sendSuccess(() -> Component.literal(
                    "Created farm plot '" + name + "' at " + pos + " with radius " + radius
            ), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Failed to create farm plot: " + e.getMessage()));
            return 0;
        }
    }

    private static int assignPlot(CommandSourceStack src, String plotName, String farmhouseName) {
        try {
            ServerLevel level = src.getLevel();
            VillageSavedData data = VillageSavedData.get(level);

            Optional<FarmPlot> plot = data.getFarmPlotByName(plotName);
            if (plot.isEmpty()) {
                src.sendFailure(Component.literal("No farm plot named '" + plotName + "'."));
                return 0;
            }

            Optional<Building> farmhouse = data.getBuildingByName(farmhouseName);
            if (farmhouse.isEmpty()) {
                src.sendFailure(Component.literal("No building named '" + farmhouseName + "'."));
                return 0;
            }

            if (farmhouse.get().getType() != Building.BuildingType.FARMHOUSE) {
                src.sendFailure(Component.literal(
                        "Building '" + farmhouseName + "' is not a FARMHOUSE."
                ));
                return 0;
            }

            plot.get().setFarmhouseId(farmhouse.get().getId());
            data.setDirty();

            src.sendSuccess(() -> Component.literal(
                    "Assigned plot '" + plotName + "' to farmhouse '" + farmhouseName + "'."
            ), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Failed to assign plot: " + e.getMessage()));
            return 0;
        }
    }

    private static int listPlots(CommandSourceStack src, String villageName) {
        try {
            ServerLevel level = src.getLevel();
            VillageSavedData data = VillageSavedData.get(level);

            Optional<Village> village = data.getVillageByName(villageName);
            if (village.isEmpty()) {
                src.sendFailure(Component.literal("No village named '" + villageName + "'."));
                return 0;
            }

            List<FarmPlot> plots = data.getFarmPlotsInVillage(village.get(), data);
            if (plots.isEmpty()) {
                src.sendSuccess(() -> Component.literal(
                        "No farm plots found in village '" + villageName + "'."
                ), false);
                return 1;
            }

            src.sendSuccess(() -> Component.literal(
                    "=== Farm plots in " + villageName + " ==="
            ), false);

            for (FarmPlot plot : plots) {
                String farmhouse = plot.isAssigned()
                        ? data.getBuildingById(plot.getFarmhouseId())
                        .map(Building::getName).orElse("unknown")
                        : "unassigned";

                src.sendSuccess(() -> Component.literal(
                        "  " + plot.getName()
                                + " | origin: " + plot.getOrigin()
                                + " | r: " + plot.getRadius()
                                + " | crop: " + plot.getCropType().name()
                                + " | farmhouse: " + farmhouse
                ), false);
            }
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Failed to list plots: " + e.getMessage()));
            return 0;
        }
    }

    private static int removePlot(CommandSourceStack src, String name) {
        try {
            ServerLevel level = src.getLevel();
            VillageSavedData data = VillageSavedData.get(level);

            Optional<FarmPlot> plot = data.getFarmPlotByName(name);
            if (plot.isEmpty()) {
                src.sendFailure(Component.literal("No farm plot named '" + name + "'."));
                return 0;
            }

            data.removeFarmPlot(plot.get().getId());
            src.sendSuccess(() -> Component.literal(
                    "Removed farm plot '" + name + "'."
            ), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Failed to remove plot: " + e.getMessage()));
            return 0;
        }
    }

    private static int setCrop(CommandSourceStack src, String plotName, String cropTypeName) {
        try {
            ServerLevel level = src.getLevel();
            VillageSavedData data = VillageSavedData.get(level);

            Optional<FarmPlot> plot = data.getFarmPlotByName(plotName);
            if (plot.isEmpty()) {
                src.sendFailure(Component.literal("No farm plot named '" + plotName + "'."));
                return 0;
            }

            FarmPlot.CropType cropType;
            try {
                cropType = FarmPlot.CropType.valueOf(cropTypeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                src.sendFailure(Component.literal(
                        "Invalid crop type. Valid types: WHEAT, CARROTS, POTATOES, BEETROOT, MIXED"
                ));
                return 0;
            }

            plot.get().setCropType(cropType);
            data.setDirty();

            src.sendSuccess(() -> Component.literal(
                    "Set crop type of '" + plotName + "' to " + cropType.name()
            ), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Failed to set crop: " + e.getMessage()));
            return 0;
        }
    }
}
