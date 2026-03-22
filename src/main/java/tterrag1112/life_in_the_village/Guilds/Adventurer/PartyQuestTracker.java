// src/main/java/tterrag1112/life_in_the_village/Entities/Party/PartyQuestTracker.java
package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Life_in_the_village;


import java.util.Optional;

/**
 * Hooks into mob death events to attribute kills to the player's active
 * quest regardless of whether the player or a party member landed the
 * killing blow.
 *
 * <h3>Attribution rules</h3>
 * <ul>
 *   <li>If a party member NPC kills a mob and the party leader has an
 *       active hunt quest targeting that mob type, the kill is credited
 *       to the player.</li>
 *   <li>The kill is also recorded on the NPC member for levelling.</li>
 *   <li>When the quest target count is reached, the player receives the
 *       completion notification.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class PartyQuestTracker {

    @SubscribeEvent
    public static void onMobDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;

        // Only care about mob kills where the attacker is a party member NPC
        if (!(event.getSource().getEntity()
                instanceof TownspersonMob attacker)) return;
        if (attacker.getCombatRole() == null) return;

        PlayerPartySavedData partyData = PlayerPartySavedData.get(level);
        Optional<PlayerParty> partyOpt =
                partyData.getPartyContaining(attacker.getUUID());
        if (partyOpt.isEmpty()) return;

        PlayerParty party = partyOpt.get();

        // Record kill on the NPC member for levelling
        partyData.recordMemberKill(attacker.getUUID());

        // Attribute to player's active quest
        ServerPlayer leader = level.getServer()
                .getPlayerList()
                .getPlayer(party.getLeaderPlayerId());
        if (leader == null) return;

        PlayerGuildData guildData = PlayerGuildData.get(level);
        String killedType = event.getEntity().getType()
                .builtInRegistryHolder().key().registry().toString();

        guildData.getActiveQuestsForPlayer(leader.getUUID())
                .stream()
                .filter(q -> q.getType() == Quest.QuestType.HUNT)
                .filter(q -> killedType.equals(q.getTargetMob()))
                .filter(q -> q.getCurrentCount() < q.getTargetCount())
                .findFirst()
                .ifPresent(q -> {
                    int newCount = q.getCurrentCount() + 1;
                    q.setCurrentCount(newCount);
                    guildData.setDirty();

                    // Grant XP scaled by party multiplier
                    if (newCount >= q.getTargetCount()) {
                        float multiplier = party.getPartyXpMultiplier();
                        int bonusXp = (int)(q.getXpReward()
                                * (multiplier - 1.0f));
                        if (bonusXp > 0) {
                            leader.displayClientMessage(
                                    net.minecraft.network.chat.Component
                                            .literal("Quest complete! Party bonus: +"
                                                    + bonusXp + " XP from "
                                                    + party.getPartyName()),
                                    false);
                        } else {
                            leader.displayClientMessage(
                                    net.minecraft.network.chat.Component
                                            .literal("Quest objective complete: "
                                                    + q.getTitle()
                                                    + " — return to the guild!"),
                                    true);
                        }
                    }
                });
    }
}