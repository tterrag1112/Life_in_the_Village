package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class QuestProgressEvents {

    @SubscribeEvent
    public static void onMobDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity()
                instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        PlayerGuildData guildData = PlayerGuildData.get(level);
        String mobType = net.minecraft.core.registries.BuiltInRegistries
                .ENTITY_TYPE.getKey(event.getEntity().getType()).toString();

        guildData.getActiveQuestsForPlayer(player.getUUID()).stream()
                .filter(q -> q.getType() == Quest.QuestType.HUNT
                        && mobType.equals(q.getTargetMob())
                        && q.getCurrentCount() < q.getTargetCount())
                .forEach(q -> {
                    q.setCurrentCount(q.getCurrentCount() + 1);
                    guildData.setDirty();

                    if (q.getCurrentCount() >= q.getTargetCount()) {
                        player.displayClientMessage(
                                Component.literal("Quest objective complete: "
                                        + q.getTitle()
                                        + " — return to the guild to claim reward!"),
                                true);
                    }
                });
    }
}