package tterrag1112.life_in_the_village.Npc.Visitor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionContent;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Religion Rework R3e-3a — festival-driven pilgrim convergence. When a village's
 * <b>grand religious festival</b> (the R3d-2 high holy days) becomes ACTIVE, a
 * bounded number of {@link VisitorType#PILGRIM} visitors of the festival's faith
 * arrive from afar to attend, swelling the celebration, then despawn on the
 * normal visitor lifecycle after it ends.
 *
 * <p>This is a thin trigger over the existing visitor machinery — it picks a
 * faith + venue and calls {@link VisitorFluxEngine#spawnVisitorTo}, bounding the
 * count against {@link VillageVisitorCapacity} (no cap bypass, no new spawn
 * path). Pilgrims are pointed at the festival's building (the temple for the
 * dominant, a shrine for a minority faith via R3e-2b) and tagged with that faith
 * so {@code FaithReconciliation} treats them as full-benefit co-religionists.
 * The resident-departure half (an adherent leaving to pilgrimage and returning)
 * is R3e-3b.</p>
 */
public final class PilgrimConvergence {

    private PilgrimConvergence() {}

    /** A grand festival draws this many pilgrims, capacity permitting. */
    private static final int MIN_PILGRIMS = 2;
    private static final int MAX_PILGRIMS = 4;

    /** A devout pilgrim's belief in the festival faith (PietyTier.DEVOUT). */
    private static final float PILGRIM_STRENGTH = 0.6f;

    /**
     * Called from {@code EventEffects.onEventStart}. No-op unless the event is a
     * grand festival; otherwise spawns up to {@link #MAX_PILGRIMS} faith-tagged
     * pilgrims pointed at the festival venue, bounded by remaining visitor
     * capacity.
     */
    public static void onFestivalStart(ServerLevel level, VillageEvent event,
                                       Village village, VillageSavedData data) {
        if (level == null || event == null || village == null) return;
        if (!drawsPilgrims(event.getType())) return;

        // Festival faith (R3e-2b stamp; dominant fallback) + its venue building.
        String faith = event.getEventData().getOrDefault(
                CeremonyBlessings.FAITH_KEY, ReligionContent.villageReligionId(level, village));
        Building venue = BuildingFaith.religiousBuildingsByFaith(level, village).get(faith);
        if (venue == null) return; // no building of this faith — nothing to converge on

        int slots = remainingVisitorSlots(level, village, data);
        if (slots <= 0) return;    // village already at visitor capacity — don't mob it

        int desired = MIN_PILGRIMS + level.getRandom().nextInt(MAX_PILGRIMS - MIN_PILGRIMS + 1);
        int count = Math.min(desired, slots);
        for (int i = 0; i < count; i++) {
            VisitorFluxEngine.spawnVisitorTo(level, village, data,
                            VisitorType.PILGRIM, venue.getId())
                    .ifPresent(p -> tagFaith(p, faith));
        }
    }

    /** The festivals that draw pilgrims — the R3d-2 grand annual high holy days.
     *  (Signature rites / cultural holy days could be added here later.) */
    private static boolean drawsPilgrims(VillageEvent.EventType type) {
        return switch (type) {
            case HARVEST_HOME, GREAT_WEAVING, TIDES_RETURN, FOUNDING_DAY -> true;
            default -> false;
        };
    }

    /** Make the pilgrim a same-faith adherent so the festival benefits them
     *  fully (FaithReconciliation co-religion): drop the spawn-time culture seed
     *  if it differs, and seed the festival faith at DEVOUT strength. */
    private static void tagFaith(TownspersonMob pilgrim, String faith) {
        String seed = pilgrim.getPiety().primaryReligion().orElse(null);
        if (seed != null && !seed.equals(faith)) pilgrim.getPiety().setBelief(seed, 0f);
        pilgrim.getPiety().setBelief(faith, PILGRIM_STRENGTH);
    }

    /** Remaining visitor headroom for the village (cap minus current loaded
     *  visitors), mirroring {@code VisitorFluxEngine.tickVillage}'s accounting so
     *  the festival spawn honours the same cap. */
    private static int remainingVisitorSlots(ServerLevel level, Village village,
                                             VillageSavedData data) {
        VillageVisitorCapacity cap = VillageVisitorCapacity.compute(village, data);
        AABB bounds = village.getBounds(data).orElse(null);
        if (bounds == null) return 0;
        int current = level.getEntitiesOfClass(TownspersonMob.class, bounds.inflate(16),
                m -> m.isAlive() && m.isVisitor()
                        && m.getAssignedVillageName()
                                .map(n -> n.equals(village.getName())).orElse(false)).size();
        return Math.max(0, cap.maxConcurrent() - current);
    }
}
