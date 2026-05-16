// src/main/java/tterrag1112/life_in_the_village/Village/Planning/StructureSizeCache.java
package tterrag1112.life_in_the_village.Village.Planning;

import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.BuildingPlacer;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.CultureResolver;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.BuildingVariant;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <h3>Keying — P0a-05</h3>
 * Cache entries are keyed by {@code (culture, style, type, variantId,
 * level, rotation)}. The legacy {@link #get(String, Rotation)}
 * overload accepting a {@code "{type}/level_{n}"} string survives as
 * a temporary bridge for planning-layer call sites that don't yet
 * know about variants — those callers get
 * {@code (culture=default, style=RURAL, variantId=type-default)}
 * defaults. P0a-06 upgrades them when the matcher learns variants.
 *
 * <h3>Footprint resolution</h3>
 * The NBT is the single source of truth for variant geometry (doc
 * 15 §"Footprint resolution"). The cache always loads the resolved
 * NBT and reads its declared size; manifests do not carry a
 * footprint field, and any legacy {@code footprint} block in older
 * manifests is silently ignored.
 *
 * <h3>Thread safety</h3>
 * Populated once during the planning phase on the server thread.
 * Not thread-safe; do not access from multiple threads.
 */
public class StructureSizeCache implements FootprintProvider {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StructureSizeCache.class);

    /** Fallback when a structure file cannot be loaded. */
    public static final int DEFAULT_RADIUS = 16;
    /** Minimum gap between any two building footprints (blocks). */
    public static final int MIN_GAP        = 3;

    private static final String DEFAULT_CULTURE = "default";

    /** Resolved Identifiers we've already warned about. Mirrors the
     *  per-key dedupe pattern in {@code CultureResolver
     *  .WARNED_DEFAULT_FALLBACK}. Keeps the missing-NBT warning to
     *  one line per absent file instead of one per planning lookup. */
    private static final Set<String> WARNED_MISSING_NBT =
            ConcurrentHashMap.newKeySet();

    private final ServerLevel level;
    private final Map<CacheKey, FootprintInfo> cache = new HashMap<>();

    public StructureSizeCache(ServerLevel level) {
        this.level = level;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Variant-aware lookup. Used by P0a-06 once the matcher passes
     * full (culture, style, type, variantId, level) to the planner.
     */
    public FootprintInfo get(String culture, Style style, BuildingType type,
                             String variantId, int level, Rotation rotation) {
        CacheKey key = new CacheKey(
                culture, style, type, variantId, level, rotation);
        return cache.computeIfAbsent(key, k -> load(k));
    }

    /**
     * Returns the footprint info for the given legacy structure path,
     * loading and caching it on first access.
     *
     * <p>Bridge for planning-layer callers that still pass the legacy
     * {@code "{type}/level_{n}"} shape. Defaults to
     * {@code culture=default}, {@code style=RURAL},
     * {@code variantId=type-default}. Non-canonical paths (e.g. the
     * market-stall sub-piece) keep the previous direct {@code
     * default/{path}} lookup so unrelated subsystems are unaffected.</p>
     *
     * @param structurePath e.g. "blacksmith/level_1"
     * @param rotation      the rotation that will be applied at placement
     */
    public FootprintInfo get(String structurePath, Rotation rotation) {
        // Known: type-default variant assumption. Resolved by V2 Layer 3,
        // which selects variant at placement time. See
        // ADAPTIVE-VILLAGE-DESIGN.md.
        CultureResolver.LegacyTypeLevel parsed =
                CultureResolver.parseLegacyTypeLevel(structurePath);
        if (parsed != null) {
            return get(DEFAULT_CULTURE, Style.RURAL, parsed.type(),
                    BuildingVariant.defaultVariantId(parsed.type()),
                    parsed.level(), rotation);
        }

        // Non-canonical path: fall back to NBT measurement at the raw
        // path, mirroring the pre-P0a-05 behaviour for sub-pieces.
        return loadRawPath(structurePath, rotation);
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

    private FootprintInfo load(CacheKey k) {
        // Doc 15 §"Footprint resolution": the NBT is the single
        // source of truth. Always measure via the seven-step resolver
        // so the size cache and the placer agree on which file is
        // loaded.
        Identifier id = CultureResolver.resolve(
                k.culture(), k.style(), k.type(), k.variantId(),
                k.level(), level);
        return measure(id, k.rotation(), describe(k));
    }

    /** Bridge path for non-canonical structure paths (e.g. market sub-pieces). */
    private FootprintInfo loadRawPath(String structurePath, Rotation rotation) {
        Identifier id = Identifier.fromNamespaceAndPath(
                Life_in_the_village.MODID,
                DEFAULT_CULTURE + "/" + structurePath);
        return measure(id, rotation, structurePath);
    }

    private FootprintInfo measure(Identifier id, Rotation rotation,
                                  String diagnosticName) {
        var templateOpt = BuildingPlacer.loadTemplate(level, id);
        if (templateOpt.isEmpty()) {
            // Once-per-key dedupe: the same NBT gets queried many times
            // per planning pass (one lookup per slot for that type), and
            // pre-V2 the warning could fire hundreds of times for a
            // single missing file. Mirrors CultureResolver's WARNED_*
            // pattern.
            if (WARNED_MISSING_NBT.add(id.toString())) {
                LOGGER.warn("StructureSizeCache: no NBT authored for '{}' "
                        + "(resolved to {}) — using fallback footprint",
                        diagnosticName, id);
            }
            return new FootprintInfo(
                    DEFAULT_RADIUS * 2, DEFAULT_RADIUS * 2,
                    DEFAULT_RADIUS);
        }

        Vec3i raw = templateOpt.get().getSize();
        return rotated(raw.getX(), raw.getZ(), rotation);
    }

    /**
     * Builds a {@link FootprintInfo} from raw width/length and a
     * placement rotation. Shared by the override and NBT paths so
     * the radius calculation lives in one place.
     */
    private static FootprintInfo rotated(int rawW, int rawL, Rotation rotation) {
        int w = rawW;
        int l = rawL;

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

    private static String describe(CacheKey k) {
        return k.culture() + "/" + k.style().folder() + "/"
                + k.type().name().toLowerCase() + "/" + k.variantId()
                + "/level_" + k.level();
    }

    // =========================================================================
    // Data records
    // =========================================================================

    /**
     * Cache key. Includes rotation since a 90°/270° rotation swaps W
     * and L and so produces a different {@link FootprintInfo}.
     */
    public record CacheKey(String culture, Style style, BuildingType type,
                           String variantId, int level, Rotation rotation) {}

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
