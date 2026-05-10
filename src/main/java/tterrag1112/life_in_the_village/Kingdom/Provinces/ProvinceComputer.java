package tterrag1112.life_in_the_village.Kingdom.Provinces;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Cultures.Culture;
import tterrag1112.life_in_the_village.Cultures.CultureBundles.SubdivisionModel;
import tterrag1112.life_in_the_village.Cultures.CultureResolver;
import tterrag1112.life_in_the_village.Kingdom.Houses.House;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.*;

/**
 * Track D3.3 — recomputes a kingdom's province set from its current
 * state. Pure function (apart from the {@link Kingdom#replaceAllProvinces}
 * call at the end): given a kingdom + level + saved data, produces
 * a fresh province list and applies it to the kingdom.
 *
 * <h3>Strategy dispatch</h3>
 * Reads {@link Culture#kingdomDefaults()} {@code .subdivisionModel()};
 * five values are mapped per the user-confirmed design:
 * <ul>
 *   <li>{@link SubdivisionModel#UNITARY} → no subdivision (returns
 *       empty list).</li>
 *   <li>{@link SubdivisionModel#PROVINCES} → TERRITORIAL with
 *       appointed governors. Cell-set Voronoi seeded at NOBLE_MANOR
 *       positions, falling back to village centroids.</li>
 *   <li>{@link SubdivisionModel#DUCHIES} → TERRITORIAL with
 *       hereditary governors. Same Voronoi pass; governor selection
 *       differs (handled in {@code GovernorSelector}, not here).</li>
 *   <li>{@link SubdivisionModel#TRIBAL_CONFEDERATION} → TRIBAL.
 *       Groups villages by the dominant noble house's id; ungrouped
 *       villages cluster into a single "common" province.</li>
 *   <li>{@link SubdivisionModel#CITY_STATE_LEAGUE} → FUNCTIONAL.
 *       Groups villages by derived role (CIVIC / TRADE / AGRICULTURE
 *       / INDUSTRIAL / DEFENSIVE) read from each village's
 *       {@code VillageTag} set with a fallback heuristic.</li>
 * </ul>
 *
 * <h3>Skip rule</h3>
 * Per the kingdom plan, kingdoms with ≤ 3 member villages skip
 * subdivision entirely (returns empty list, behaves as UNITARY).
 *
 * <h3>Determinism</h3>
 * Cell assignment uses Manhattan distance plus a stable tie-break
 * by seed UUID. Same world seed produces the same provinces across
 * reloads. Province UUIDs are derived from the kingdom + seed UUID
 * pair so re-runs preserve province identity (treasury + report
 * buffer survive recomputes).
 */
