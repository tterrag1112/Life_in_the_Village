package tterrag1112.life_in_the_village.Events;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Buildings.VillageExpansionManager;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRouteManager;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageWarningSystem;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class WorldEvents {

    // We don't want to run every single tick — track time ourselves
    // as a secondary guard on top of AdventurerSavedData's own interval check.
    private static int tickCounter = 0;
    private static final int CHECK_INTERVAL = 20; // once per second

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        if (tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        // Tick adventurer groups in the overworld.
        // If you want groups in other dimensions, add those levels here.
        ServerLevel overworld = event.getServer().overworld();
        AdventurerSavedData adventurerData = AdventurerSavedData.get(overworld);
        CaravanSavedData caravanData = CaravanSavedData.get(overworld);
        VillageSavedData villageData = VillageSavedData.get(overworld);

        adventurerData.tick(overworld, villageData);
        caravanData.tick(overworld, villageData);

        TradeRouteManager.tick(overworld, villageData,
                overworld.getGameTime());


        VillageWarningSystem.tickWarningSpread(
                overworld, villageData, overworld.getGameTime());
        VillageExpansionManager.tick(overworld, villageData,
                overworld.getGameTime());


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

    @SubscribeEvent
    public static void onPlayerLogin(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        VillageSavedData data = VillageSavedData.get(
                level.getServer().overworld());

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
    }
}
