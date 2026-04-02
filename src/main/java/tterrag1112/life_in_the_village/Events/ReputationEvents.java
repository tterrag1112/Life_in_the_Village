package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;

/**
 * Forge event hooks that affect village reputation.
 *
 * <h3>IMPORTANT: routing rule</h3>
 * All reputation mutations MUST go through {@link ReputationManager}.
 * This class must NEVER call {@code village.modifyReputation()} or
 * {@code data.setDirty()} for reputation changes directly.
 *
 * <h3>Why this matters</h3>
 * <ul>
 *   <li>Creating fresh reputation records for new player-village pairs</li>
 *   <li>Applying the correct delta from {@code ChangeReason}</li>
 *   <li>Detecting tier transitions and notifying the player</li>
 *   <li>Marking data dirty</li>
 * </ul>
 * Bypassing it (as the old code did) meant reputation changes from
 * attacking NPCs used hardcoded deltas that didn't match the
 * {@code ChangeReason} enum, skipped tier-change notifications, and
 * couldn't be audited centrally.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class ReputationEvents {

    /**
     * Player attacks a townsperson — routes through
     * {@link ReputationManager#onAttackedNpc} which uses
     * {@code ChangeReason.ATTACKED_NPC} (delta: -50).
     */
    @SubscribeEvent
    public static void onLivingHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof TownspersonMob townsperson)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!(townsperson.level() instanceof ServerLevel level)) return;

        townsperson.getAssignedVillageName().ifPresent(villageName -> {
            VillageSavedData data = VillageSavedData.get(level);
            data.getVillageByName(villageName).ifPresent(village ->
                    ReputationManager.onAttackedNpc(
                            player, village.getId(), level));
        });
    }

    /**
     * Convenience method for systems that need to grant reputation
     * across ALL villages a player has interacted with.
     *
     * Routes each village through {@link ReputationManager#onDonationMade}
     * as a generic positive change. Callers that need a different reason
     * should call the appropriate ReputationManager method directly.
     *
     * @param player the player receiving reputation
     * @param level  server level
     * @param data   village saved data
     */
    public static void modifyReputationForAllVillages(
            ServerPlayer player,
            ServerLevel level,
            VillageSavedData data) {
        data.getAllVillages().forEach(village ->
                ReputationManager.onDonationMade(
                        player, village.getId(), level));
    }
}