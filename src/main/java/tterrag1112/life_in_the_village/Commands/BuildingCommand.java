package tterrag1112.life_in_the_village.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.SyncBuildingsPacket;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Event.EventEffects;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Event.VillageEventScheduler;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageSpawner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static tterrag1112.life_in_the_village.Village.BuildingPlacer.getProfessionForType;

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

                                                                            BuildingType type;
                                                                            try {
                                                                                type = BuildingType.valueOf(typeStr.toUpperCase());
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
                                        // /adventurer resetroads
                                        .then(Commands.literal("resetroads")
                                                .executes(ctx -> resetRoads(ctx.getSource())))
                                .then(Commands.literal("spawn")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .executes(ctx -> {
                                                            ServerLevel level = ctx.getSource().getLevel();
                                                            BlockPos pos = BlockPos.containing(
                                                                    ctx.getSource().getPosition());
                                                            String name = StringArgumentType.getString(ctx, "name");
                                                            String type = StringArgumentType.getString(ctx, "type");

                                                            VillageSpawner.spawnVillage(level, pos, type, name)
                                                                    .ifPresentOrElse(
                                                                            v -> ctx.getSource().sendSuccess(() ->
                                                                                            Component.literal("Spawned village '"
                                                                                                    + name + "' of type '" + type + "'"),
                                                                                    true),
                                                                            () -> ctx.getSource().sendFailure(
                                                                                    Component.literal("Failed to spawn village"))
                                                                    );
                                                            return 1;
                                                        })
                                                )
                                        )
                                )
                                // Add force event command
                                .then(Commands.literal("event")
                                        .then(Commands.argument("villagename", StringArgumentType.word())
                                                .then(Commands.argument("eventtype", StringArgumentType.word())
                                                        .executes(ctx -> forceEvent(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "villagename"),
                                                                StringArgumentType.getString(ctx, "eventtype")
                                                        ))
                                                )
                                        )
                                )
            .then(Commands.literal("needs")
                        .then(Commands.argument("villagename", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerLevel level = ctx.getSource().getLevel();
                                    String villageName = StringArgumentType.getString(ctx, "villagename");
                                    VillageSavedData data = VillageSavedData.get(level);

                                    data.getVillageByName(villageName).ifPresentOrElse(village -> {
                                        ctx.getSource().sendSuccess(() ->
                                                Component.literal("=== Needs for " + villageName + " ==="), false);

                                        // Prosperity
                                        int prosperity = VillageEventScheduler.getProsperity(
                                                level, village, data);
                                        ctx.getSource().sendSuccess(() ->
                                                Component.literal("Prosperity: " + prosperity + "/100"), false);

                                        // Active event
                                        data.getActiveEventForVillage(village.getId())
                                                .ifPresent(event ->
                                                        ctx.getSource().sendSuccess(() ->
                                                                Component.literal("Active Event: "
                                                                        + event.getType().name().replace("_", " ")
                                                                        + " (" + (int)(event.getProgress(
                                                                        level.getGameTime()) * 100)
                                                                        + "% complete)"), false));

                                        if (village.getNeeds().isEmpty()) {
                                            ctx.getSource().sendSuccess(() ->
                                                            Component.literal("  Not yet computed — waiting for next tick"),
                                                    false);
                                            return;
                                        }

                                        village.getNeeds().forEach((category, need) ->
                                                ctx.getSource().sendSuccess(() -> Component.literal(
                                                        "  " + category.name()
                                                                + ": " + need.getLevel()
                                                                + " (" + need.getAvailable()
                                                                + "/" + need.getRequired() + ")"
                                                                + (need.getDeficit() > 0
                                                                ? " deficit=" + need.getDeficit() : "")
                                                ), false)
                                        );

                                    }, () -> ctx.getSource().sendFailure(
                                            Component.literal("No village named '" + villageName + "'.")
                                    ));
                                    return 1;
                                })
                        )
                )

