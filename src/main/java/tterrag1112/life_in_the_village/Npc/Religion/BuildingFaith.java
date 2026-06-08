package tterrag1112.life_in_the_village.Npc.Religion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Religion Rework R3e-2 — the single building-faith resolver. The locked design:
 * <b>temple + chapel = the village's dominant faith; a shrine = a minority
 * faith's foothold.</b> A religious building therefore carries a faith, which
 * drives its priest's belief + order, the served test, and (deferred to the
 * placement work) its NBT variant.
 *
 * <p>Every consumer reads faith through {@link #resolveFaith} so the
 * temple/chapel-derive-dominant fallback lives in exactly one place:
 * {@link ClergyOrders} (order assignment), {@link FaithReconciliation}
 * (served test), and the inhabitant populator (shrine default + clergy
 * belief).</p>
 */
public final class BuildingFaith {

    private BuildingFaith() {}

    /** A devout priest's belief strength in their building's faith (DEVOUT
     *  tier) — set at spawn for a shrine's minority clergy. */
    public static final float CLERGY_STRENGTH = 0.6f;

    /** True for the three faith-bearing building types. */
    public static boolean isReligiousBuilding(BuildingType type) {
        return type == BuildingType.TEMPLE
                || type == BuildingType.CHAPEL
                || type == BuildingType.SHRINE;
    }

    /**
     * The faith a religious building serves: its explicit {@code patronFaith}
     * when set (a shrine's minority faith), else the village dominant (an unset
     * temple/chapel). {@code null} for a non-religious building.
     */
    public static String resolveFaith(ServerLevel level, Village village, Building building) {
        if (building == null || !isReligiousBuilding(building.getType())) return null;
        String patron = building.getPatronFaith();
        if (patron != null) return patron;
        return ReligionContent.villageReligionId(level, village);
    }

    /** The faith a PRIEST should take: their assigned religious building's faith
     *  when they staff one, else the village dominant. Used by
     *  {@link ClergyOrders} for order resolution and for the spawn-time belief. */
    public static String clergyFaith(ServerLevel level, Village village, TownspersonMob npc) {
        Building building = npc.getAssignedBuildingId()
                .flatMap(VillageSavedData.get(level)::getBuildingById).orElse(null);
        String byBuilding = resolveFaith(level, village, building);
        if (byBuilding != null) return byBuilding;
        // Spawn-time the building may not be in the index yet (it is registered
        // by UUID only); applyClergyFaith has already set the priest's belief to
        // the building faith, so their own primary is the authority here. Else
        // the village dominant.
        return npc.getPiety().primaryReligion()
                .orElse(ReligionContent.villageReligionId(level, village));
    }

    /**
     * Sets a religious-building priest's belief to the BUILDING's faith when it
     * differs from the culture seed already applied (the shrine minority case);
     * a temple/chapel priest whose building faith already equals their dominant
     * seed is left untouched, so single-faith villages are undisturbed. Drops
     * the (now wrong) culture seed and seeds the building faith at
     * {@link #CLERGY_STRENGTH}.
     */
    public static void applyClergyFaith(ServerLevel level, Village village,
                                        TownspersonMob npc, Building building) {
        if (npc.getProfession() != Profession.PRIEST) return;
        String faith = resolveFaith(level, village, building);
        if (faith == null) return;
        String current = npc.getPiety().primaryReligion().orElse(null);
        if (faith.equals(current)) return; // already aligned (temple/chapel dominant)
        if (current != null) npc.getPiety().setBelief(current, 0f);
        npc.getPiety().setBelief(faith, CLERGY_STRENGTH);
    }

    /**
     * The village's largest <em>unserved minority</em> faith — the most common
     * primary faith among loaded village NPCs that is NOT the dominant. When no
     * minority population exists yet (the common single-culture village), falls
     * back to the first registered non-dominant religion so a manually-spawned
     * shrine still takes a meaningful, distinct faith (overridable via
     * {@code /religion shrine}).
     */
    public static String largestUnservedMinority(ServerLevel level, Village village) {
        String dominant = ReligionContent.villageReligionId(level, village);
        VillageSavedData data = VillageSavedData.get(level);
        Map<String, Integer> tally = new HashMap<>();
        village.getBounds(data).ifPresent(bounds ->
                level.getEntitiesOfClass(TownspersonMob.class, bounds.inflate(32),
                                m -> m.getAssignedVillageName()
                                        .map(n -> n.equals(village.getName())).orElse(false))
                        .forEach(m -> m.getPiety().primaryReligion().ifPresent(faith -> {
                            if (!faith.equals(dominant)) tally.merge(faith, 1, Integer::sum);
                        })));
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        if (best != null) return best;
        // No minority present — deterministic non-dominant fallback.
        for (Religion r : ReligionRegistry.all()) {
            if (!r.id().equals(dominant)) return r.id();
        }
        return dominant; // single registered religion — degenerate, but safe
    }

    /**
     * R3e-2b — every faith with a religious building in the village, mapped to
     * one representative building (its <em>venue</em>): the dominant faith to its
     * temple/chapel, each shrine faith to its shrine. TEMPLE is preferred over
     * CHAPEL over SHRINE within a faith, so the dominant's venue matches the
     * legacy {@code templeLocation} when a temple exists (single-faith villages
     * are undisturbed). Insertion-ordered for stable iteration.
     */
    public static Map<String, Building> religiousBuildingsByFaith(ServerLevel level, Village village) {
        Map<String, Building> out = new LinkedHashMap<>();
        VillageSavedData data = VillageSavedData.get(level);
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null || !isReligiousBuilding(b.getType())) continue;
            String faith = resolveFaith(level, village, b);
            if (faith == null) continue;
            Building cur = out.get(faith);
            if (cur == null || venueRank(b.getType()) < venueRank(cur.getType())) {
                out.put(faith, b);
            }
        }
        return out;
    }

    /** Venue preference within a faith: TEMPLE &lt; CHAPEL &lt; SHRINE. */
    private static int venueRank(BuildingType type) {
        return switch (type) {
            case TEMPLE -> 0;
            case CHAPEL -> 1;
            case SHRINE -> 2;
            default     -> 3;
        };
    }

    /**
     * The faith of the religious building standing at {@code loc} (its origin),
     * or {@code null} when no religious building sits there. Lets a blessing
     * rite recover its faith from its location (the venue building) without a
     * new rite field — used by {@code RiteExecutor} to tune effects to the
     * faith whose building hosts the rite.
     */
    public static String faithAtLocation(ServerLevel level, Village village, BlockPos loc) {
        if (loc == null) return null;
        VillageSavedData data = VillageSavedData.get(level);
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null || !isReligiousBuilding(b.getType())) continue;
            BlockPos origin = b.getShape().getOrigin();
            if (origin != null && origin.equals(loc)) return resolveFaith(level, village, b);
        }
        return null;
    }

    /**
     * R4a — the id of the religious building standing at {@code loc} (its origin),
     * or empty when none. Lets a rite route its economic flow (e.g. a tithe) into
     * the right building's {@code BuildingEconomy} from the rite's venue.
     */
    public static java.util.Optional<UUID> buildingIdAtLocation(ServerLevel level,
                                                                Village village, BlockPos loc) {
        if (loc == null) return java.util.Optional.empty();
        VillageSavedData data = VillageSavedData.get(level);
        for (UUID bid : village.getBuildingIds()) {
            Building b = data.getBuildingById(bid).orElse(null);
            if (b == null || !isReligiousBuilding(b.getType())) continue;
            BlockPos origin = b.getShape().getOrigin();
            if (origin != null && origin.equals(loc)) return java.util.Optional.of(bid);
        }
        return java.util.Optional.empty();
    }

    /**
     * The served test's building-aware core: is there a loaded PRIEST in the
     * village whose building faith equals {@code faith}? Covers both a dominant
     * temple/chapel priest and a minority shrine priest with one scan.
     */
    public static boolean hasSeatedPriestOfFaith(ServerLevel level, Village village, String faith) {
        if (faith == null) return false;
        VillageSavedData data = VillageSavedData.get(level);
        AABB bounds = village.getBounds(data).map(b -> b.inflate(32)).orElse(null);
        if (bounds == null) return false;
        for (TownspersonMob m : level.getEntitiesOfClass(TownspersonMob.class, bounds,
                npc -> npc.getProfession() == Profession.PRIEST
                        && npc.getAssignedVillageName()
                                .map(n -> n.equals(village.getName())).orElse(false))) {
            Building b = m.getAssignedBuildingId()
                    .flatMap(data::getBuildingById).orElse(null);
            if (faith.equals(resolveFaith(level, village, b))) return true;
        }
        return false;
    }
}
