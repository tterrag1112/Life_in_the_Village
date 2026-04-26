package tterrag1112.life_in_the_village.Npc.Health;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;

/**
 * Bridges combat damage onto the {@link HealthComponent}. Spec line
 * 74 — combat injuries become MINOR_WOUND / SERIOUS_WOUND /
 * BROKEN_BONE per damage bracket.
 *
 * <p>The crime hooks ({@code CrimeDetectionHooks}) already listen on
 * {@code LivingIncomingDamageEvent} for ASSAULT crime filing. This
 * runs alongside them — order doesn't matter because both produce
 * additive side effects (memory + health onset).</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class InjuryHooks {

    private InjuryHooks() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob victim)) return;
        if (!(victim.level() instanceof ServerLevel level)) return;
        float amount = event.getAmount();
        if (amount < 1.0f) return;
        long now = level.getGameTime();

        // Damage brackets per spec line 74. The damage source kind
        // selects the condition family: blunt force breaks bones, fire
        // burns, blade / generic produces wounds.
        boolean isFire = event.getSource() != null && event.getSource().is(
                net.minecraft.tags.DamageTypeTags.IS_FIRE);
        boolean isFall = event.getSource() != null && event.getSource().is(
                net.minecraft.tags.DamageTypeTags.IS_FALL);

        HealthCondition type;
        int severity;
        if (isFire) {
            type = HealthCondition.BURN;
            severity = amount >= 8f ? 4 : amount >= 4f ? 3 : 2;
        } else if (isFall || amount >= 12f) {
            type = HealthCondition.BROKEN_BONE;
            severity = 3;
        } else if (amount >= 6f) {
            type = HealthCondition.SERIOUS_WOUND;
            severity = 4;
        } else {
            type = HealthCondition.MINOR_WOUND;
            severity = 2;
        }
        HealthTicker.addCondition(victim, type, severity, now);
    }
}
