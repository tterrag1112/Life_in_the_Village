package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import tterrag1112.life_in_the_village.Blocks.custom.GuardPostBlock;
import tterrag1112.life_in_the_village.Commands.BuildingCommand;
import tterrag1112.life_in_the_village.Commands.FarmPlotCommands;
import tterrag1112.life_in_the_village.Commands.GuildCommands;
import tterrag1112.life_in_the_village.Commands.KingdomCommands;
import tterrag1112.life_in_the_village.Kingdom.KingdomTitleRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.NpcNameRegistry;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Magic.ManaSystem.ModManaHandler;
import tterrag1112.life_in_the_village.Networking.*;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.ModBuildings;
import tterrag1112.life_in_the_village.Village.Economy.Resources.BlacksmithRecipeRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Resources.MiningYieldRegistry;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

import java.util.List;


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
        registrar.playBidirectional(
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

    }

    @SubscribeEvent // on the mod event bus only on the physical client
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(
                KingdomActionPacket.TYPE,
                KingdomActionPacket::handle
        );
    }
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BuildingCommand.register(event.getDispatcher());
        FarmPlotCommands.onRegisterCommands(event);
        KingdomCommands.onRegisterCommands(event);
        GuildCommands.onRegisterCommands(event);

    }


    @SubscribeEvent
    public static void setPlayerManaOnRespawn(PlayerEvent.PlayerRespawnEvent event){
        Player player = event.getEntity();
        int manaLevel = player.getData(ModData.MANA);
        int penaltyAmount = ModManaHandler.DEATH_MANA_PENALTY_PERCENTAGE;
        ModManaHandler.decreaseManaForPlayer(player, 25);
    }

    @SubscribeEvent
    public static void setPlayerManaOnSpawn(PlayerEvent.PlayerLoggedInEvent event){
        Player player = event.getEntity();
        ModManaHandler.setManaForPlayer(player, 50);
        PacketDistributor.sendToPlayer(((ServerPlayer) player), new ManaData(0, 50));
    }

    @SubscribeEvent
    public static void setPlayersManaOnClone(final PlayerEvent.Clone event) {
        ServerPlayer player = ((ServerPlayer) event.getEntity());
        ModManaHandler.setManaForPlayer(player, event.getOriginal().getData(ModData.MANA));
    }
    @SubscribeEvent
    public static void setPlayersManaOnDimensionChange(final PlayerEvent.PlayerChangedDimensionEvent event) {
        PacketDistributor.sendToPlayer(((ServerPlayer) event.getEntity()), new ManaData(0, event.getEntity().getData(ModData.MANA)));
    }
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            List<Building> buildings = VillageSavedData.get(level).getAllBuildings();
            List<Village> villages = VillageSavedData.get(level).getAllVillages();
            PacketDistributor.sendToPlayer(player, new SyncBuildingsPacket(buildings, villages));
        }
    }




}
