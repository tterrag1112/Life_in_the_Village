// src/main/java/tterrag1112/life_in_the_village/Village/Planning/StructureSizeCache.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;

import java.util.HashMap;
import java.util.Map;

/**
 * Caches the XZ footprint dimensions of structure NBT files so
 * {@link VillagePlanner} can use real building sizes for overlap
 * avoidance rather than a fixed estimate.
 *
 * <h3>Footprint radius</h3>
 * The "radius" stored per structure is the half-diagonal of the
 * rotated XZ footprint — the worst-case distance from centre to
 * any corner — plus a 2-block clearance gap. This is the value
 * used in {@link LayoutSlot#overlaps} Chebyshev distance check.
 *
 * <h3>Thread safety</h3>
 * Populated once during the planning phase on the server thread.
 * Not thread-safe; do not access from multiple threads.
 */
public class StructureSizeCache {

    /** Fallback when a structure file cannot be loaded. */
    public static final int DEFAULT_RADIUS = 8;
    /** Minimum gap between any two building footprints (blocks). */
    public static final int MIN_GAP        = 3;

    private final ServerLevel level;
    private final Map<String, FootprintInfo> cache = new HashMap<>();

    public StructureSizeCache(ServerLevel level) {
        this.level = level;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the footprint info for the given structure path, loading
     * and caching it on first access.
     *
     * @param structurePath e.g. "blacksmith/level_1"
     * @param rotation      the rotation that will be applied at placement
     */
    public FootprintInfo get(String structurePath, Rotation rotation) {
        // Cache key includes rotation since it swaps W and L
        String key = structurePath + ":" + rotation.name();
        return cache.computeIfAbsent(key,
                k -> load(structurePath, rotation));
    }

    /**
     * Returns the half-diagonal radius (+ gap) for overlap checking.
     * Equivalent to the old hardcoded {@code DEFAULT_BUILDING_RADIUS}.
     */
    public int getRadius(String structurePath, Rotation rotation) {
        return get(structurePath, rotation).overlapRadius();
    }

    // =========================================================================
    // Loading
    // =========================================================================

    private FootprintInfo load(String structurePath, Rotation rotation) {
        var id = Identifier
                .fromNamespaceAndPath(
                        tterrag1112.life_in_the_village
                                .Life_in_the_village.MODID,
                        structurePath);

        var templateOpt = BuildingPlacer.loadTemplate(level, id);
        if (templateOpt.isEmpty()) {
            System.out.println("StructureSizeCache: could not load '"
                    + structurePath + "' — using default radius");
            return new FootprintInfo(
                    DEFAULT_RADIUS * 2, DEFAULT_RADIUS * 2,
                    DEFAULT_RADIUS);
        }

        Vec3i raw = templateOpt.get().getSize();
        int w = raw.getX();
        int l = raw.getZ();

        // Swap W and L for 90/270-degree rotations
        if (rotation == Rotation.CLOCKWISE_90
                || rotation == Rotation.COUNTERCLOCKWISE_90) {
            int tmp = w; w = l; l = tmp;
        }

        // Half-diagonal: worst-case distance from centre to corner
        int halfW   = w / 2;
        int halfL   = l / 2;
        double diag = Math.sqrt(halfW * halfW + halfL * halfL);
        int radius  = (int) Math.ceil(diag) + MIN_GAP;

        return new FootprintInfo(w, l, radius);
    }

    // =========================================================================
    // Data record
    // =========================================================================

    /**
     * Holds the XZ footprint dimensions and the precomputed overlap
     * radius for a single structure + rotation combination.
     *
     * @param width         X dimension after rotation
     * @param length        Z dimension after rotation
     * @param overlapRadius half-diagonal + MIN_GAP — use in
     *                      {@link LayoutSlot#overlaps}
     */
    public record FootprintInfo(int width, int length, int overlapRadius) {}
}