// Add force event as a sibling to needs
                .then(Commands.literal("event")
                        .then(Commands.argument("villagename", StringArgumentType.word())
                                .then(Commands.argument("eventtype", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerLevel level = ctx.getSource().getLevel();
                                            String villageName = StringArgumentType.getString(ctx, "villagename");
                                            String eventTypeName = StringArgumentType.getString(ctx, "eventtype");
                                            VillageSavedData data = VillageSavedData.get(level);

                                            Village village = data.getVillageByName(villageName)
                                                    .orElse(null);
                                            if (village == null) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "No village named '" + villageName + "'."));
                                                return 0;
                                            }

                                            VillageEvent.EventType type;
                                            try {
                                                type = VillageEvent.EventType.valueOf(
                                                        eventTypeName.toUpperCase());
                                            } catch (IllegalArgumentException e) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "Invalid event type. Valid: "
                                                                + java.util.Arrays.stream(
                                                                        VillageEvent.EventType.values())
                                                                .map(VillageEvent.EventType::name)
                                                                .collect(java.util.stream.Collectors
                                                                        .joining(", "))));
                                                return 0;
                                            }

                                            // Cancel any existing event
                                            data.getActiveEventsForVillage(village.getId())
                                                    .forEach(existing -> {
                                                        EventEffects.onEventEnd(
                                                                level, existing, village, data);
                                                        existing.setStatus(
                                                                VillageEvent.EventStatus.ENDED);
                                                    });
                                            data.removeEndedEvents();

                                            // Start immediately
                                            VillageEvent event = new VillageEvent(
                                                    java.util.UUID.randomUUID(),
                                                    village.getId(), type,
                                                    VillageEvent.EventStatus.ACTIVE,
                                                    level.getGameTime(),
                                                    level.getGameTime() + type.getDurationTicks(),
                                                    new java.util.ArrayList<>()
                                            );
                                            data.addEvent(event);
                                            EventEffects.onEventStart(level, event, village, data);
                                            data.setDirty();

                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "Forced " + type.name().replace("_", " ")
                                                            + " in village '" + villageName + "'"), true);
                                            return 1;
                                        })
                                )
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
                                .then(Commands.literal("setarmor")
                                        .then(Commands.argument("village", StringArgumentType.string())
                                                .then(Commands.argument("slot", StringArgumentType.word())
                                                        .then(Commands.argument("item", IdentifierArgument.id())
                                                                .executes(ctx -> {
                                                                    String villageName = StringArgumentType.getString(ctx, "village");
                                                                    String slotStr = StringArgumentType.getString(ctx, "slot");
                                                                    Identifier itemId = IdentifierArgument.getId(ctx, "item");
                                                                    VillageSavedData data = VillageSavedData.get(ctx.getSource().getLevel());

                                                                    EquipmentSlot slot;
                                                                    try {
                                                                        slot = EquipmentSlot.valueOf(slotStr.toUpperCase());
                                                                    } catch (IllegalArgumentException e) {
                                                                        ctx.getSource().sendFailure(
                                                                                Component.literal("Invalid slot. Use HEAD, CHEST, LEGS, or FEET")
                                                                        );
                                                                        return 0;
                                                                    }

                                                                    Item item = BuiltInRegistries.ITEM.get(itemId).get().value();
                                                                    if (item == Items.AIR) {
                                                                        ctx.getSource().sendFailure(
                                                                                Component.literal("Unknown item: " + itemId)
                                                                        );
                                                                        return 0;
                                                                    }

                                                                    Optional<Village> village = data.getVillageByName(villageName);
                                                                    if (village.isEmpty()) {
                                                                        ctx.getSource().sendFailure(
                                                                                Component.literal("Village not found: " + villageName)
                                                                        );
                                                                        return 0;
                                                                    }

                                                                    village.get().setPreferredArmorPiece(slot, item);
                                                                    data.setDirty();
                                                                    ctx.getSource().sendSuccess(
                                                                            () -> Component.literal("Set " + slot.name() + " armor to "
                                                                                    + itemId + " for " + villageName),
                                                                            true
                                                                    );
                                                                    return 1;
                                                                })
                                                        )
                                                )
                                        )
                                )


                        )

                        .then(Commands.literal("assign_npc")
                                .executes(ctx -> {
                                    ServerPlayer player = (ServerPlayer) ctx.getSource().getEntity();
                                    ServerLevel level = ctx.getSource().getLevel();
                                    BlockPos pos = player.blockPosition();
                                    VillageSavedData data = VillageSavedData.get(level); // was missing


                                    // Find building at player position
                                    Optional<Building> building = VillageSavedData.get(level).getBuildingAt(pos);
                                    if (building.isEmpty()) {
                                        ctx.getSource().sendFailure(Component.literal("You are not inside a building."));
                                        return 0;
                                    }

                                    // Find nearest townsperson within 10 blocks
                                    List<TownspersonMob> nearby = level.getEntitiesOfClass(
                                            TownspersonMob.class,
                                            new AABB(pos).inflate(10)
                                    );

                                    if (nearby.isEmpty()) {
                                        ctx.getSource().sendFailure(Component.literal("No townsperson nearby."));
                                        return 0;
                                    }

                                    TownspersonMob mob = nearby.get(0);
                                    Optional<Village> village = data.getAllVillages().stream()
                                            .filter(v -> v.getBuildingIds().contains(building.get().getId()))
                                            .findFirst();

                                    String villageName = village.map(Village::getName).orElse("");

                                    mob.assignToBuilding(building.get().getId(), villageName);
                                    // Set profession based on building type
                                    Profession profession = getProfessionForType(building.get().getType());
                                    if (profession != null) {
                                        mob.setProfession(profession);
                                    } else {
                                        // Default to merchant if no profession mapped
                                        mob.setProfession(Profession.MERCHANT);
                                    }


                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Assigned " + mob.getDisplayName().getString()
                                                    + " to " + building.get().getName()
                                                    + " as " + mob.getProfession().getDisplayName()),
                                            true
                                    );
                                    return 1;
                                })
                        )
        );
    }


    private static int villageNeeds(CommandSourceStack src, String villageName) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        data.getVillageByName(villageName).ifPresentOrElse(village -> {
            src.sendSuccess(() -> Component.literal(
                    "=== Needs for " + villageName + " ==="), false);

            // Prosperity score
            int prosperity = VillageEventScheduler.getProsperity(
                    level, village, data);
            src.sendSuccess(() -> Component.literal(
                    "Prosperity: " + prosperity + "/100"), false);

            // Active event if any
            data.getActiveEventForVillage(village.getId())
                    .ifPresent(event -> src.sendSuccess(() ->
                                    Component.literal("Active Event: "
                                            + event.getType().name().replace("_", " ")
                                            + " (" + (int)(event.getProgress(
                                            level.getGameTime()) * 100) + "% complete)"),
                            false));

            if (village.getNeeds().isEmpty()) {
                src.sendSuccess(() -> Component.literal(
                        "  Not yet computed — waiting for next tick"), false);
                return;
            }

            village.getNeeds().forEach((category, need) ->
                    src.sendSuccess(() -> Component.literal(
                            "  " + category.name()
                                    + ": " + need.getLevel()
                                    + " (" + need.getAvailable()
                                    + "/" + need.getRequired() + ")"
                                    + (need.getDeficit() > 0
                                    ? " deficit=" + need.getDeficit() : "")
                    ), false)
            );
        }, () -> src.sendFailure(
                Component.literal("No village named '" + villageName + "'.")));
        return 1;
    }



    private static int forceEvent(CommandSourceStack src,
                                  String villageName, String eventTypeName) {
        ServerLevel level = src.getLevel();
        VillageSavedData data = VillageSavedData.get(level);

        Village village = data.getVillageByName(villageName).orElse(null);
        if (village == null) {
            src.sendFailure(Component.literal(
                    "No village named '" + villageName + "'."));
            return 0;
        }

        VillageEvent.EventType type;
        try {
            type = VillageEvent.EventType.valueOf(
                    eventTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal(
                    "Invalid event type. Valid: "
                            + java.util.Arrays.stream(VillageEvent.EventType.values())
                            .map(VillageEvent.EventType::name)
                            .collect(java.util.stream.Collectors.joining(", "))));
            return 0;
        }

        // Cancel any existing event
        data.getActiveEventsForVillage(village.getId())
                .forEach(existing -> {
                    EventEffects.onEventEnd(level, existing, village, data);
                    existing.setStatus(VillageEvent.EventStatus.ENDED);
                });
        data.removeEndedEvents();

        // Start immediately — no announcement delay
        VillageEvent event = new VillageEvent(
                java.util.UUID.randomUUID(),
                village.getId(),
                type,
                VillageEvent.EventStatus.ACTIVE,
                level.getGameTime(),
                level.getGameTime() + type.getDurationTicks(),
                new java.util.ArrayList<>()
        );
        data.addEvent(event);
        EventEffects.onEventStart(level, event, village, data);
        data.setDirty();

        src.sendSuccess(() -> Component.literal(
                "Forced " + type.name().replace("_", " ")
                        + " in village '" + villageName + "'"), true);
        return 1;
    }
    private static int resetRoads(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        VillageSavedData villageData = VillageSavedData.get(level);
        CaravanSavedData caravanData = CaravanSavedData.get(level);

        // Despawn all caravans first
        caravanData.getAllCaravans().forEach(c -> {
            if (c.getMerchantEntityId() != null) {
                var e = level.getEntity(c.getMerchantEntityId());
                if (e != null) e.discard();
            }
            c.getGuardEntityIds().stream()
                    .map(level::getEntity)
                    .filter(Objects::nonNull)
                    .forEach(net.minecraft.world.entity.Entity::discard);
            if (c.getCartEntityId() != null) {
                var e = level.getEntity(c.getCartEntityId());
                if (e != null) e.discard();
            }
        });

        // Count before clearing
        int routeCount = villageData.getAllTradeRoutes().size();
        int roadCount  = villageData.getAllTradeRoads().size();

        // Clear all trade data
        villageData.getAllTradeRoutes()
                .forEach(r -> villageData.removeTradeRoute(
                        r.getRouteId()));
        villageData.getAllTradeRoads()
                .forEach(r -> villageData.removeTradeRoad(
                        r.getRoadId()));
        villageData.setDirty();

        // Clear all caravans
        caravanData.getAllCaravans()
                .forEach(c -> caravanData.removeCaravan(
                        c.getCaravanId()));
        caravanData.setDirty();

        // Re-establish routes between existing villages
        villageData.getAllVillages().forEach(village ->
                TradeRouteManager.establishRoutes(
                        level, village, villageData));

        src.sendSuccess(() -> Component.literal(
                        "Reset " + routeCount + " routes and "
                                + roadCount + " roads.\n"
                                + "Re-established routes for "
                                + villageData.getAllVillages().size()
                                + " villages.\n"
                                + "New routes: "
                                + villageData.getAllTradeRoutes().size()),
                false);

        return 1;
    }
}
