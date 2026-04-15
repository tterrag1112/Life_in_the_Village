// src/main/java/tterrag1112/life_in_the_village/World/Atlas/Regions/AtlasRegionIndex.java
package tterrag1112.life_in_the_village.World.Atlas.Regions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Region / ocean index derived by flood-filling the atlas.
 *
 * <h3>Build strategy — lazy, bounded</h3>
 * Regions are not built eagerly. They're computed on demand in a
 * bounded area around a query point via {@link #buildAround}. Each
 * invocation re-floods that area's cells, which is acceptable because
 * region lookups happen rarely (during kingdom seeding, not per-tick).
 *
 * <h3>Small-patch absorption</h3>
 * Groups smaller than {@link #MIN_REGION_SIZE} are merged into the
 * largest adjacent region. This prevents a 3-cell desert embedded in
 * a jungle from being treated as a separate "desert region" that a
 * kingdom could choose to claim.
 *
 * <h3>Compatibility grouping</h3>
 * During flood-fill we treat categories as "compatible" if they'd be
 * similar for claim purposes — e.g. FOREST neighbours merge with PLAINS
 * if one is small enough to be absorbed. Ocean and river cells are
 * never merged into land regions.
 */
public class AtlasRegionIndex {

    /** Groups smaller than this get absorbed into neighbouring regions. */
    public static final int MIN_REGION_SIZE = 8;

    /** How far (in cells) from the query point to flood during buildAround. */
    public static final int DEFAULT_BUILD_RADIUS_CELLS = 40;

    // =========================================================================
    // State
    // =========================================================================

    private final Map<Integer, AtlasRegion> regions = new HashMap<>();
    private final Map<Integer, AtlasOcean>  oceans  = new HashMap<>();

    /** Reverse index: cell key → region id (or ocean id). */
    private final Map<Long, Integer> cellToRegion = new HashMap<>();
    private final Map<Long, Integer> cellToOcean  = new HashMap<>();

    private int nextRegionId = 1;
    private int nextOceanId  = 1;

    // =========================================================================
    // Codec / persistence
    // =========================================================================

    public record Snapshot(
            List<AtlasRegion> regions,
            List<AtlasOcean>  oceans,
            int nextRegionId,
            int nextOceanId
    ) {
        public static final Codec<Snapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                AtlasRegion.CODEC.listOf()
                        .optionalFieldOf("regions", new ArrayList<>())
                        .forGetter(Snapshot::regions),
                AtlasOcean.CODEC.listOf()
                        .optionalFieldOf("oceans", new ArrayList<>())
                        .forGetter(Snapshot::oceans),
                Codec.INT.optionalFieldOf("nextRegionId", 1).forGetter(Snapshot::nextRegionId),
                Codec.INT.optionalFieldOf("nextOceanId",  1).forGetter(Snapshot::nextOceanId)
        ).apply(i, Snapshot::new));
    }

    public static final Codec<AtlasRegionIndex> CODEC = Snapshot.CODEC.xmap(
            snap -> {
                AtlasRegionIndex idx = new AtlasRegionIndex();
                for (AtlasRegion r : snap.regions()) {
                    idx.regions.put(r.id(), r);
                    for (long key : r.cellKeys()) idx.cellToRegion.put(key, r.id());
                }
                for (AtlasOcean o : snap.oceans()) {
                    idx.oceans.put(o.id(), o);
                    for (long key : o.cellKeys()) idx.cellToOcean.put(key, o.id());
                }
                idx.nextRegionId = snap.nextRegionId();
                idx.nextOceanId  = snap.nextOceanId();
                return idx;
            },
            idx -> new Snapshot(
                    new ArrayList<>(idx.regions.values()),
                    new ArrayList<>(idx.oceans.values()),
                    idx.nextRegionId, idx.nextOceanId)
    );

    // =========================================================================
    // Queries
    // =========================================================================

    @Nullable
    public AtlasRegion getRegionAt(int cellX, int cellZ) {
        Integer id = cellToRegion.get(AtlasCell.packKey(cellX, cellZ));
        return id == null ? null : regions.get(id);
    }

    @Nullable
    public AtlasOcean getOceanAt(int cellX, int cellZ) {
        Integer id = cellToOcean.get(AtlasCell.packKey(cellX, cellZ));
        return id == null ? null : oceans.get(id);
    }

    public Collection<AtlasRegion> allRegions() { return regions.values(); }
    public Collection<AtlasOcean>  allOceans()  { return oceans.values();  }

    /** True if the cell belongs to any known region or ocean. */
    public boolean isIndexed(int cellX, int cellZ) {
        long key = AtlasCell.packKey(cellX, cellZ);
        return cellToRegion.containsKey(key) || cellToOcean.containsKey(key);
    }

    // =========================================================================
    // Building
    // =========================================================================

    /**
     * Floods regions/oceans within {@code radiusCells} of the given cell
     * coordinate. Cells already assigned to a region/ocean are skipped,
     * so repeated calls over overlapping areas are cheap.
     *
     * <p>The atlas should already be filled for the area being indexed —
     * this method does not sample.
     */
    public void buildAround(WorldAtlas atlas, int cellX, int cellZ, int radiusCells) {
        // Collect candidate cells in range that aren't yet indexed
        List<AtlasCell> candidates = new ArrayList<>();
        for (int dx = -radiusCells; dx <= radiusCells; dx++) {
            for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                int tcx = cellX + dx;
                int tcz = cellZ + dz;
                if (isIndexed(tcx, tcz)) continue;
                AtlasCell cell = atlas.getCellByCoord(tcx, tcz);
                if (cell == null) continue;
                candidates.add(cell);
            }
        }

        Set<Long> visited = new HashSet<>();
        List<List<AtlasCell>> rawGroups = new ArrayList<>();

        // Flood-fill groups of same-category cells
        for (AtlasCell seed : candidates) {
            if (visited.contains(seed.key())) continue;
            List<AtlasCell> group = floodFill(atlas, seed, visited, candidates);
            if (!group.isEmpty()) rawGroups.add(group);
        }

        // Separate oceans first (they don't participate in absorption)
        List<List<AtlasCell>> landGroups = new ArrayList<>();
        for (List<AtlasCell> group : rawGroups) {
            if (group.get(0).category() == BiomeCategory.OCEAN) {
                commitOcean(group);
            } else {
                landGroups.add(group);
            }
        }

        // Absorb small land groups into larger neighbours
        landGroups.sort(Comparator.comparingInt(List::size)); // smallest first
        for (List<AtlasCell> group : landGroups) {
            if (group.size() >= MIN_REGION_SIZE) {
                commitRegion(group, group.get(0).category());
            } else {
                // Try to absorb into an adjacent existing region
                Integer absorbInto = findDominantNeighbourRegion(atlas, group);
                if (absorbInto != null) {
                    absorbGroupInto(group, absorbInto);
                } else {
                    // No suitable neighbour — commit as-is
                    commitRegion(group, group.get(0).category());
                }
            }
        }
    }

    /** Flood-fill compatible cells starting from seed. */
    private List<AtlasCell> floodFill(WorldAtlas atlas, AtlasCell seed,
                                      Set<Long> visited,
                                      List<AtlasCell> candidatePool) {
        // Build a quick lookup by key to know which cells are in scope
        Set<Long> inScope = new HashSet<>(candidatePool.size());
        for (AtlasCell c : candidatePool) inScope.add(c.key());

        List<AtlasCell> group = new ArrayList<>();
        Deque<AtlasCell> stack = new ArrayDeque<>();
        stack.push(seed);
        visited.add(seed.key());
        BiomeCategory seedCat = seed.category();

        while (!stack.isEmpty()) {
            AtlasCell cur = stack.pop();
            group.add(cur);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (Math.abs(dx) + Math.abs(dz) == 2) continue; // 4-neighbour only
                    AtlasCell nb = atlas.getCellByCoord(cur.cellX() + dx, cur.cellZ() + dz);
                    if (nb == null) continue;
                    if (visited.contains(nb.key())) continue;
                    if (!inScope.contains(nb.key())) continue;
                    if (!categoriesMatch(seedCat, nb.category())) continue;
                    visited.add(nb.key());
                    stack.push(nb);
                }
            }
        }
        return group;
    }

    /**
     * Strict category matching for initial flood-fill. Absorption of
     * small groups into dissimilar neighbours happens later.
     */
    private static boolean categoriesMatch(BiomeCategory a, BiomeCategory b) {
        return a == b;
    }

    /** Find the region that borders the most cells of this small group. */
    @Nullable
    private Integer findDominantNeighbourRegion(WorldAtlas atlas, List<AtlasCell> group) {
        Map<Integer, Integer> neighbourCounts = new HashMap<>();
        for (AtlasCell c : group) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long nbKey = AtlasCell.packKey(c.cellX() + dx, c.cellZ() + dz);
                    Integer rid = cellToRegion.get(nbKey);
                    if (rid == null) continue;
                    neighbourCounts.merge(rid, 1, Integer::sum);
                }
            }
        }
        return neighbourCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private void absorbGroupInto(List<AtlasCell> group, int targetRegionId) {
        AtlasRegion target = regions.get(targetRegionId);
        if (target == null) { commitRegion(group, group.get(0).category()); return; }
        List<Long> merged = new ArrayList<>(target.cellKeys());
        for (AtlasCell c : group) {
            merged.add(c.key());
            cellToRegion.put(c.key(), targetRegionId);
        }
        regions.put(targetRegionId, new AtlasRegion(
                target.id(), target.category(), target.centroidKey(), merged));
    }

    private void commitRegion(List<AtlasCell> group, BiomeCategory category) {
        int id = nextRegionId++;
        List<Long> keys = new ArrayList<>(group.size());
        long sumX = 0, sumZ = 0;
        for (AtlasCell c : group) { keys.add(c.key()); sumX += c.cellX(); sumZ += c.cellZ(); }
        int cx = (int)(sumX / group.size());
        int cz = (int)(sumZ / group.size());
        AtlasRegion r = new AtlasRegion(id, category, AtlasCell.packKey(cx, cz), keys);
        regions.put(id, r);
        for (long key : keys) cellToRegion.put(key, id);
    }

    private void commitOcean(List<AtlasCell> group) {
        int id = nextOceanId++;
        List<Long> keys = new ArrayList<>(group.size());
        long sumX = 0, sumZ = 0;
        for (AtlasCell c : group) { keys.add(c.key()); sumX += c.cellX(); sumZ += c.cellZ(); }
        int cx = (int)(sumX / group.size());
        int cz = (int)(sumZ / group.size());
        AtlasOcean o = new AtlasOcean(id, AtlasCell.packKey(cx, cz), keys);
        oceans.put(id, o);
        for (long key : keys) cellToOcean.put(key, id);
    }
}