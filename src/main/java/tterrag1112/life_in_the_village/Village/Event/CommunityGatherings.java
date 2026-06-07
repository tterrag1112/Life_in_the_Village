package tterrag1112.life_in_the_village.Village.Event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecution;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Village.Gathering.CommunityGathering;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Religion Rework R2a — the cross-registry discovery point. The two
 * registries stay separate (the {@code VillageSavedData} event store and
 * {@code RiteSavedData}); this helper unions them behind
 * {@link CommunityGathering} so a single consumer (R2b's attend-event
 * behavior) can find active gatherings of <em>both</em> kinds without
 * knowing which subsystem owns each one.
 *
 * <p>Lives on the event side because it inherently depends on both stores;
 * the {@code CommunityGathering} interface itself stays dependency-free in
 * the neutral {@code Village.Gathering} package, so neither subsystem gains a
 * cycle.</p>
 */
public final class CommunityGatherings {

    private CommunityGatherings() {}

    /** Every gathering (events + rites) owned by {@code villageId}. */
    public static List<CommunityGathering> inVillage(ServerLevel level, UUID villageId) {
        List<CommunityGathering> out = new ArrayList<>();
        if (level == null || villageId == null) return out;
        for (VillageEvent e : VillageSavedData.get(level).getAllEvents()) {
            if (villageId.equals(e.getVillageId())) out.add(e);
        }
        for (RiteExecution r : RiteSavedData.get(level).all()) {
            if (villageId.equals(r.villageId())) out.add(r);
        }
        return out;
    }

    /** Gatherings (events + rites) in {@code villageId} under way at {@code tick}. */
    public static List<CommunityGathering> activeInVillage(ServerLevel level,
                                                           UUID villageId, long tick) {
        List<CommunityGathering> out = new ArrayList<>();
        for (CommunityGathering g : inVillage(level, villageId)) {
            if (g.isActiveAt(tick)) out.add(g);
        }
        return out;
    }

    /**
     * Gatherings (events + rites) under way at {@code tick} whose pinned
     * location is within {@code radius} blocks of {@code pos}. Gatherings with
     * no pinned location (village-wide festivals) are excluded — R2b resolves
     * those via village membership instead.
     */
    public static List<CommunityGathering> activeNear(ServerLevel level,
                                                      BlockPos pos, double radius, long tick) {
        List<CommunityGathering> out = new ArrayList<>();
        if (level == null || pos == null) return out;
        double r2 = radius * radius;
        VillageSavedData vdata = VillageSavedData.get(level);
        for (VillageEvent e : vdata.getAllEvents()) {
            addIfNear(e, pos, r2, tick, out);
        }
        for (RiteExecution rite : RiteSavedData.get(level).all()) {
            addIfNear(rite, pos, r2, tick, out);
        }
        return out;
    }

    private static void addIfNear(CommunityGathering g, BlockPos pos, double r2,
                                  long tick, List<CommunityGathering> out) {
        if (!g.isActiveAt(tick)) return;
        g.gatheringLocation()
                .filter(loc -> loc.distSqr(pos) <= r2)
                .ifPresent(loc -> out.add(g));
    }
}
