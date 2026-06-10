package tterrag1112.life_in_the_village.Quests;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;

/**
 * F2b-1 — the F2 quest engine's world-event hooks. Currently just the mob-death source
 * for the {@link Objective.Hunt} (MOB_DEATH) objective. <b>Additive:</b> this is a
 * SEPARATE handler from the legacy {@code QuestProgressEvents.onMobDeath} (which is
 * untouched and still drives the legacy guild HUNT) — both observe the same kill. The
 * other guild kinds are POLL-mode (checked at turn-in via {@link QuestEvents#evaluate}),
 * so they need no event hook; the escort source is F2b-2.
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
