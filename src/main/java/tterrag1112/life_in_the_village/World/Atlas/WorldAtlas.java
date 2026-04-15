package tterrag1112.life_in_the_village.World.Atlas;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasOcean;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasRegion;
import tterrag1112.life_in_the_village.World.Atlas.Regions.AtlasRegionIndex;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Persistent virtual map of sampled atlas cells for a single dimension.
 *
 * <h3>Storage</h3>
 * Cells are kept in a {@code Long → AtlasCell} map keyed by
 * {@link AtlasCell#packKey}. The codec serialises as a flat list of
 * cells, then rebuilds the map on load.
 *
 * <h3>Lifecycle</h3>
 * Cells are added by {@link AtlasFillSystem} (lazy fill around players)
 * or by {@link #ensureCell} (synchronous on-demand fill). Once a cell
 * is in the atlas it is never re-sampled — the noise output is fully
 * deterministic so it cannot change.
 *
 * <h3>Adjacency flags</h3>
 * After a cell is added, {@link #computeAdjacencyFlags} updates its
 * coast/river/freshwater flags based on its 8 neighbours. Neighbours
 * that are added later will trigger a re-evaluation of adjacent cells
 * via {@link #onCellAdded}.
 *
 * <h3>NOT a chunk loader</h3>
 * This class never loads, generates, or modifies chunks. All sampling
 * goes through {@link AtlasSampler} which uses noise-only APIs.
 */
public class WorldAtlas extends SavedData {

    // =========================================================================
    // Codec / SavedData wiring
    // =========================================================================

    public record AtlasSnapshot(
            List<AtlasCell> cells,
            List<Long> roadCellKeys,
            List<List<UUID>> roadCellRoadIds,
            AtlasRegionIndex regionIndex
    ) {
        public static final Codec<AtlasSnapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                AtlasCell.CODEC.listOf()
                        .optionalFieldOf("cells", new ArrayList<>())
                        .forGetter(AtlasSnapshot::cells),
                Codec.LONG.listOf()
                        .optionalFieldOf("roadCellKeys", new ArrayList<>())
                        .forGetter(AtlasSnapshot::roadCellKeys),
                Codec.STRING.xmap(UUID::fromString, UUID::toString)
                        .listOf().listOf()
                        .optionalFieldOf("roadCellRoadIds", new ArrayList<>())
                        .forGetter(AtlasSnapshot::roadCellRoadIds),
                AtlasRegionIndex.CODEC
                        .optionalFieldOf("regionIndex", new AtlasRegionIndex())
                        .forGetter(AtlasSnapshot::regionIndex)
        ).apply(i, AtlasSnapshot::new));
    }

    public static final Codec<WorldAtlas> CODEC = AtlasSnapshot.CODEC.xmap(
            snap -> {
                WorldAtlas a = new WorldAtlas();
                for (AtlasCell c : snap.cells()) a.cells.put(c.key(), c);
                int n = Math.min(snap.roadCellKeys().size(),
                        snap.roadCellRoadIds().size());
                for (int idx = 0; idx < n; idx++) {
                    a.cellRoads.put(
                            snap.roadCellKeys().get(idx),
                            new ArrayList<>(snap.roadCellRoadIds().get(idx)));
                }
                a.regionIndex = snap.regionIndex();
                return a;
            },
            atlas -> {
                List<Long> keys = new ArrayList<>(atlas.cellRoads.size());
                List<List<UUID>> roadIds = new ArrayList<>(atlas.cellRoads.size());
                for (var entry : atlas.cellRoads.entrySet()) {
                    keys.add(entry.getKey());
                    roadIds.add(new ArrayList<>(entry.getValue()));
                }
                return new AtlasSnapshot(
                        new ArrayList<>(atlas.cells.values()),
                        keys, roadIds, atlas.regionIndex);
            }
    );

    public static final SavedDataType<WorldAtlas> TYPE = new SavedDataType<>(
            "life_in_the_village_world_atlas",
            WorldAtlas::new,
            CODEC
    );

    public static WorldAtlas get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // =========================================================================
    // State
    // =========================================================================

    private final Map<Long, AtlasCell> cells = new HashMap<>();

    private final Map<Long, List<UUID>> cellRoads = new HashMap<>();

    private AtlasRegionIndex regionIndex = new AtlasRegionIndex();


    public int size() { return cells.size(); }

    // =========================================================================
    // Coordinate helpers
    // =========================================================================

    public static int blockToCell(int blockCoord) {
        return blockCoord >> AtlasCell.CELL_SHIFT;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    @Nullable
    public AtlasCell getCellByCoord(int cellX, int cellZ) {
        return cells.get(AtlasCell.packKey(cellX, cellZ));
    }

    @Nullable
    public AtlasCell getCellAtBlock(int blockX, int blockZ) {
        return getCellByCoord(blockToCell(blockX), blockToCell(blockZ));
    }

    public boolean isFilled(int cellX, int cellZ) {
        return cells.containsKey(AtlasCell.packKey(cellX, cellZ));
    }

    /**
     * Returns the cell at the given block position, sampling and storing
     * it synchronously if not yet present. Use sparingly — prefer letting
     * {@link AtlasFillSystem} fill regions in advance.
     */
    public AtlasCell ensureCell(ServerLevel level, int blockX, int blockZ) {
        int cx = blockToCell(blockX);
        int cz = blockToCell(blockZ);
        AtlasCell existing = getCellByCoord(cx, cz);
        if (existing != null) return existing;
        AtlasCell sampled = AtlasSampler.sampleCell(level, cx, cz);
        addCell(sampled);
        return sampled;
    }

    /**
     * Returns all cells whose centres lie within {@code radius} blocks
     * of the given block position. Result is unordered.
     */
    public List<AtlasCell> cellsWithinRadius(int blockX, int blockZ, int radius) {
        int rCells = (radius >> AtlasCell.CELL_SHIFT) + 1;
        int cx = blockToCell(blockX);
        int cz = blockToCell(blockZ);
        int radiusSq = radius * radius;
        List<AtlasCell> out = new ArrayList<>();
        for (int dx = -rCells; dx <= rCells; dx++) {
            for (int dz = -rCells; dz <= rCells; dz++) {
                AtlasCell c = getCellByCoord(cx + dx, cz + dz);
                if (c == null) continue;
                int ddx = c.blockCenterX() - blockX;
                int ddz = c.blockCenterZ() - blockZ;
                if (ddx * ddx + ddz * ddz <= radiusSq) out.add(c);
            }
        }
        return out;
    }

    // =========================================================================
    // Mutation
    // =========================================================================

    /**
     * Adds a freshly-sampled cell to the atlas. Triggers an adjacency
     * recompute on this cell and on its 8 neighbours so coast/river-adj
     * flags stay correct as the atlas grows.
     */
    public void addCell(AtlasCell cell) {
        cells.put(cell.key(), cell);
        onCellAdded(cell);
        setDirty();
    }

    private void onCellAdded(AtlasCell added) {
        // Recompute this cell's adjacency flags
        AtlasCell updated = computeAdjacencyFlags(added);
        if (updated.flags() != added.flags()) {
            cells.put(updated.key(), updated);
        }
        // Recompute neighbours — they may now see this cell as river/ocean adj
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                AtlasCell nb = getCellByCoord(added.cellX() + dx, added.cellZ() + dz);
                if (nb == null) continue;
                AtlasCell nbUpdated = computeAdjacencyFlags(nb);
                if (nbUpdated.flags() != nb.flags()) {
                    cells.put(nbUpdated.key(), nbUpdated);
                }
            }
        }
    }

    /**
     * Recomputes the adjacency-derived flag bits (coast, river-adj,
     * freshwater) for one cell based on its 8 neighbours. Returns
     * a new cell with the updated flags. The intrinsic flags
     * (HAS_RIVER, HAS_OCEAN, etc.) are preserved.
     */
    private AtlasCell computeAdjacencyFlags(AtlasCell cell) {
        int newFlags = cell.flags()
                & ~(AtlasCell.FLAG_COAST
                | AtlasCell.FLAG_RIVER_ADJ);
        // Keep FLAG_FRESHWATER if it was set intrinsically
        boolean intrinsicFresh = cell.category().isFreshwater();
        if (!intrinsicFresh) newFlags &= ~AtlasCell.FLAG_FRESHWATER;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                AtlasCell nb = getCellByCoord(
                        cell.cellX() + dx, cell.cellZ() + dz);
                if (nb == null) continue;
                if (nb.category() == BiomeCategory.OCEAN)
                    newFlags |= AtlasCell.FLAG_COAST;
                if (nb.category() == BiomeCategory.RIVER)
                    newFlags |= AtlasCell.FLAG_RIVER_ADJ | AtlasCell.FLAG_FRESHWATER;
                if (nb.category() == BiomeCategory.SWAMP)
                    newFlags |= AtlasCell.FLAG_FRESHWATER;
            }
        }
        return cell.withFlags(newFlags);
    }
    /**
     * Synchronously samples every cell within {@code blockRadius} of
     * the given block position that is not yet in the atlas, subject to
     * a time budget.
     *
     * <p>Returns {@code true} when the region is fully sampled, or
     * {@code false} if the time budget was exceeded before completion.
     * Callers that need the region complete should retry on a subsequent
     * tick until {@code true} is returned.
     *
     * <p>Sampling cost is roughly 50–150 µs per cell. A {@code radius}
     * of 800 blocks (~13 cells radius, ~530 cells total) takes around
     * 30–80 ms total when starting from empty. The {@code timeBudgetNanos}
     * parameter caps per-call work so the server tick is never starved.
     *
     * @param level             the server level to sample from
     * @param blockX            centre X in block coordinates
     * @param blockZ            centre Z in block coordinates
     * @param blockRadius       radius in blocks
     * @param timeBudgetNanos   max nanoseconds to spend before yielding
     * @return true if fully filled, false if budget exhausted
     */
    public boolean ensureRegionFilled(net.minecraft.server.level.ServerLevel level,
                                      int blockX, int blockZ, int blockRadius,
                                      long timeBudgetNanos) {
        long deadline = System.nanoTime() + timeBudgetNanos;

        int rCells = (blockRadius >> AtlasCell.CELL_SHIFT) + 1;
        int cx = blockToCell(blockX);
        int cz = blockToCell(blockZ);
        long radiusSq = (long) blockRadius * blockRadius;

        // Spiral outward from the centre so the most important cells
        // (closest to the requested point) get filled first if we run
        // out of budget.
        for (int r = 0; r <= rCells; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    // Only the ring at radius r
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;

                    int tcx = cx + dx;
                    int tcz = cz + dz;

                    // Block-space distance check (atlas cell centre vs target)
                    int bx = (tcx << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                    int bz = (tcz << AtlasCell.CELL_SHIFT) + AtlasCell.CELL_HALF;
                    long ddx = bx - blockX;
                    long ddz = bz - blockZ;
                    if (ddx * ddx + ddz * ddz > radiusSq) continue;

                    if (isFilled(tcx, tcz)) continue;

                    if (System.nanoTime() > deadline) return false;

                    try {
                        AtlasCell sampled = AtlasSampler.sampleCell(level, tcx, tcz);
                        addCell(sampled);
                    } catch (Exception e) {
                        // Skip cells that fail to sample (dimension unloading)
                    }
                }
            }
        }
        return true;
    }
    // =========================================================================
    // Road tracking
    // =========================================================================

    /**
     * Records that the given road passes through the listed cell keys.
     * Idempotent — if the road is already registered for a cell, no
     * duplicate is added.
     *
     * @param roadId   the road UUID
     * @param cellKeys the list of cell keys (from {@link AtlasCell#key})
     *                 that the road's cell-path traverses
     */
    public void addRoadThroughCells(UUID roadId, List<Long> cellKeys) {
        for (long key : cellKeys) {
            List<UUID> list = cellRoads.computeIfAbsent(
                    key, k -> new ArrayList<>(2));
            if (!list.contains(roadId)) list.add(roadId);
        }
        setDirty();
    }

    /**
     * Removes a road from every cell it was registered with. Walks the
     * full road map — O(C) where C is total cells with roads. Acceptable
     * because road removal is rare under the "never delete roads" policy
     * established for Phase 7a.
     */
    public void removeRoad(UUID roadId) {
        var iter = cellRoads.entrySet().iterator();
        boolean changed = false;
        while (iter.hasNext()) {
            var entry = iter.next();
            if (entry.getValue().remove(roadId)) {
                changed = true;
                if (entry.getValue().isEmpty()) iter.remove();
            }
        }
        if (changed) setDirty();
    }

    /**
     * Returns the road IDs passing through the cell at the given block
     * position, or an empty list if none. Result is unmodifiable.
     */
    public List<UUID> getRoadsThroughCell(int blockX, int blockZ) {
        long key = AtlasCell.packKey(blockToCell(blockX), blockToCell(blockZ));
        List<UUID> list = cellRoads.get(key);
        return list == null ? Collections.emptyList()
                : Collections.unmodifiableList(list);
    }

    /**
     * Returns the road IDs registered for the cell with the given packed key.
     * Used by the router during cost calculation.
     */
    public List<UUID> getRoadsForCellKey(long cellKey) {
        List<UUID> list = cellRoads.get(cellKey);
        return list == null ? Collections.emptyList()
                : Collections.unmodifiableList(list);
    }

    /**
     * Counts how many distinct roads pass within {@code radiusBlocks} of
     * the given block position. Used by site selection to score
     * trade-hub candidate positions higher when near existing roads.
     */
    public int countRoadsNear(int blockX, int blockZ, int radiusBlocks) {
        Set<UUID> seen = new HashSet<>();
        int rCells = (radiusBlocks >> AtlasCell.CELL_SHIFT) + 1;
        int cx = blockToCell(blockX);
        int cz = blockToCell(blockZ);
        for (int dx = -rCells; dx <= rCells; dx++) {
            for (int dz = -rCells; dz <= rCells; dz++) {
                long key = AtlasCell.packKey(cx + dx, cz + dz);
                List<UUID> list = cellRoads.get(key);
                if (list != null) seen.addAll(list);
            }
        }
        return seen.size();
    }

    /**
     * Returns a snapshot of the entire road map for client transmission.
     * The returned map is a defensive copy — modifications do not affect
     * the atlas. Used by the GUI map system and debug commands.
     */
    public Map<Long, List<UUID>> getRoadMapSnapshot() {
        Map<Long, List<UUID>> copy = new HashMap<>(cellRoads.size());
        for (var entry : cellRoads.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    public AtlasRegionIndex getRegionIndex() { return regionIndex; }

    @Nullable
    public AtlasRegion getRegionAt(int cellX, int cellZ) {
        return regionIndex.getRegionAt(cellX, cellZ);
    }

    @Nullable
    public AtlasOcean getOceanAt(int cellX, int cellZ) {
        return regionIndex.getOceanAt(cellX, cellZ);
    }

    /**
     * Ensures the region/ocean index covers the area around the given
     * cell coordinate. Idempotent — already-indexed cells are skipped.
     * Call after ensureRegionFilled() so all relevant cells are sampled.
     */
    public void ensureRegionsIndexed(int cellX, int cellZ, int radiusCells) {
        regionIndex.buildAround(this, cellX, cellZ, radiusCells);
        setDirty();
    }

}