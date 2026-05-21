package tterrag1112.life_in_the_village.Npc.Aging;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipMatcher;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipSavedData;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.Optional;

/**
 * Handles the spec's "coming-of-age event" on TEEN→ADULT transition
 * (spec line 139-150).
 *
 * <p>Phase 2 v1: leader-presence is sufficient for the rite (priest
 * support lands in Phase 3 task 20). Routes the candidate's
 * preferredProfession into the apprenticeship pipeline; if no
 * eligible master is available, leaves the apprenticeship goal open
 * (LifeGoalSelector / TEACH_APPRENTICE handle the future re-attempt
 * via the existing daily goal evaluator).</p>
 */
public final class ComingOfAgeHandler implements EventDispatcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Memory value stamped on the rite when a leader is present. */
    public static final int RITE_MEMORY_VALUE = 70;

    @Override public String name() { return "coming_of_age_handler"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.LifeStageAdvanced lsa)) return;
        if (!"ADULT".equalsIgnoreCase(lsa.newStage())) return;
        TownspersonMob npc = lsa.subject();
        if (npc == null) return;
        if (!(npc.level() instanceof ServerLevel sl)) return;

        ChildhoodState state = npc.getChildhoodState();
        // Recompute preference if not already set (handles the case
        // where the child arc never seeded one).
        ChildhoodInitializer.recomputePreference(npc);

        // Rite of passage — leader-presence stub. Phase 3 layers in
        // priest support.
        TownspersonMob officiant = findRiteOfficiant(sl, npc);
        if (officiant != null) {
            npc.getMemory().add(NpcMemory.create(
                    MemoryType.OFFICIATED_BY,
                    List.of(officiant.getUUID()),
                    sl.getGameTime(),
                    RITE_MEMORY_VALUE,
                    "Coming-of-age rite officiated by " + officiant.getNpcName()));
            officiant.getMemory().add(NpcMemory.create(
                    MemoryType.OFFICIATED_FOR,
                    List.of(npc.getUUID()),
                    sl.getGameTime(),
                    RITE_MEMORY_VALUE,
                    "Officiated " + npc.getNpcName() + "'s coming-of-age"));
            LOGGER.debug("[ComingOfAge] {} rite officiated by {}",
                    npc.getNpcName(), officiant.getNpcName());
        }

        // Apprenticeship handoff per spec line 147.
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(sl);
        if (reg.getByApprentice(npc.getUUID()).isPresent()) return; // already handled

        Profession preferred = state.preferredProfession().preferred();
        if (preferred == Profession.NONE) {
            preferred = ApprenticeshipMatcher.preferredProfessionFor(npc);
        }
        if (preferred == Profession.NONE || preferred == Profession.CITIZEN) {
            // Spec line 150: default to FARMHAND/LABORER. v1 uses
            // FARMHAND; the apprenticeship goal stays open via the
            // standard LifeGoalSelector retry on next adult re-eval.
            tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                    npc, Profession.FARMHAND,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.COMING_OF_AGE,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.SYSTEM);
            return;
        }
        // Matcher already runs on its own LifeStageAdvanced(ADULT)
        // listener — calling it explicitly here would double-create
        // the contract. We just route the basic-fallback case so an
        // NPC without an eligible master still gets a profession.
        Optional<TownspersonMob> master = ApprenticeshipMatcher.findMaster(npc, sl);
        if (master.isEmpty()) {
            // No master available now — fall back to a basic profession
            // and let the LifeGoalSelector goal stay open for retry.
            tterrag1112.life_in_the_village.Npc.Career.CareerTransitions.changeProfession(
                    npc, Profession.FARMHAND,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Reason.COMING_OF_AGE,
                    tterrag1112.life_in_the_village.Npc.Career.ProfessionChangeRequest.Source.SYSTEM);
        }
        // If a master IS available, ApprenticeshipDispatcher handles
        // the contract creation itself; we don't duplicate that work.
    }

    /**
     * Finds a leader (or other ceremony-eligible NPC) within the
     * candidate's village. Phase 3 task 20 widens the search to
     * include priests.
     */
    private static TownspersonMob findRiteOfficiant(ServerLevel level, TownspersonMob npc) {
        List<TownspersonMob> candidates = level.getEntitiesOfClass(
                TownspersonMob.class,
                npc.getBoundingBox().inflate(64.0),
                m -> m != npc
                        && (m.getProfession() == Profession.VILLAGE_LEADER
                                || m.getProfession() == Profession.PRIEST
                                || m.getProfession() == Profession.HERALD));
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
