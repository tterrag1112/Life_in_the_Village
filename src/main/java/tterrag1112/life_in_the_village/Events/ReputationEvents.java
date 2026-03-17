package tterrag1112.life_in_the_village.Events;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class ReputationEvents {

    // Player attacks a townsperson — large reputation hit
    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob townsperson)) return;
        if (!(event.getSource().getEntity() instanceof Player player)) return;
        if (!(townsperson.level() instanceof ServerLevel serverLevel)) return;

        townsperson.getAssignedVillageName().ifPresent(villageName -> {
            VillageSavedData data = VillageSavedData.get(serverLevel);
            data.getVillageByName(villageName).ifPresent(village -> {
                village.modifyReputation(player.getUUID(), -25);
                data.setDirty();
                player.displayClientMessage(
                        Component.literal("Your reputation in " + villageName + " has decreased."),
                        true
                );
            });
        });
    }

    // Player completes a trade — small reputation gain
    public static void onTradeCompleted(Player player, TownspersonMob merchant) {
        if (!(merchant.level() instanceof ServerLevel serverLevel)) return;
        merchant.getAssignedVillageName().ifPresent(villageName -> {
            VillageSavedData data = VillageSavedData.get(serverLevel);
            data.getVillageByName(villageName).ifPresent(village -> {
                village.modifyReputation(player.getUUID(), 5);
                data.setDirty();
            });
        });
    }

    public static void modifyReputationForAllVillages(
            ServerPlayer player,
            ServerLevel level,
            VillageSavedData data,
            int amount) {

        data.getAllVillages().forEach(village -> {
            village.modifyReputation(player.getUUID(), amount);
            data.setDirty();
            player.displayClientMessage(
                    Component.literal("Your reputation has increased in "
                            + village.getName() + "."),
                    true
            );
        });
    }
}
