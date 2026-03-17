package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class InnBedEvent {
    @SubscribeEvent
    public static void onPlayerSleep(CanContinueSleepingEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        VillageSavedData data = VillageSavedData.get(level);
        long currentTick = level.getGameTime();

        if (!data.hasRentedRoom(player.getUUID(), currentTick)) return;

        BlockPos rentedBed = data.getRentedBed(player.getUUID()).orElse(null);
        if (rentedBed == null) return;

        // Allow sleep if this is the player's rented bed
        if (event.getEntity().blockPosition().equals(rentedBed)) {
            event.mayContinueSleeping(); // clear any vanilla block
            // Vanilla will handle the rest
        }
    }
}
