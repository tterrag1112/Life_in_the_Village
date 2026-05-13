package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Track E1 anchor detection — a named feature zone that
 * downstream layout strategies (prompt-2+) may center themselves
 * around.
 *
 * <p>Per the user-locked design:
 * <ul>
 *   <li>Detection is permissive — every anchor with
 *       {@code quality ≥ }{@link #QUALITY_THRESHOLD} is returned,
 *       sorted by quality desc per type.</li>
 *   <li>Orientation, when meaningful, is a normalized 2D
 *       direction vector {@code (dirX, dirZ)} rather than an
 *       enum bucket. Non-linear types carry
 *       {@code dirX = dirZ = 0}.</li>
 *   <li>{@code metadata} is type-specific (see
 *       {@link AnchorDetector}); the map is unmodifiable.</li>
 * </ul>
 *
 * @param id        sequential id ("a0", "a1", …) in detection order
 * @param type      taxonomic type tag
 * @param centre    world-space block position of the anchor's "point"
 * @param quality   {@code [0.0, 1.0]} where 1.0 = ideal example
 * @param extent    axis-aligned bounding rectangle in world blocks
 * @param dirX      normalised X component of orientation
 *                  (0 for non-linear types)
 * @param dirZ      normalised Z component of orientation
 *                  (0 for non-linear types)
 * @param metadata  type-specific extras (unmodifiable)
 */
public record Anchor(
        String id,
        AnchorType type,
        BlockPos centre,
        double quality,
        AnchorExtent extent,
        double dirX,
        double dirZ,
        Map<String, Object> metadata) {

    /** Quality threshold for inclusion — below 0.2 means "barely qualifies." */
    public static final double QUALITY_THRESHOLD = 0.20;

    public Anchor {
        if (quality < 0.0) quality = 0.0;
        if (quality > 1.0) quality = 1.0;
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public boolean hasOrientation() {
        return dirX != 0.0 || dirZ != 0.0;
    }
}