public final class ProvinceComputer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Per the kingdom plan: kingdoms with this many or fewer
     *  member villages skip subdivision. */
    public static final int MIN_VILLAGES_FOR_SUBDIVISION = 4;

    private ProvinceComputer() {}

    /**
     * Recomputes provinces for {@code kingdom} and applies the
     * result. Preserves treasury / report buffer / modifiers when
     * the new pass produces a province with the same id as an
     * existing one.
     */
    public static void recompute(ServerLevel level, VillageSavedData data,
                                 Kingdom kingdom, long tick) {
        if (level == null || data == null || kingdom == null) return;
        Culture culture = CultureResolver.ofKingdom(kingdom);
        SubdivisionModel model = culture.kingdomDefaults().subdivisionModel();

        List<Province> fresh;
        if (kingdom.getVillageIds().size() < MIN_VILLAGES_FOR_SUBDIVISION
                || model == SubdivisionModel.UNITARY) {
            fresh = List.of();
        } else {
            fresh = switch (model) {
                case UNITARY              -> List.of();
                case PROVINCES, DUCHIES   -> territorial(level, data, kingdom, tick);
                case TRIBAL_CONFEDERATION -> tribal(level, data, kingdom, tick);
                case CITY_STATE_LEAGUE    -> functional(level, data, kingdom, tick);
            };
        }

        // Carry over treasury / reports / modifiers when a province
        // with the same id existed before.
        List<Province> merged = mergeWithExisting(fresh, kingdom);
        // Re-run governor selection for every province (rank /
        // prestige / house head changes since last recompute may
        // produce a different winner).
        List<Province> withGovernors = new ArrayList<>(merged.size());
        for (Province p : merged) {
            Optional<UUID> gov = GovernorSelector.selectFor(level, data, kingdom, p);
            withGovernors.add(p.withGovernor(gov.orElse(null)));
        }
        kingdom.replaceAllProvinces(withGovernors);
        kingdom.setLastProvinceRecomputeTick(tick);
        LOGGER.info("[ProvinceComputer] '{}' ({}) → {} provinces",
                kingdom.getName(), model.name(), withGovernors.size());
    }

    // ── Strategy: TERRITORIAL (PROVINCES + DUCHIES) ─────────────────────────

    /**
     * Cell-set Voronoi over manor positions. Falls back to village
     * centroids when the kingdom has zero NOBLE_MANOR buildings.
     * Each kingdom-claim cell is assigned to the nearest seed by
     * Manhattan distance; ties broken by seed UUID.
     */
    private static List<Province> territorial(ServerLevel level, VillageSavedData data,
                                              Kingdom kingdom, long tick) {
        // Collect seeds: prefer manors, fall back to village centroids.
        List<Seed> seeds = collectManorSeeds(data, kingdom);
        if (seeds.isEmpty()) seeds = collectVillageSeeds(data, kingdom);
        if (seeds.isEmpty()) return List.of();

        // Cells we partition: the kingdom's claimed cells (or all
        // cells where any member village's centroid sits, if no
        // territorial claim exists yet).
        Set<Long> cellPool = collectCellPool(data, kingdom);
        if (cellPool.isEmpty()) return List.of();

        // Assign each cell to nearest seed.
        Map<UUID, List<Long>> cellsBySeed = new LinkedHashMap<>();
        for (Seed s : seeds) cellsBySeed.put(s.id(), new ArrayList<>());
        for (long cellKey : cellPool) {
            int cx = AtlasCell.unpackX(cellKey);
            int cz = AtlasCell.unpackZ(cellKey);
            int blockX = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            int blockZ = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            Seed nearest = nearestSeed(seeds, blockX, blockZ);
            cellsBySeed.get(nearest.id()).add(cellKey);
        }

        // Each seed becomes a province; assign villages by nearest seed too.
        Map<UUID, List<UUID>> villagesBySeed = new LinkedHashMap<>();
        for (Seed s : seeds) villagesBySeed.put(s.id(), new ArrayList<>());
        for (UUID vid : kingdom.getVillageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            BlockPos centre = villageCentre(v, data);
            if (centre == null) continue;
            Seed nearest = nearestSeed(seeds, centre.getX(), centre.getZ());
            villagesBySeed.get(nearest.id()).add(vid);
        }

        // Build province records.
        List<Province> out = new ArrayList<>();
        int idx = 1;
        for (Seed s : seeds) {
            UUID provinceId = derivedId(kingdom.getId(), s.id());
            String name = kingdom.getName() + " Province " + idx++;
            out.add(Province.fresh(
                    provinceId, name, kingdom.getId(),
                    Optional.empty(),
                    villagesBySeed.get(s.id()),
                    cellsBySeed.get(s.id()),
                    tick));
        }
        return out;
    }

    // ── Strategy: TRIBAL (TRIBAL_CONFEDERATION) ─────────────────────────────

    /**
     * Groups villages by the dominant noble house. Each noble house
     * maps to one province carrying the house's villages + their
     * cells (the cells of the kingdom claim that contain those
     * villages' centroids). Villages whose dominant residents have
     * no house affiliation cluster into a single "Commons" province.
     */
    private static List<Province> tribal(ServerLevel level, VillageSavedData data,
                                         Kingdom kingdom, long tick) {
        Map<Optional<UUID>, List<UUID>> byHouse = new LinkedHashMap<>();
        for (UUID vid : kingdom.getVillageIds()) {
            Optional<UUID> houseId = dominantHouseOfVillage(data, kingdom, vid);
            byHouse.computeIfAbsent(houseId, k -> new ArrayList<>()).add(vid);
        }
        if (byHouse.size() < 2) {
            // All villages in one bucket (e.g. all unaffiliated) — no
            // meaningful subdivision; treat as UNITARY.
            return List.of();
        }
        Set<Long> cellPool = collectCellPool(data, kingdom);

        // Pre-compute group keys + names + village centroids per group.
        List<Optional<UUID>> groupKeys = new ArrayList<>(byHouse.keySet());
        List<List<UUID>> groupVillages = new ArrayList<>();
        List<List<BlockPos>> groupCentres = new ArrayList<>();
        List<String> groupNames = new ArrayList<>();
        List<UUID> groupSeedKeys = new ArrayList<>();
        int idx = 1;
        for (Optional<UUID> houseId : groupKeys) {
            List<UUID> villageIds = byHouse.get(houseId);
            groupVillages.add(villageIds);
            groupCentres.add(centresOf(data, villageIds));
            groupNames.add(houseId
                    .flatMap(kingdom::findHouse)
                    .map(House::name)
                    .orElse("Commons of " + kingdom.getName()));
            groupSeedKeys.add(houseId.orElse(new UUID(0L, idx)));
            idx++;
        }

        // Partition cells across groups by nearest centroid.
        List<List<Long>> cellsByGroup = partitionCellsAcrossGroups(cellPool, groupCentres);

        List<Province> out = new ArrayList<>();
        for (int i = 0; i < groupKeys.size(); i++) {
            UUID provinceId = derivedId(kingdom.getId(), groupSeedKeys.get(i));
            out.add(Province.fresh(provinceId, groupNames.get(i), kingdom.getId(),
                    Optional.empty(), groupVillages.get(i), cellsByGroup.get(i), tick));
        }
        return out;
    }

    // ── Strategy: FUNCTIONAL (CITY_STATE_LEAGUE) ────────────────────────────

    /**
     * Groups villages by their derived primary role. Reads each
     * village's {@code VillageTag} set; folds role-tags into a
     * single primary per village (TRADE wins over INDUSTRIAL wins
     * over AGRICULTURAL etc., default to AGRICULTURAL).
     */
    private static List<Province> functional(ServerLevel level, VillageSavedData data,
                                             Kingdom kingdom, long tick) {
        Map<String, List<UUID>> byRole = new LinkedHashMap<>();
        for (UUID vid : kingdom.getVillageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            String role = primaryRoleOf(v);
            byRole.computeIfAbsent(role, k -> new ArrayList<>()).add(vid);
        }
        if (byRole.size() < 2) return List.of();
        Set<Long> cellPool = collectCellPool(data, kingdom);

        List<String> roleKeys = new ArrayList<>(byRole.keySet());
        List<List<UUID>> groupVillages = new ArrayList<>();
        List<List<BlockPos>> groupCentres = new ArrayList<>();
        for (String role : roleKeys) {
            List<UUID> villageIds = byRole.get(role);
            groupVillages.add(villageIds);
            groupCentres.add(centresOf(data, villageIds));
        }

        List<List<Long>> cellsByGroup = partitionCellsAcrossGroups(cellPool, groupCentres);

        List<Province> out = new ArrayList<>();
        for (int i = 0; i < roleKeys.size(); i++) {
            String role = roleKeys.get(i);
            UUID provinceId = derivedId(kingdom.getId(),
                    new UUID(role.hashCode() & 0xFFFFFFFFL, i + 1));
            out.add(Province.fresh(provinceId, capitalise(role) + " Province",
                    kingdom.getId(), Optional.empty(),
                    groupVillages.get(i), cellsByGroup.get(i), tick));
        }
        return out;
    }

    // ── Seeds + helpers ─────────────────────────────────────────────────────

    private record Seed(UUID id, int blockX, int blockZ) {}

    private static List<Seed> collectManorSeeds(VillageSavedData data, Kingdom kingdom) {
        List<Seed> seeds = new ArrayList<>();
        for (UUID vid : kingdom.getVillageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            for (UUID buildingId : v.getBuildingIds()) {
                Building b = data.getBuildingById(buildingId).orElse(null);
                if (b == null) continue;
                if (b.getType() != BuildingType.NOBLE_MANOR) continue;
                BlockPos origin = b.getShape().getOrigin();
                if (origin == null) continue;
                seeds.add(new Seed(b.getId(), origin.getX(), origin.getZ()));
            }
        }
        return seeds;
    }

    private static List<Seed> collectVillageSeeds(VillageSavedData data, Kingdom kingdom) {
        List<Seed> seeds = new ArrayList<>();
        for (UUID vid : kingdom.getVillageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            BlockPos centre = villageCentre(v, data);
            if (centre == null) continue;
            seeds.add(new Seed(v.getId(), centre.getX(), centre.getZ()));
        }
        return seeds;
    }

    private static BlockPos villageCentre(Village v, VillageSavedData data) {
        return v.getBounds(data)
                .map(b -> new BlockPos((int) b.getCenter().x, (int) b.getCenter().y,
                        (int) b.getCenter().z))
                .orElse(null);
    }

    private static Set<Long> collectCellPool(VillageSavedData data, Kingdom kingdom) {
        // Prefer the kingdom's territorial claim; fall back to a
        // 5×5 cell halo around each village centroid when no claim
        // exists.
        var claim = kingdom.getTerritorialClaim().orElse(null);
        if (claim != null && !claim.claimedCellKeys().isEmpty()) {
            return new LinkedHashSet<>(claim.claimedCellKeys());
        }
        Set<Long> cells = new LinkedHashSet<>();
        for (UUID vid : kingdom.getVillageIds()) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            BlockPos c = villageCentre(v, data);
            if (c == null) continue;
            int cx = c.getX() >> AtlasCell.CELL_SHIFT;
            int cz = c.getZ() >> AtlasCell.CELL_SHIFT;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    cells.add(AtlasCell.packKey(cx + dx, cz + dz));
                }
            }
        }
        return cells;
    }

    private static Seed nearestSeed(List<Seed> seeds, int blockX, int blockZ) {
        Seed best = seeds.get(0);
        long bestDist = manhattan(best, blockX, blockZ);
        for (int i = 1; i < seeds.size(); i++) {
            Seed s = seeds.get(i);
            long d = manhattan(s, blockX, blockZ);
            if (d < bestDist || (d == bestDist && s.id().compareTo(best.id()) < 0)) {
                best = s;
                bestDist = d;
            }
        }
        return best;
    }

    private static long manhattan(Seed s, int blockX, int blockZ) {
        return Math.abs((long) s.blockX() - blockX) + Math.abs((long) s.blockZ() - blockZ);
    }

    private static List<BlockPos> centresOf(VillageSavedData data, List<UUID> villageIds) {
        List<BlockPos> out = new ArrayList<>();
        for (UUID vid : villageIds) {
            Village v = data.getVillageById(vid).orElse(null);
            if (v == null) continue;
            BlockPos c = villageCentre(v, data);
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * Cross-group cell partition: each cell is assigned to the group
     * whose nearest member centroid is closest. Groups with no
     * centroids never claim cells. Returns a list of cell-lists in
     * the same order as {@code groupCentres}.
     */
    private static List<List<Long>> partitionCellsAcrossGroups(Set<Long> cellPool,
                                                               List<List<BlockPos>> groupCentres) {
        int n = groupCentres.size();
        List<List<Long>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());
        for (long cellKey : cellPool) {
            int cx = AtlasCell.unpackX(cellKey);
            int cz = AtlasCell.unpackZ(cellKey);
            int blockX = (cx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            int blockZ = (cz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
            int bestGroup = -1;
            long bestDist = Long.MAX_VALUE;
            for (int g = 0; g < n; g++) {
                List<BlockPos> centres = groupCentres.get(g);
                for (BlockPos c : centres) {
                    long d = Math.abs((long) c.getX() - blockX)
                            + Math.abs((long) c.getZ() - blockZ);
                    if (d < bestDist) {
                        bestDist = d;
                        bestGroup = g;
                    }
                }
            }
            if (bestGroup >= 0) out.get(bestGroup).add(cellKey);
        }
        return out;
    }

    // ── Tribal helpers ──────────────────────────────────────────────────────

    /**
     * Returns the dominant noble house among NPCs assigned to the
     * village. "Dominant" = the house with the highest-rank loaded
     * representative; ties broken by prestige then UUID. Empty
     * Optional means no qualifying noble lives in the village.
     */
    private static Optional<UUID> dominantHouseOfVillage(VillageSavedData data,
                                                         Kingdom kingdom, UUID villageId) {
        Village v = data.getVillageById(villageId).orElse(null);
        if (v == null) return Optional.empty();
        // For determinism, we look at the house list stored on the
        // kingdom and check which house's head's UUID matches the
        // village's leader / a resident. v1 simplification: pick the
        // house whose head is the village leader, if any.
        Optional<UUID> leaderId = v.getVillageLeaderId();
        if (leaderId.isEmpty()) return Optional.empty();
        UUID leader = leaderId.get();
        for (House h : kingdom.getHouses()) {
            if (h.headUuid().filter(leader::equals).isPresent()) {
                return Optional.of(h.id());
            }
            if (h.founderUuid().equals(leader)) return Optional.of(h.id());
        }
        return Optional.empty();
    }

    // ── Functional helpers ─────────────────────────────────────────────────

    /**
     * Derives a single primary role tag from a village's type's
     * tag set ({@link VillageTypeData#getTags}). Priority: TRADE >
     * DEFENSIVE > INDUSTRIAL > RELIGIOUS > AGRICULTURAL > REMOTE >
     * (default) AGRICULTURAL. Tag taxonomy is environment-flavoured
     * today; D3.4 may add an explicit role tag.
     */
    private static String primaryRoleOf(Village v) {
        VillageTypeData type = VillageTypeRegistry.INSTANCE.getType(v.getVillageType());
        if (type == null) return "AGRICULTURAL";
        Set<VillageTag> tags = type.getTags();
        if (tags == null || tags.isEmpty()) return "AGRICULTURAL";
        if (tags.contains(VillageTag.TRADE))        return "TRADE";
        if (tags.contains(VillageTag.DEFENSIVE))    return "DEFENSIVE";
        if (tags.contains(VillageTag.INDUSTRIAL))   return "INDUSTRIAL";
        if (tags.contains(VillageTag.RELIGIOUS))    return "RELIGIOUS";
        if (tags.contains(VillageTag.AGRICULTURAL)) return "AGRICULTURAL";
        if (tags.contains(VillageTag.REMOTE))       return "REMOTE";
        return "AGRICULTURAL";
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    // ── Identity + merge helpers ────────────────────────────────────────────

    /**
     * Stable province UUID derived from kingdom + seed UUID. Same
     * inputs → same UUID across reloads, so re-runs that produce
     * the same seed pair preserve province identity (and therefore
     * treasury / report buffer / modifiers via
     * {@link #mergeWithExisting}).
     */
    private static UUID derivedId(UUID kingdomId, UUID seedId) {
        long high = kingdomId.getMostSignificantBits()
                ^ Long.rotateLeft(seedId.getMostSignificantBits(), 21);
        long low  = kingdomId.getLeastSignificantBits()
                ^ Long.rotateLeft(seedId.getLeastSignificantBits(), 13);
        return new UUID(high, low);
    }

    /**
     * Carries treasury / report buffer / modifiers from
     * existing same-id provinces onto the freshly-computed list.
     * New provinces get default state.
     */
    private static List<Province> mergeWithExisting(List<Province> fresh, Kingdom kingdom) {
        if (fresh.isEmpty()) return fresh;
        Map<UUID, Province> existing = new HashMap<>();
        for (Province p : kingdom.getProvinces()) existing.put(p.id(), p);
        List<Province> merged = new ArrayList<>(fresh.size());
        for (Province f : fresh) {
            Province old = existing.get(f.id());
            if (old == null) {
                merged.add(f);
            } else {
                // Preserve old's treasury / governor / modifiers / reports;
                // adopt new's village + cell membership + name.
                merged.add(new Province(
                        f.id(), f.name(), f.kingdomId(), old.governorUuid(),
                        f.villageIds(), f.cellKeys(),
                        old.stability(), old.treasury(), old.createdTick(),
                        old.modifiers(), old.reports()));
            }
        }
        return merged;
    }
}
