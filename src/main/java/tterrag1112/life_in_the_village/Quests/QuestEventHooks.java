package tterrag1112.life_in_the_village.Quests;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;

/**
 * F2b-1 — the F2 quest engine's world-event hooks. The mob-death source for the
 * {@link Objective.Hunt} (MOB_DEATH) objective. As of the F2b-2 re-seat this is the SOLE
 * player mob-death source: the legacy {@code QuestProgressEvents} handler was retired and
 * guild Hunt quests now advance through here (party-member kills are bridged to the leader
 * by {@code PartyQuestTracker}). The other guild kinds are POLL-mode (checked at turn-in),
 * and the escort source is {@code GuildWorkerBehavior} (ESCORT_ARRIVED).
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class QuestEventHooks {

    private QuestEventHooks() {}

    @SubscribeEvent
    public static void onMobDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel)) return;
        String mobId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString();
        QuestEvents.notify(player, QuestContext.mobDeath(mobId));
    }
}
