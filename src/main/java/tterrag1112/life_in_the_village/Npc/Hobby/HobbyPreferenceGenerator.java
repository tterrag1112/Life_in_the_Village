package tterrag1112.life_in_the_village.Npc.Hobby;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;

/**
 * Bus dispatcher that seeds {@link NpcHobbyPreference#topHobbies} when
 * an NPC transitions to ADULT. Spec line 120.
 */
public final class HobbyPreferenceGenerator implements EventDispatcher {

    @Override public String name() { return "hobby_preference_generator"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.LifeStageAdvanced lsa)) return;
        if (!"ADULT".equalsIgnoreCase(lsa.newStage())) return;
        TownspersonMob npc = lsa.subject();
        if (npc == null) return;
        if (!(npc.level() instanceof ServerLevel sl)) return;
        if (npc.getHobbyPreference().hasPreferences()) return;
        npc.getHobbyPreference().generate(npc, sl, sl.getRandom());
    }
}
