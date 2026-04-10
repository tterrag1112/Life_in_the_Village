package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.UUID;

/**
 * Creates and stores {@link Village} records in their planned-but-not-realised
 * state. A planned village has a name, type, culture, and an origin position,
 * but no buildings, NPCs, or block changes. It lives in {@link VillageSavedData}
 * alongside realised villages and is indistinguishable from them for read-only
 * operations that don't touch buildings (listings, UI, kingdom membership).
 *
 * <h3>Realisation</h3>
 * {@link tterrag1112.life_in_the_village.Events.VillageRealisationSystem}
 * watches player positions and calls {@link VillageSpawner#spawnVillage} for
 * any planned village whose origin is within the realisation radius. After
 * that call, {@link #markRealised} transitions the record to the realised
 * state.
 *
 * <h3>Kingdom membership</h3>
 * Villages can be added to a kingdom at plan time — the kingdom object only
 * stores UUIDs, so it doesn't care whether the member is realised. This lets
 * virtual kingdoms exist before any player has visited them.
 */
public final class VillagePlanHelper {

    private VillagePlanHelper() {}

    /**
     * Creates a new planned village and registers it in saved data.
     *
     * <p>The village is created with a fresh UUID, the given name/type/culture,
     * and the origin position stored as {@code plannedOrigin}. No blocks are
     * placed and no NPCs are spawned. The returned {@code Village} can be
     * added to a {@link Kingdom} immediately.
     *
     * @return the newly created planned village
     */
    public static Village planVillage(ServerLevel level,
                                      VillageSavedData data,
                                      BlockPos origin,
                                      String type,
                                      String name) {
        Village village = new Village(name);
        village.setVillageType(type);
        village.setPlannedOrigin(origin);
        village.setRealised(false);

        data.addVillage(village);
        data.setDirty();

        System.out.println("VillagePlanHelper: planned '" + name
                + "' (" + type + ") at " + origin.toShortString());
        return village;
    }

    /**
     * Returns true if the village is planned but has not yet been realised.
     */
    public static boolean isPlanned(Village village) {
        return !village.isRealised() && village.getPlannedOrigin() != null;
    }

    /**
     * Called after {@link VillageSpawner#spawnVillage} successfully spawns
     * a planned village. Flips the realised flag so future ticks skip it.
     * The villageCentre is set by the spawner itself during placement.
     */
    public static void markRealised(Village village, VillageSavedData data) {
        village.setRealised(true);
        data.setDirty();
    }

    /**
     * Convenience — finds the nearest planned village to a block position,
     * optionally within a radius. Returns empty if no planned villages
     * exist in range.
     */
    public static Optional<Village> findNearestPlanned(
            VillageSavedData data, BlockPos pos, double maxDistance) {
        double bestSq = maxDistance * maxDistance;
        Village best = null;
        for (Village v : data.getAllVillages()) {
            if (!isPlanned(v)) continue;
            BlockPos origin = v.getPlannedOrigin();
            if (origin == null) continue;
            double d = origin.distSqr(pos);
            if (d < bestSq) {
                bestSq = d;
                best = v;
            }
        }
        return Optional.ofNullable(best);
    }
}