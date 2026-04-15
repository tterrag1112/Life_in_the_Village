package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.*;

/**
 * Snapshot for the continent-scale map. Similar shape to
 * {@link KingdomMapData} but without the focus/foreign split —
 * every kingdom on the continent is a peer, rendered with its own
 * tint from a deterministic palette.
 *
 * <p>The continent is the connected landmass containing the seed
 * kingdom. Ocean cells form natural borders; the flood-fill stops
 * at them. See {@code ContinentMapScope} for the discovery logic.
 */
public final class ContinentMapData {

    public final UUID seedKingdomId;
    public final String seedKingdomName;

    /** Viewport bounds (inclusive), already padded. */
    public final int minCellX, minCellZ, maxCellX, maxCellZ;

    /** Atlas cells across the continent plus a small buffer. */
    public final Map<Long, AtlasCell> terrainGrid;

    /** Cells reachable from the seed by land flood-fill. Forms the continent outline. */
    public final Set<Long> continentCells;

    /** Every kingdom whose claim intersects the continent, with its own tint. */
    public final List<KingdomRegion> kingdoms;

    public final List<KingdomMapData.VillageMarker> villages;
    public final List<KingdomMapData.RoutePath>     landRoutes;
    public final List<KingdomMapData.RoutePath>     seaRoutes;

    public ContinentMapData(UUID seedKingdomId, String seedKingdomName,
                            int minCellX, int minCellZ, int maxCellX, int maxCellZ,
                            Map<Long, AtlasCell> terrainGrid,
                            Set<Long> continentCells,
                            List<KingdomRegion> kingdoms,
                            List<KingdomMapData.VillageMarker> villages,
                            List<KingdomMapData.RoutePath> landRoutes,
                            List<KingdomMapData.RoutePath> seaRoutes) {
        this.seedKingdomId = seedKingdomId;
        this.seedKingdomName = seedKingdomName;
        this.minCellX = minCellX; this.minCellZ = minCellZ;
        this.maxCellX = maxCellX; this.maxCellZ = maxCellZ;
        this.terrainGrid = Collections.unmodifiableMap(terrainGrid);
        this.continentCells = Collections.unmodifiableSet(continentCells);
        this.kingdoms = List.copyOf(kingdoms);
        this.villages = List.copyOf(villages);
        this.landRoutes = List.copyOf(landRoutes);
        this.seaRoutes = List.copyOf(seaRoutes);
    }

    /**
     * One kingdom as it appears on the continent map. Carries a tint
     * chosen by deterministic hash of the UUID so colors are stable
     * across sessions.
     */
    public record KingdomRegion(UUID id, String name,
                                Set<Long> cells,
                                int tintColor, int borderColor,
                                boolean isSeed) {}
}