package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Event.EventEffects;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class PlayerEventProximityHandler {

    private static final Map<UUID, UUID> lastEventForPlayer =
            new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        if (level.getGameTime() % 100 != 0) return; // check every 5s

        VillageSavedData data = VillageSavedData.get(level);

        for (Village village : data.getAllVillages()) {
            data.getActiveEventForVillage(village.getId())
                    .ifPresent(activeEvent -> {
                        boolean inVillage = village.getBounds(data)
                                .map(b -> b.inflate(32).contains(
                                        player.getX(), player.getY(), player.getZ()))
                                .orElse(false);

                        if (!inVillage) return;

                        // Only apply buff once per event
                        UUID lastEvent = lastEventForPlayer.get(
                                player.getUUID());
                        if (activeEvent.getId().equals(lastEvent)) return;

                        lastEventForPlayer.put(
                                player.getUUID(), activeEvent.getId());
                        EventEffects.applyPlayerBuff(player, activeEvent);
                    });
        }
    }
}
