package tterrag1112.life_in_the_village.Gui.Map.Kingdom;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;

import java.util.*;

/**
 * Immutable client-side snapshot built once when the map screen opens.
 * Consumed by {@link KingdomMapPanel} and its layers. Contains no
 * references to server objects — safe to hold for the lifetime of
 * the screen without worrying about chunk loads.
 */
public final class KingdomMapData {

    /** Focus kingdom being inspected. */
    public final UUID focusKingdomId;
    public final String focusKingdomName;

    /** Viewport bounds in cell coords (inclusive), already padded. */
    public final int minCellX, minCellZ, maxCellX, maxCellZ;

    /** Atlas cells covering the viewport (cells outside = unknown). */
    public final Map<Long, AtlasCell> terrainGrid;

    /** Cell keys owned by the focus kingdom. */
    public final Set<Long> focusCells;

    /** Foreign kingdoms that overlap the viewport. */
    public final List<ForeignKingdom> foreignKingdoms;

    /** All villages visible in the viewport (focus + foreign). */
    public final List<VillageMarker> villages;

    /** Land trade route paths as screen-ready cell-level polylines (in world-block coords). */
    public final List<RoutePath> landRoutes;

    /** Sea routes as cell-centre polylines (in cell coords → block coords at centre). */
    public final List<RoutePath> seaRoutes;

    public KingdomMapData(UUID focusKingdomId, String focusKingdomName,
                          int minCellX, int minCellZ, int maxCellX, int maxCellZ,
                          Map<Long, AtlasCell> terrainGrid,
                          Set<Long> focusCells,
                          List<ForeignKingdom> foreignKingdoms,
                          List<VillageMarker> villages,
                          List<RoutePath> landRoutes,
                          List<RoutePath> seaRoutes) {
        this.focusKingdomId  = focusKingdomId;
        this.focusKingdomName = focusKingdomName;
        this.minCellX = minCellX; this.minCellZ = minCellZ;
        this.maxCellX = maxCellX; this.maxCellZ = maxCellZ;
        this.terrainGrid = Collections.unmodifiableMap(terrainGrid);
        this.focusCells  = Collections.unmodifiableSet(focusCells);
        this.foreignKingdoms = List.copyOf(foreignKingdoms);
        this.villages    = List.copyOf(villages);
        this.landRoutes  = List.copyOf(landRoutes);
        this.seaRoutes   = List.copyOf(seaRoutes);
    }

    // =========================================================================
    // Supporting types
    // =========================================================================

    public record ForeignKingdom(UUID id, String name, Set<Long> cells) {}

    public record VillageMarker(UUID id, UUID kingdomId,
                                String name, BlockPos worldPos,
                                boolean isCapital) {}

    /**
     * A polyline through either block-space waypoints (land — sampled from
     * graph edge cellPaths) or cell-centre waypoints (sea — from cell keys).
     * Either way, stored as world-block coords; {@link MapProjection}
     * handles the mapping.
     */
    public record RoutePath(UUID connectionId, UUID villageA, UUID villageB,
                            List<BlockPos> waypoints, boolean isSea) {}
}