// src/main/java/tterrag1112/life_in_the_village/Entities/Party/PartyQuestTracker.java
package tterrag1112.life_in_the_village.Guilds.Adventurer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Quests.QuestContext;
import tterrag1112.life_in_the_village.Quests.QuestEvents;


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

        // Attribute the kill to the party leader's active quests. The F2 engine advances
        // every matching Hunt objective and prompts the leader to turn in at the guild;
        // the party XP multiplier is applied there (GuildQuests.turnIn), not on the kill.
        ServerPlayer leader = level.getServer()
                .getPlayerList()
                .getPlayer(party.getLeaderPlayerId());
        if (leader == null) return;

        String killedType = BuiltInRegistries.ENTITY_TYPE
                .getKey(event.getEntity().getType()).toString();
        QuestEvents.notify(leader, QuestContext.mobDeath(killedType));
    }
}