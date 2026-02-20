package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Networking.SyncBuildingsPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

public class BuildingCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("building")
                        // --- Existing place command ---
                        .then(Commands.literal("place")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("structure", IdentifierArgument.id())
                                                .then(Commands.argument("name", StringArgumentType.string())
                                                        .then(Commands.argument("type", StringArgumentType.word())
                                                                .then(Commands.argument("rotation", StringArgumentType.word())
                                                                        .executes(ctx -> {
                                                                            BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
                                                                            Identifier structureId = IdentifierArgument.getId(ctx, "structure");
                                                                            String name = StringArgumentType.getString(ctx, "name");
                                                                            String typeStr = StringArgumentType.getString(ctx, "type");
                                                                            ServerLevel level = ctx.getSource().getLevel();

                                                                            Building.BuildingType type;
                                                                            try {
                                                                                type = Building.BuildingType.valueOf(typeStr.toUpperCase());
                                                                            } catch (IllegalArgumentException e) {
                                                                                ctx.getSource().sendFailure(
                                                                                        Component.literal("Unknown building type: " + typeStr)
                                                                                );
                                                                                return 0;
                                                                            }

                                                                            String rotationStr = StringArgumentType.getString(ctx, "rotation");
                                                                            Rotation rotation;
                                                                            try {
                                                                                rotation = Rotation.valueOf(rotationStr.toUpperCase());
                                                                            } catch (IllegalArgumentException e) {
                                                                                ctx.getSource().sendFailure(
                                                                                        Component.literal("Invalid rotation. Use NONE, CLOCKWISE_90, CLOCKWISE_180, or COUNTERCLOCKWISE_90")
                                                                                );
                                                                                return 0;
                                                                            }

                                                                            Optional<Building> result = BuildingPlacer.placeAndRegister(
                                                                                    level, pos, structureId, name, type, rotation
                                                                            );

                                                                            if (result.isPresent()) {
                                                                                ctx.getSource().sendSuccess(
                                                                                        () -> Component.literal("Placed and registered: " + name), true
                                                                                );
                                                                                return 1;
                                                                            } else {
                                                                                ctx.getSource().sendFailure(
                                                                                        Component.literal("Structure not found: " + structureId)
                                                                                );
                                                                                return 0;
                                                                            }
                                                                        })
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )

                        // --- Village create command ---
                        .then(Commands.literal("village")
                                .then(Commands.literal("create")
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String name = StringArgumentType.getString(ctx, "name");
                                                    VillageSavedData data = VillageSavedData.get(ctx.getSource().getLevel());
                                                    data.addVillage(new Village(name));
                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Created village: " + name), true
                                                    );
                                                    return 1;
                                                })
                                        )
                                )

                                // --- Village assign command ---
                                .then(Commands.literal("assign")
                                        .then(Commands.argument("village", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String villageName = StringArgumentType.getString(ctx, "village");
                                                    ServerLevel level = ctx.getSource().getLevel();
                                                    VillageSavedData data = VillageSavedData.get(level);

                                                    // Get the position of the command executor
                                                    BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());

                                                    Optional<Village> village = data.getVillageByName(villageName);
                                                    Optional<Building> building = data.getBuildingAt(pos);

                                                    if (village.isEmpty()) {
                                                        ctx.getSource().sendFailure(
                                                                Component.literal("Village not found: " + villageName)
                                                        );
                                                        return 0;
                                                    }
                                                    if (building.isEmpty()) {
                                                        ctx.getSource().sendFailure(
                                                                Component.literal("You are not standing inside any building.")
                                                        );
                                                        return 0;
                                                    }

                                                    village.get().addBuilding(building.get());
                                                    data.setDirty();
                                                    PacketDistributor.sendToPlayer(
                                                            (ServerPlayer) ctx.getSource().getEntity(),
                                                            new SyncBuildingsPacket(data.getAllBuildings(), data.getAllVillages())
                                                    );
                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Assigned " + building.get().getName() + " to " + villageName), true
                                                    );
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );
    }
}
