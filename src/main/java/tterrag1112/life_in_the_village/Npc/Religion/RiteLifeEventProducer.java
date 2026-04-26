package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.UUID;

/**
 * Phase 3 task 20 dispatcher. Reacts to life events to schedule the
 * appropriate rite via {@link RiteScheduler}.
 *
 * <ul>
 *   <li>{@link NpcLifeEvent.LifeStageAdvanced} when newStage = ADULT
 *   → COMING_OF_AGE, scheduled 1 day out.</li>
 *   <li>{@link NpcLifeEvent.Married} → MARRIAGE, scheduled the same day.</li>
 *   <li>{@link NpcLifeEvent.BirthInFamily} → NAMING, scheduled 2 days out.</li>
 *   <li>{@link NpcLifeEvent.FamilyDeath} → FUNERAL, scheduled 1 day out
 *   (the deceased's UUID is the participant).</li>
 * </ul>
 *
 * <p>Each schedule consults the rite filter on the village's primary
 * religion; rites the religion doesn't ritualise are silently skipped
 * (spec line 64 — Loom/Tidecall don't have COMING_OF_AGE).</p>
 */
public final class RiteLifeEventProducer implements EventDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String NAME = "religion-rite";

    @Override public String name() { return NAME; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (event == null) return;
        TownspersonMob subject = event.subject();
        if (subject == null) return;
        if (!(subject.level() instanceof ServerLevel level)) return;
        Village village = villageOf(subject, level);
        if (village == null) return;
        Religion religion = religionFor(village, level);
        if (religion == null) return;

        if (event instanceof NpcLifeEvent.LifeStageAdvanced advanced
                && "ADULT".equals(advanced.newStage())
                && religion.ritualises(Rite.COMING_OF_AGE)) {
            RiteScheduler.schedule(level, village, Rite.COMING_OF_AGE,
                    List.of(subject.getUUID()), 24000L);
        } else if (event instanceof NpcLifeEvent.Married married
                && religion.ritualises(Rite.MARRIAGE)) {
            RiteScheduler.schedule(level, village, Rite.MARRIAGE,
                    List.of(subject.getUUID(), married.spouseId()), 24000L);
        } else if (event instanceof NpcLifeEvent.BirthInFamily birth
                && religion.ritualises(Rite.NAMING)) {
            // Spec line 119: NAMING participants = household + child.
            // v1 ships parent + child; the household scan lands when
            // the priest visit goal does.
            RiteScheduler.schedule(level, village, Rite.NAMING,
                    List.of(subject.getUUID(), birth.childId()), 2L * 24000L);
        } else if (event instanceof NpcLifeEvent.FamilyDeath death
                && religion.ritualises(Rite.FUNERAL)) {
            RiteScheduler.schedule(level, village, Rite.FUNERAL,
                    List.of(death.deceasedId(), subject.getUUID()), 24000L);
        }
    }

    private static Village villageOf(TownspersonMob npc, ServerLevel level) {
        return npc.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName)
                .orElse(null);
    }

    private static Religion religionFor(Village village, ServerLevel level) {
        VillageSavedData vdata = VillageSavedData.get(level);
        String culture = vdata.getKingdomForVillage(village.getId())
                .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getCulture)
                .orElse("default");
        return ReligionRegistry.get(ReligionRegistry.dominantReligionFor(culture));
    }

    /** Suppresses unused-import on UUID for follow-up overloads. */
    @SuppressWarnings("unused")
    private static final UUID DOC_HOOK = new UUID(0L, 0L);
}
