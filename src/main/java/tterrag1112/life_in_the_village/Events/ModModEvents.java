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
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
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
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Kingdom.Castle.CastleStyleLoader;
import tterrag1112.life_in_the_village.Kingdom.KingdomTitleRegistry;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.HousePurchaseManager;
import tterrag1112.life_in_the_village.Village.Economy.BusinessPurchaseManager;
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
import java.util.Optional;
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
        event.addListener(Identifier.fromNamespaceAndPath(Life_in_the_village.MODID, "variant_manifests"),
                tterrag1112.life_in_the_village.Village.Decoration.Variants
                        .VariantManifestLoader.INSTANCE);
        // Track A2 — V2 CultureRegistry deleted; cultures register from
        // CultureRegistry.ensureInit() (Java starters) until NPC Phase 6
        // re-introduces JSON-driven custom cultures.
        event.addListener(Identifier.fromNamespaceAndPath(
                        Life_in_the_village.MODID, "v2_structure_availability"),
                tterrag1112.life_in_the_village.Village.Planning.V2.Layer3
                        .StructureAvailabilityRegistry.INSTANCE);


    }
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        ResourceManager manager = server.getResourceManager();

        // Phase 1: register the three default NpcLifeEvent dispatchers
        // (memory, mood, trait drift). Idempotent.
        tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.registerDefaults();
        // Track D1: kingdom-tier event bus. Idempotent; D1 ships with
        // no default subscribers — D2 / D3 register stability /
        // legitimacy / office-population dispatchers later.
        tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus.registerDefaults();

        // Phase 3 task 24: register building enter/leave listeners that
        // drive greeter assignment + close refusal logic. Listener
        // registration is idempotent because BuildingPresenceTracker's
        // listener lists are session-scoped and ServerStartingEvent
        // fires once per server lifecycle.
        tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker
                .registerEnterListener(tterrag1112.life_in_the_village.Npc.BusinessFront
                        .GreeterAssignment::onPlayerEntered);
        tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker
                .registerLeaveListener(tterrag1112.life_in_the_village.Npc.BusinessFront
                        .GreeterAssignment::onPlayerLeft);
        // Phase 3 task 19: trespassing detection on the same enter
        // listener. CrimeDetectionHooks decides whether the entry is
        // criminal (RESIDENCE owned by another player).
        tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker
                .registerEnterListener(tterrag1112.life_in_the_village.Npc.Crime
                        .CrimeDetectionHooks::onPlayerEnteredBuilding);

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
        // Track D3.5A — petition resolution / withdrawal.
        registrar.playToServer(
                tterrag1112.life_in_the_village.Networking.KingdomPetitionPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.KingdomPetitionPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.KingdomPetitionPacket::handle);
        // Track D3.5B — petition submission (carries full payload bytes).
        registrar.playToServer(
                tterrag1112.life_in_the_village.Networking.KingdomPetitionSubmitPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.KingdomPetitionSubmitPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.KingdomPetitionSubmitPacket::handle);

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

        // Track C3.1 — road proposals book screen.
        registrar.playToClient(
                tterrag1112.life_in_the_village.Networking.OpenRoadProposalsPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.OpenRoadProposalsPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.OpenRoadProposalsPacket::handle);
        registrar.playToServer(
                VillageActionPacket.TYPE,
                VillageActionPacket.CODEC,
                VillageActionPacket::handle);

        registrar.playToServer(
                BusinessActionPacket.TYPE,
                BusinessActionPacket.CODEC,
                BusinessActionPacket::handle);
        registrar.playToClient(
                OpenBusinessManagementPacket.TYPE,
                OpenBusinessManagementPacket.CODEC,
                OpenBusinessManagementPacket::handle);
        registrar.playToClient(
                OpenBusinessWorkerPacket.TYPE,
                OpenBusinessWorkerPacket.CODEC,
                OpenBusinessWorkerPacket::handle);
        registrar.playToClient(
                OpenGuildScreenPacket.TYPE,
                OpenGuildScreenPacket.CODEC,
                OpenGuildScreenPacket::handle);

        registrar.playToServer(
                GuildActionPacket.TYPE,
                GuildActionPacket.CODEC,
                GuildActionPacket::handle);

        // P0a-13 — repaint UI
        registrar.playToClient(
                tterrag1112.life_in_the_village.Networking.OpenRepaintScreenPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.OpenRepaintScreenPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.OpenRepaintScreenPacket::handle);
        registrar.playToServer(
                tterrag1112.life_in_the_village.Networking.RepaintActionPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.RepaintActionPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.RepaintActionPacket::handle);

        registrar.playToServer(
                RequestKingdomMapSyncPacket.TYPE,
                RequestKingdomMapSyncPacket.CODEC,
                RequestKingdomMapSyncPacket::handle);

        // Track 4 — gift primer (SocialVerbsPanel "Gift" button).
        registrar.playToServer(
                tterrag1112.life_in_the_village.Networking.PrimeGiftPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.PrimeGiftPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.PrimeGiftPacket::handle);

        // Track 5a — ScribeCounter screen + composer actions.
        registrar.playToClient(
                tterrag1112.life_in_the_village.Networking.OpenScribeCounterPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.OpenScribeCounterPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.OpenScribeCounterPacket::handle);
        registrar.playToServer(
                tterrag1112.life_in_the_village.Networking.ScribeCounterActionPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.ScribeCounterActionPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.ScribeCounterActionPacket::handle);
        // Track 5a — StallLease screen + action.
        registrar.playToClient(
                tterrag1112.life_in_the_village.Networking.OpenStallLeasePacket.TYPE,
                tterrag1112.life_in_the_village.Networking.OpenStallLeasePacket.CODEC,
                tterrag1112.life_in_the_village.Networking.OpenStallLeasePacket::handle);
        registrar.playToServer(
                tterrag1112.life_in_the_village.Networking.StallLeaseActionPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.StallLeaseActionPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.StallLeaseActionPacket::handle);
        // Track 5a — Library screen.
        registrar.playToClient(
                tterrag1112.life_in_the_village.Networking.OpenLibraryScreenPacket.TYPE,
                tterrag1112.life_in_the_village.Networking.OpenLibraryScreenPacket.CODEC,
                tterrag1112.life_in_the_village.Networking.OpenLibraryScreenPacket::handle);
        registrar.playToClient(
                KingdomMapSyncPacket.TYPE,
                KingdomMapSyncPacket.STREAM_CODEC,
                KingdomMapSyncPacket::handle);
        registrar.playToServer(
                RequestContinentMapSyncPacket.TYPE,
                RequestContinentMapSyncPacket.CODEC,
                RequestContinentMapSyncPacket::handle);
        registrar.playToClient(
                ContinentMapSyncPacket.TYPE,
                ContinentMapSyncPacket.STREAM_CODEC,
                ContinentMapSyncPacket::handle);

        // ── NPC Profile Screen (Slice 3) ─────────────────────────────────────
        registrar.playToClient(
                OpenNpcProfilePacket.TYPE,
                OpenNpcProfilePacket.CODEC,
                OpenNpcProfilePacket::handle);
        registrar.playToClient(
                NpcProfileSyncPacket.TYPE,
                NpcProfileSyncPacket.CODEC,
                NpcProfileSyncPacket::handle);
        registrar.playToServer(
                RequestNpcProfileSyncPacket.TYPE,
                RequestNpcProfileSyncPacket.CODEC,
                RequestNpcProfileSyncPacket::handle);
        registrar.playToServer(
                NpcProfileActionPacket.TYPE,
                NpcProfileActionPacket.CODEC,
                NpcProfileActionPacket::handle);
        registrar.playToServer(
                tterrag1112.life_in_the_village.Networking.PlayerVerbInvokePacket.TYPE,
                tterrag1112.life_in_the_village.Networking.PlayerVerbInvokePacket.CODEC,
                tterrag1112.life_in_the_village.Networking.PlayerVerbInvokePacket::handle);
        registrar.playToServer(
                CloseNpcProfilePacket.TYPE,
                CloseNpcProfilePacket.CODEC,
                CloseNpcProfilePacket::handle);

        // Phase 3 task 24: business-front screen open packet.
        registrar.playToClient(
                OpenBusinessFrontPacket.TYPE,
                OpenBusinessFrontPacket.CODEC,
                OpenBusinessFrontPacket::handle);

    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BuildingCommand.register(event.getDispatcher());
        // Phase 6.0 — /lit brain dump <selector>
        tterrag1112.life_in_the_village.Commands.BrainCommands
                .register(event.getDispatcher());
        FarmPlotCommands.onRegisterCommands(event);
        KingdomCommands.onRegisterCommands(event);
        GuildCommands.onRegisterCommands(event);
        OrderCommand.register(event.getDispatcher());
        CastleCommand.register(event.getDispatcher());
        CastleDesignCommand.register(event.getDispatcher());
        DebugTickCommand.register(event.getDispatcher());
        FarmCommands.register(event.getDispatcher());
        BalanceTestCommands.register(event.getDispatcher());
        AtlasDebugCommand.register(event.getDispatcher());
        VillageTagsDebugCommand.register(event.getDispatcher());
        KingdomClaimDebugCommand.register(event.getDispatcher());
        // B2.7 — centralized help directory under /liv help + /litv help.
        LivHelpCommand.register(event.getDispatcher());
        PlacementFailuresDebugCommand.register(event.getDispatcher());
        RoadDebugCommand.register(event.getDispatcher());
        RoadGraphDebugCommand.register(event.getDispatcher());
        LayoutDebugCommand.register(event.getDispatcher());
        FeatureMapCommand.register(event.getDispatcher());
        SiteCommand.register(event.getDispatcher());
        PlaceCommand.register(event.getDispatcher());
        LayoutCommand.register(event.getDispatcher());
        // Track E1 — V2 layout introspection dump command.
        tterrag1112.life_in_the_village.Commands.LayoutDumpCommand
                .register(event.getDispatcher());
        SpawnCommand.register(event.getDispatcher());
        // Track A4 — ConfigCommand registered the V2 toggle subcommand
        // and was deleted along with V2Settings when V2 became the
        // only planner.
        NpcDebugCommand.register(event.getDispatcher());
        OfficeDebugCommand.register(event.getDispatcher());
        DialogueDebugCommand.register(event.getDispatcher());
        VerbDebugCommand.register(event.getDispatcher());
        GossipDebugCommand.register(event.getDispatcher());
        ScribalDebugCommand.register(event.getDispatcher());
        LetterBookDebugCommand.register(event.getDispatcher());
        EconomyDebugCommand.register(event.getDispatcher());
        LawDebugCommand.register(event.getDispatcher());
        BusinessDebugCommand.register(event.getDispatcher());
        CrimeDebugCommand.register(event.getDispatcher());
        ReligionDebugCommand.register(event.getDispatcher());
        HealthDebugCommand.register(event.getDispatcher());
        PlagueDebugCommand.register(event.getDispatcher());
        SimDebugCommand.register(event.getDispatcher());
        GuildAbstractDebugCommand.register(event.getDispatcher());
        BusinessAdminCommand.register(event.getDispatcher());
        ApprenticeDebugCommand.register(event.getDispatcher());
        RequestDebugCommand.register(event.getDispatcher());
        VisitorDebugCommand.register(event.getDispatcher());
        HistoryDebugCommand.register(event.getDispatcher());
        CultureDebugCommand.register(event.getDispatcher());
        EventDebugCommand.register(event.getDispatcher());
        AppearanceDebugCommand.register(event.getDispatcher());

    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tterrag1112.life_in_the_village.Npc.BusinessFront.BuildingPresenceTracker
                    .onPlayerLogout(player.getUUID());
        }
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

        // Find the block pos of the opened container
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

        // ── Ownership checks — no theft penalty for owned containers ─────────

        // 1. Player owns the building via property system
        boolean playerOwnsBuilding = data.isPlayerOwned(building.getId())
                && data.getPropertyForBuilding(building.getId())
                .map(p -> p.playerId().equals(player.getUUID()))
                .orElse(false);
        if (playerOwnsBuilding) return;

        // 2. Container is the player's own market stall chest
        final net.minecraft.core.BlockPos finalContainerPos = containerPos;
        boolean playerOwnsStall = data.getStallsForVillage(village.getId())
                .stream()
                .anyMatch(s -> s.isActive()
                        && s.getOwnerUUID().equals(player.getUUID())
                        && s.getChestPos().equals(finalContainerPos));
        if (playerOwnsStall) return;

        // ── Detect whether items were actually removed ────────────────────────
        boolean stoleItems = false;
        for (int i = 0; i < menu.slots.size(); i++) {
            net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory()) continue;

            ItemStack current = slot.getItem();
            ItemStack before  = snapshot.getOrDefault(i, ItemStack.EMPTY);

            int removedCount = before.getCount() - current.getCount();
            if (removedCount > 0 && !before.isEmpty()) {
                stoleItems = true;
                break;
            }
        }

        if (!stoleItems) return;

        // ── Witness check — only penalise if a guard or adventurer sees it ────
        TownspersonMob witness = findTheftWitness(level, player, containerPos);

        if (witness != null) {
            // Witnessed — apply full penalty and alert the witness
            tterrag1112.life_in_the_village.Village.VillageWarningSystem
                    .issueWarning(player.getUUID(), village.getId(), level, data);
            tterrag1112.life_in_the_village.Village.Reputation.ReputationManager
                    .onStolenFromContainer(player, village.getId(), level);

            witness.setCurrentActivity("Witnessed theft!");

            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                                    witness.getNpcName() + " saw you steal from "
                                            + village.getName() + "!")
                            .withStyle(net.minecraft.ChatFormatting.RED),
                    false);

            // Phase 3 task 19: file a CrimeReport so the constable can
            // investigate and a trial can follow. THEFT_MINOR vs.
            // THEFT_MAJOR uses an item-value heuristic: estimate the
            // total stolen value from the snapshot/current diff.
            long totalValue = estimateStolenValue(snapshot, menu);
            tterrag1112.life_in_the_village.Npc.Crime.CrimeType crimeType =
                    totalValue >= 20L
                            ? tterrag1112.life_in_the_village.Npc.Crime.CrimeType.THEFT_MAJOR
                            : tterrag1112.life_in_the_village.Npc.Crime.CrimeType.THEFT_MINOR;
            tterrag1112.life_in_the_village.Npc.Crime.CrimeReporter
                    .builder(crimeType, level)
                    .perpetrator(player.getUUID())
                    .reportedBy(witness.getUUID())
                    .location(containerPos)
                    .village(village.getId())
                    .note("Container theft (~" + totalValue + " bronze)")
                    .commit();
        }
        // No witness — theft succeeds silently, no reputation change
    }

    /**
     * Estimates total bronze value of items removed from the snapshot.
     * Uses {@link tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper#getBaseSellPrice}
     * which always returns a non-zero value.
     */
    private static long estimateStolenValue(java.util.Map<Integer, ItemStack> snapshot,
                                            net.minecraft.world.inventory.AbstractContainerMenu menu) {
        long total = 0L;
        for (int i = 0; i < menu.slots.size(); i++) {
            net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
            if (snapshot == null) continue;
            ItemStack before = snapshot.getOrDefault(i, ItemStack.EMPTY);
            if (before.isEmpty()) continue;
            ItemStack now = slot.getItem();
            int removed = before.getCount() - now.getCount();
            if (removed <= 0) continue;
            long perUnit = tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper
                    .getBaseSellPrice(before.getItem());
            total += perUnit * removed;
        }
        return total;
    }
    @SubscribeEvent
    public static void onTownspersonDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob townsperson)) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;

        // Check if this was a head farmer
        if (townsperson.getProfession() == Profession.FARMER) {
            Optional<Building> farmhouse = townsperson.getAssignedBuildingId()
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .filter(b -> b.getType() == BuildingType.FARMHOUSE);

            if (farmhouse.isPresent()) {
                BusinessPurchaseManager.handleFarmerDeath(townsperson, level);
            }
        }
    }
    /**
     * Looks for a guard or adventurer NPC within witness range of the theft.
     * Uses a simple range check — no line-of-sight for now, but the range
     * is kept tight (16 blocks) so it's easy to avoid by scouting first.
     *
     * Returns the first qualifying witness found, or null if unwitnessed.
     */
    private static tterrag1112.life_in_the_village.Entities.custom.TownspersonMob
    findTheftWitness(ServerLevel level,
                     ServerPlayer thief,
                     net.minecraft.core.BlockPos theftPos) {

        final double WITNESS_RANGE = 16.0;

        net.minecraft.world.phys.AABB searchBox =
                new net.minecraft.world.phys.AABB(theftPos).inflate(WITNESS_RANGE);

        return level.getEntitiesOfClass(
                        tterrag1112.life_in_the_village.Entities.custom.TownspersonMob.class,
                        searchBox,
                        npc -> {
                            // Only guards and adventurers act as witnesses
                            tterrag1112.life_in_the_village.Profession.Profession prof =
                                    npc.getProfession();
                            if (prof != tterrag1112.life_in_the_village.Profession.Profession.GUARD
                                    && prof != tterrag1112.life_in_the_village.Profession.Profession.ADVENTURER
                                    && prof != tterrag1112.life_in_the_village.Profession.Profession.GUILDWORKER) {
                                return false;
                            }
                            // Must not be asleep or otherwise blocked
                            if (npc.shouldBeHome()) return false;
                            // Must be roughly facing or near the thief
                            // (distance from NPC to theft pos, not from thief)
                            return npc.blockPosition().distSqr(theftPos)
                                    <= WITNESS_RANGE * WITNESS_RANGE;
                        })
                .stream()
                .findFirst()
                .orElse(null);
    }
}
