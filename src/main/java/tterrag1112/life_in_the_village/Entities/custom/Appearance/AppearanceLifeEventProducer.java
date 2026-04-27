package tterrag1112.life_in_the_village.Entities.custom.Appearance;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Events.EventDispatcher;
import tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent;

/**
 * Phase 5 doc 33 § Rebuild triggers.
 *
 * <p>Subscribes to the {@link tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus}
 * and recomputes Layer-1 appearance on:</p>
 *
 * <ul>
 *   <li>{@link NpcLifeEvent.LifeStageAdvanced} — new life-stage
 *       decoration applied (child/teen/adult/elderly).</li>
 *   <li>{@link NpcLifeEvent.OfficeChange} — additive add of the new
 *       office mark for the new holder, then full rebuild so the
 *       previous holder's marks recompute too. The OfficeChange
 *       event carries the new holder; the previous holder is
 *       handled by the OfficeElection finalisation path that fires
 *       a separate event for them.</li>
 *   <li>{@link NpcLifeEvent.Hired}, {@link NpcLifeEvent.Fired},
 *       {@link NpcLifeEvent.Promoted} — mirror profession changes
 *       that may affect the appearance set.</li>
 * </ul>
 *
 * <p>Profession-set rebuilds are handled directly inside
 * {@code TownspersonMob.setProfession} so they fire even on paths
 * that don't go through the bus.</p>
 */
public final class AppearanceLifeEventProducer implements EventDispatcher {

    @Override public String name() { return "AppearanceLifeEventProducer"; }

    @Override
    public void onEvent(NpcLifeEvent event) {
        TownspersonMob subject = event.subject();
        if (subject == null) return;
        if (!(subject.level() instanceof ServerLevel)) return;

        if (event instanceof NpcLifeEvent.LifeStageAdvanced
                || event instanceof NpcLifeEvent.OfficeChange
                || event instanceof NpcLifeEvent.Hired
                || event instanceof NpcLifeEvent.Fired
                || event instanceof NpcLifeEvent.Promoted
                || event instanceof NpcLifeEvent.Demoted) {
            try {
                AppearanceRebuilder.rebuild(subject);
            } catch (RuntimeException ex) {
                // Rebuilds are best-effort — log but never propagate to
                // the bus (would kill subsequent dispatchers).
                System.err.println("[AppearanceLifeEventProducer] rebuild failed: "
                        + ex.getMessage());
            }
        }
    }
}
