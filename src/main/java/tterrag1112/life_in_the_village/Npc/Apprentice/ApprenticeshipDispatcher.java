package tterrag1112.life_in_the_village.Npc.Apprentice;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;

import java.util.Optional;

/**
 * Bus listener that runs apprenticeship discovery when an NPC
 * transitions to ADULT (spec line 94).
 *
 * <p>Listens for {@link NpcLifeEvent.LifeStageAdvanced} with
 * {@code newStage == "ADULT"}. Skips NPCs already involved in any
 * active contract (matcher would no-op anyway). Spec's
 * "preferredProfession" field doesn't exist yet — see the
 * {@link ApprenticeshipMatcher#preferredProfessionFor proxy} note.</p>
 */
public final class ApprenticeshipDispatcher implements EventDispatcher {

    @Override public String name() { return "apprenticeship_discovery"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        if (!(event instanceof NpcLifeEvent.LifeStageAdvanced lsa)) return;
        if (!"ADULT".equalsIgnoreCase(lsa.newStage())) return;
        TownspersonMob candidate = lsa.subject();
        if (candidate == null) return;
        if (!(candidate.level() instanceof ServerLevel sl)) return;

        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(sl);
        if (reg.getByApprentice(candidate.getUUID()).isPresent()) return;

        Optional<TownspersonMob> master = ApprenticeshipMatcher.findMaster(candidate, sl);
        if (master.isEmpty()) return;
        TownspersonMob m = master.get();
        if (!ApprenticeshipMatcher.masterAccepts(m, candidate, reg)) return;

        ApprenticeshipContract contract = ApprenticeshipManager.startContract(
                sl, m, candidate, "Standard. 2 years.");
        // Mint the physical contract via the village scribe (if any).
        ApprenticeshipContractFactory.queueViaScribe(sl, contract, m, candidate);
    }
}
