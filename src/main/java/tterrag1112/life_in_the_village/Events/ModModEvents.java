package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import tterrag1112.life_in_the_village.Blocks.custom.GuardPostBlock;
import tterrag1112.life_in_the_village.Commands.*;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyleLoader;
import tterrag1112.life_in_the_village.Kingdom.KingdomTitleRegistry;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.*;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.ModBuildings;
import tterrag1112.life_in_the_village.Village.Economy.Resources.BlacksmithRecipeRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Resources.MiningYieldRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class ModModEvents {

    @SubscribeEvent
    public static void registerRegistries(NewRegistryEvent event){
        event.register(ModBuildings.BUILDING_REGISTRY);

    }
    @SubscribeEvent
    public static void onAddReloadListeners(AddClientReloadListenersEvent event) {
        System.out.println("Registering reload listeners");

        event.addListener(
                Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "npc_names"),
                NpcNameRegistry.INSTANCE
        );

    }
    @SubscribeEvent
    public static void onAddServerReloadListeners(AddServerReloadListenersEvent event){
        event.addListener(Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "castle_styles"),
                CastleStyleLoader.INSTANCE);


    }
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        ResourceManager manager = server.getResourceManager();

        System.out.println("Loading village types...");
        manager.listResources("village_types",
                path -> path.getPath().endsWith(".json")
        ).forEach((loc, res) -> System.out.println("  Found: " + loc));

        VillageTypeRegistry.INSTANCE.loadFromServer(manager);
        MarketPriceRegistry.INSTANCE.loadFromServer(server.getResourceManager());
        MiningYieldRegistry.INSTANCE.loadFromServer(server.getResourceManager());
        BlacksmithRecipeRegistry.INSTANCE.loadFromServer(server.getResourceManager());
        KingdomTitleRegistry.INSTANCE.loadFromServer(server.getResourceManager());




    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!(event.getState().getBlock() instanceof GuardPostBlock)) return;

        BlockPos pos = event.getPos();
        VillageSavedData data = VillageSavedData.get(serverLevel);
        data.getAllVillages().forEach(v -> v.removeGuardPost(pos));
        data.setDirty();
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event){
        final PayloadRegistrar registrar = event.registrar("1")
                .executesOn(HandlerThread.MAIN);
        registrar.playToServer(KeyData.TYPE, KeyData.STREAM_CODEC, KeyData::handle);
        registrar.playToClient(ManaData.TYPE, ManaData.STREAM_CODEC, ServerPayloadHandler::handleManaOnClient);
        registrar.playToClient(SyncBuildingsPacket.TYPE, SyncBuildingsPacket.STREAM_CODEC, SyncBuildingsPacket::handle);
        registrar.playToClient(StockpileContentsPacket.TYPE, StockpileContentsPacket.CODEC, StockpileContentsPacket::handle);
        registrar.playToClient(BuilderInventoryPacket.TYPE, BuilderInventoryPacket.CODEC, BuilderInventoryPacket::handle);
        registrar.playToClient(OpenTradeScreenPacket.TYPE, OpenTradeScreenPacket.CODEC, OpenTradeScreenPacket::handle);
        registrar.playToServer(TradeActionPacket.TYPE, TradeActionPacket.CODEC, TradeActionPacket::handle);
        registrar.playToServer(
                KingdomActionPacket.TYPE,
                KingdomActionPacket.CODEC,
                KingdomActionPacket::handle);

        registrar.playToClient(
                OpenKingdomBookPacket.TYPE,
                OpenKingdomBookPacket.CODEC,
                OpenKingdomBookPacket::handle);
        registrar.playToClient(
                SyncKingdomPacket.TYPE,
                SyncKingdomPacket.STREAM_CODEC,
                SyncKingdomPacket::handle);

        registrar.playToClient(
                OpenVillageBookPacket.TYPE,
                OpenVillageBookPacket.CODEC,
                OpenVillageBookPacket::handle);
        registrar.playToServer(
                VillageActionPacket.TYPE,
                VillageActionPacket.CODEC,
                VillageActionPacket::handle);

        registrar.playToServer(
                CompanyActionPacket.TYPE,
                CompanyActionPacket.CODEC,
                CompanyActionPacket::handle);
        registrar.playToClient(
                OpenCompanyManagementPacket.TYPE,
                OpenCompanyManagementPacket.CODEC,
                OpenCompanyManagementPacket::handle);
        registrar.playToClient(
                OpenCompanyWorkerPacket.TYPE,
                OpenCompanyWorkerPacket.CODEC,
                OpenCompanyWorkerPacket::handle);
        registrar.playToClient(
                OpenGuildScreenPacket.TYPE,
                OpenGuildScreenPacket.CODEC,
                OpenGuildScreenPacket::handle);

        registrar.playToServer(
                GuildActionPacket.TYPE,
                GuildActionPacket.CODEC,
                GuildActionPacket::handle);

    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BuildingCommand.register(event.getDispatcher());
        FarmPlotCommands.onRegisterCommands(event);
        KingdomCommands.onRegisterCommands(event);
        GuildCommands.onRegisterCommands(event);
        OrderCommand.register(event.getDispatcher());
        CastleCommand.register(event.getDispatcher());
        CastleDesignCommand.register(event.getDispatcher());
        DebugTickCommand.register(event.getDispatcher());

    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            VillageSavedData data = VillageSavedData.get(level);

            List<Building> buildings = data.getAllBuildings();
            List<Village> villages = data.getAllVillages();
            List<TradeRoute> tradeRoutes = data.getAllTradeRoutes();
            PacketDistributor.sendToPlayer(player, new SyncBuildingsPacket(buildings, villages, tradeRoutes));
            PacketDistributor.sendToPlayer(player,
                    new SyncKingdomPacket(
                            data.getAllKingdoms()));
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ServerLevel level = (ServerLevel) player.level();
        VillageSavedData data = VillageSavedData.get(level);

        // Sync buildings
        PacketDistributor.sendToPlayer(player,
                new SyncBuildingsPacket(
                        data.getAllBuildings(),
                        data.getAllVillages(),
                        data.getAllTradeRoutes()));

        // Sync kingdoms — ensures ClientKingdomCache
        // is populated even after full game restart
        PacketDistributor.sendToPlayer(player,
                new SyncKingdomPacket(
                        data.getAllKingdoms()));


        // Clear any warnings where reputation has recovered
        VillageWarningSystem.checkAndClearWarnings(
                player.getUUID(), data);

        // Notify player of any active warnings
        List<Village> warned = VillageWarningSystem
                .getWarnedVillages(player.getUUID(), data);
        if (!warned.isEmpty()) {
            player.displayClientMessage(
                    Component.literal("You have active warnings in "
                                    + warned.size() + " village"
                                    + (warned.size() > 1 ? "s" : "") + ": "
                                    + warned.stream()
                                    .map(Village::getName)
                                    .collect(Collectors.joining(", ")))
                            .withStyle(net.minecraft.ChatFormatting.RED),
                    false);
        }
        long currentTick = level.getGameTime();
        HousePurchaseManager.processTax(player, level, currentTick);
    }


    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel overworld = event.getServer().overworld();
        AdventurerSavedData data = AdventurerSavedData.get(overworld);

        // Despawn all spawned groups and mark as unspawned before save
        data.getAllGroups().forEach(group -> {
            if (group.isSpawned()) {
                group.getSpawnedEntityIds().stream()
                        .map(overworld::getEntity)
                        .filter(Objects::nonNull)
                        .forEach(net.minecraft.world.entity.Entity::discard);
                group.getSpawnedEntityIds().clear();
                group.setSpawned(false);
            }
        });

        data.setDirty();
    }

    private static final java.util.Map<java.util.UUID, java.util.Map<Integer, net.minecraft.world.item.ItemStack>>
            containerSnapshots = new java.util.HashMap<>();

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        var menu = event.getContainer();
        java.util.Map<Integer, ItemStack> snapshot = new java.util.HashMap<>();
        // Only snapshot non-player-inventory slots
        for (int i = 0; i < menu.slots.size(); i++) {
            net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
            // Player inventory slots contain the player's own Container — skip them
            if (slot.container == player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) snapshot.put(i, stack.copy());
        }
        containerSnapshots.put(player.getUUID(), snapshot);
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        var snapshot = containerSnapshots.remove(player.getUUID());
        if (snapshot == null) return;

        var menu = event.getContainer();

        // Find the block pos from any non-player-inventory slot's container
        net.minecraft.core.BlockPos containerPos = null;
        for (net.minecraft.world.inventory.Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) continue;
            if (slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                containerPos = be.getBlockPos();
                break;
            }
        }
        if (containerPos == null) return;

        VillageSavedData data = VillageSavedData.get(level);
        var village = data.getVillageAt(containerPos).orElse(null);
        if (village == null) return;

        var building = data.getBuildingAt(containerPos).orElse(null);
        if (building == null) return;

        boolean playerOwnsBuilding = data.isPlayerOwned(building.getId())
                && data.getPropertyForBuilding(building.getId())
                .map(p -> p.playerId().equals(player.getUUID()))
                .orElse(false);

        if (!playerOwnsBuilding) {
            // Look for slots where item count decreased
            boolean stoleItems = false;
            for (int i = 0; i < menu.slots.size(); i++) {
                net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
                if (slot.container == player.getInventory()) continue;

                ItemStack current = slot.getItem();
                ItemStack before  = snapshot.getOrDefault(i, ItemStack.EMPTY);

                // Count decreased = items were removed
                int removedCount = before.getCount() - current.getCount();
                if (removedCount > 0 && !before.isEmpty()) {
                    stoleItems = true;
                    break;
                }
            }

            if (stoleItems) {
                tterrag1112.life_in_the_village.Village.VillageWarningSystem
                        .issueWarning(player.getUUID(), village.getId(), level, data);
                tterrag1112.life_in_the_village.Village.Reputation.ReputationManager
                        .onStolenFromContainer(player, village.getId(), level);
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                        "You have been seen stealing from "
                                                + village.getName() + "!")
                                .withStyle(net.minecraft.ChatFormatting.RED),
                        false);
            }
        }

        // Diff: find slots where count increased
        for (int i = 0; i < menu.slots.size(); i++) {
            net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory()) continue;

            ItemStack current = slot.getItem();
            if (current.isEmpty()) continue;

            ItemStack before = snapshot.getOrDefault(i, ItemStack.EMPTY);
            int addedCount = current.getCount() - before.getCount();
            if (addedCount <= 0) continue;

            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(current.getItem()).toString();

            tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager
                    .onItemDelivered(player, itemId, addedCount, level);
            CraftingOrderInteraction.onItemsDeposited(
                    player, village.getId(), itemId, addedCount, level);
        }
    }
}
