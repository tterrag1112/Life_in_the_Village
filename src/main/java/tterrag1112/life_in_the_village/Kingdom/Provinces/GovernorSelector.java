package tterrag1112.life_in_the_village.Kingdom.Provinces;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.SubdivisionModel;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.3 — picks a governor for a {@link Province}. Per the
 * kingdom plan: "highest-rank noble within the province whose
 * primary estate sits in the province; tie-break by prestige".
 *
 * <p>Pure helper (no state). Called by
 * {@code ProvinceRecomputeTickSystem} after a fresh subdivision
 * pass and by {@code NobilityEventDispatcher} when a noble's
 * dynasty changes (proxy for "manor changes hands").
 *
 * <h3>Selection rule</h3>
 * Walks loaded NPCs assigned to any village in the province with
 * a non-zero rank index AND a dynasty house affiliation. Sorts
 * descending by rank, then prestige, then UUID (stable for
 * determinism). Returns the top candidate's UUID.
 *
 * <h3>DUCHIES vs PROVINCES</h3>
 * For {@link SubdivisionModel#PROVINCES} (appointed governors), the
 * top-ranked candidate is appointed regardless of house. For
 * {@link SubdivisionModel#DUCHIES} (hereditary holdings), the
 * candidate must be the head of a dynasty house — falls back to
 * the appointed shape if no house-head qualifies.
 *
 * <h3>Heir refusal</h3>
 * v1: heirs always accept. Phase 4 / 5 may add a refusal mechanic
 * mirroring D3.2b's deferred heir-refusal.
 */
public final class GovernorSelector {

    private static final double VILLAGE_SCAN_INFLATE = 32.0;

    private GovernorSelector() {}

    /**
     * Picks a governor UUID for {@code province}. Returns empty
     * when no qualifying noble is loaded in any of the province's
     * member villages.
     */
    public static Optional<UUID> selectFor(ServerLevel level, VillageSavedData data,
                                           Kingdom kingdom, Province province) {
        if (level == null || data == null || kingdom == null || province == null) {
            return Optional.empty();
        }
        SubdivisionModel model = CultureResolver.ofKingdom(kingdom)
                .kingdomDefaults().subdivisionModel();

        var candidates = collectCandidates(level, data, province);
        if (candidates.isEmpty()) return Optional.empty();

        var sorted = candidates.stream()
                .sorted(Comparator
                        .<TownspersonMob>comparingInt(n -> n.getNobility().getRankIndex()).reversed()
                        .thenComparing(Comparator.<TownspersonMob>comparingInt(
                                n -> n.getNobility().getPrestige()).reversed())
                        .thenComparing(TownspersonMob::getUUID))
                .toList();

        if (model == SubdivisionModel.DUCHIES) {
            // Hereditary: prefer a noble who is head of a dynasty
            // house. Fall back to the top-ranked candidate when no
            // house-head is available.
            for (TownspersonMob c : sorted) {
                UUID uuid = c.getUUID();
                for (House h : kingdom.getHouses()) {
                    if (h.headUuid().filter(uuid::equals).isPresent()) {
                        return Optional.of(uuid);
                    }
                }
            }
        }
        return Optional.of(sorted.get(0).getUUID());
    }

    private static java.util.List<TownspersonMob> collectCandidates(ServerLevel level,
                                                                    VillageSavedData data,
                                                                    Province province) {
        java.util.List<TownspersonMob> out = new java.util.ArrayList<>();
        for (UUID vid : province.villageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            AABB bounds = v.getBounds(data)
                    .map(b -> b.inflate(VILLAGE_SCAN_INFLATE))
                    .orElse(null);
            if (bounds == null) continue;
            level.getEntitiesOfClass(TownspersonMob.class, bounds,
                            npc -> npc.getAssignedVillageName()
                                    .map(n -> n.equals(v.getName()))
                                    .orElse(false))
                    .stream()
                    .filter(GovernorSelector::qualifies)
                    .forEach(out::add);
        }
        return out;
    }

    private static boolean qualifies(TownspersonMob npc) {
        var nob = npc.getNobility();
        return nob.hasDynastyHouse() && nob.getRankIndex() > 0;
    }
}
