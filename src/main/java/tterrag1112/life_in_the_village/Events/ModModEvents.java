package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import tterrag1112.life_in_the_village.Commands.BuildingCommand;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Magic.ManaSystem.ModManaHandler;
import tterrag1112.life_in_the_village.Networking.*;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.ModBuildings;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;


@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class ModModEvents {

    @SubscribeEvent
    public static void registerRegistries(NewRegistryEvent event){
        event.register(ModBuildings.BUILDING_REGISTRY);

    }
    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event){
        final PayloadRegistrar registrar = event.registrar("1")
                .executesOn(HandlerThread.MAIN);
        registrar.playToServer(KeyData.TYPE, KeyData.STREAM_CODEC, KeyData::handle);
        registrar.playToClient(ManaData.TYPE, ManaData.STREAM_CODEC, ServerPayloadHandler::handleManaOnClient);
        registrar.playToClient(SyncBuildingsPacket.TYPE, SyncBuildingsPacket.STREAM_CODEC, SyncBuildingsPacket::handle);

    }
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BuildingCommand.register(event.getDispatcher());
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
