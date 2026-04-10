package tterrag1112.life_in_the_village.Kingdom;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Enforces the KINGS_PEACE kingdom law.
 *
 * <p>Attacking an NPC already costs reputation via
 * {@code ReputationEvents.onLivingHurt}. KINGS_PEACE escalates this: the
 * crime propagates to every village in the same kingdom, making violence
 * under the crown's peace a kingdom-wide infamy event rather than a purely
 * local grievance.</p>
 *
 * <p>Priority is set above the base reputation hook so the local penalty
 * fires first; this handler only adds the kingdom-wide spread.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class KingsPeaceHandler {

    private KingsPeaceHandler() {}

    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getEntity() instanceof TownspersonMob victim))           return;
        if (!(victim.level() instanceof ServerLevel level))                  return;

        VillageSavedData data = VillageSavedData.get(level);

        Optional<Village> victimVillage = victim.getAssignedVillageName()
                .flatMap(data::getVillageByName);
        if (victimVillage.isEmpty()) return;

        UUID victimVillageId = victimVillage.get().getId();
        if (!KingdomLawEffects.isKingsPeaceActive(data, victimVillageId)) return;

        // Spread the penalty across the entire kingdom (excluding the victim's
        // own village, which the base ReputationEvents handler already hit).
        Kingdom kingdom = data.getKingdomForVillage(victimVillageId).orElse(null);
        if (kingdom == null) return;

        for (UUID siblingId : kingdom.getVillageIds()) {
            if (siblingId.equals(victimVillageId)) continue;
            ReputationManager.onAttackedNpc(player, siblingId, level);
        }

        player.displayClientMessage(
                net.minecraft.network.chat.Component
                        .literal("Your violence breaks the King's Peace — word will spread.")
                        .withStyle(net.minecraft.ChatFormatting.RED),
                true);
    }
}