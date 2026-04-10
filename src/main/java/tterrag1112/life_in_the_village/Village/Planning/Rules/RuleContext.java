package tterrag1112.life_in_the_village.Village.Planning.Rules;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.TerrainProfile;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Mutable state carried through the rule pipeline during village planning.
 *
 * <h3>Lifecycle</h3>
 * A fresh {@code RuleContext} is created at the start of each village plan.
 * Rules are applied in order; each rule reads the current state and may
 * mutate it. After the last rule runs, the planner reads the final state
 * to build the concrete {@code VillageLayout}.
 *
 * <h3>State categories</h3>
 * <ul>
 *   <li><b>Anchors</b> — named positions that later rules reference
 *       (e.g. "town_square" → chosen flat position near terrain centre)</li>
 *   <li><b>Axis</b> — primary orientation of the village, in radians.
 *       Used for linear and riverine layouts, and for angular zone filters.</li>
 *   <li><b>Zone assignments</b> — maps each building type to the zone
 *       that will host it (inner/middle/outer ring, angular sector).</li>
 *   <li><b>Adjacency requirements</b> — hard constraints on where
 *       specific building types can be placed (miller→river, fisherman→coast).</li>
 *   <li><b>Density</b> — target buildings-per-unit-area, drives the
 *       ring radius calculations in the final layout.</li>
 * </ul>
 *
 * <h3>Null handling</h3>
 * All fields start null/empty. Rules are expected to populate only what
 * they care about; the planner applies sensible defaults for anything
 * left unset.
 */
public final class RuleContext {

    // ── Inputs (set once at construction) ───────────────────────────────────

    private final TerrainProfile terrain;
    private final BlockPos origin;
    private final long seed;

    // ── Anchors ─────────────────────────────────────────────────────────────

    private final Map<String, BlockPos> anchors = new HashMap<>();

    // ── Axis ────────────────────────────────────────────────────────────────

    /** Primary axis angle in radians. Null = no axis constraint. */
    @Nullable private Double axisRad;

    /** Maximum angular deviation tolerated when enforcing the axis (radians). */
    private double axisTolerance = Math.toRadians(30);

    // ── Zone assignments ────────────────────────────────────────────────────

    /**
     * Zone assignment per building type. A building type without an entry
     * falls back to its default zone from {@code ZoneRegistry}.
     */
    private final Map<BuildingType, ZoneSpec> zoneAssignments = new EnumMap<>(BuildingType.class);

    // ── Adjacency constraints ───────────────────────────────────────────────

    /**
     * Hard adjacency requirements. If the constraint cannot be satisfied,
     * the building is not placed. Multiple constraints per type are ANDed.
     */
    private final Map<BuildingType, List<AdjacencyReq>> adjacencyReqs =
            new EnumMap<>(BuildingType.class);

    // ── Density ─────────────────────────────────────────────────────────────

    /** Target buildings per 100 m². Null = use density profile default. */
    @Nullable private Float densityPer100m2;

    /** True if a perimeter wall should be placed regardless of type defaults. */
    private boolean forceWalled = false;

    // =========================================================================
    // Construction
    // =========================================================================

    public RuleContext(TerrainProfile terrain, BlockPos origin, long seed) {
        this.terrain = terrain;
        this.origin  = origin;
        this.seed    = seed;
    }

    // =========================================================================
    // Accessors — read
    // =========================================================================

    public TerrainProfile terrain() { return terrain; }
    public BlockPos origin()        { return origin; }
    public long seed()              { return seed; }

    @Nullable public BlockPos getAnchor(String name) { return anchors.get(name); }
    public Map<String, BlockPos> allAnchors() { return Collections.unmodifiableMap(anchors); }

    @Nullable public Double axisRad() { return axisRad; }
    public double axisTolerance()     { return axisTolerance; }

    @Nullable public ZoneSpec getZone(BuildingType type) { return zoneAssignments.get(type); }
    public Map<BuildingType, ZoneSpec> allZones() {
        return Collections.unmodifiableMap(zoneAssignments);
    }

    public List<AdjacencyReq> getAdjacencyReqs(BuildingType type) {
        return adjacencyReqs.getOrDefault(type, List.of());
    }

    @Nullable public Float densityPer100m2() { return densityPer100m2; }
    public boolean forceWalled()             { return forceWalled; }

    // =========================================================================
    // Accessors — write
    // =========================================================================

    public void setAnchor(String name, BlockPos pos) { anchors.put(name, pos); }

    public void setAxis(double radians, double toleranceRadians) {
        this.axisRad       = radians;
        this.axisTolerance = toleranceRadians;
    }

    public void assignZone(BuildingType type, ZoneSpec zone) {
        zoneAssignments.put(type, zone);
    }

    public void addAdjacencyReq(BuildingType type, AdjacencyReq req) {
        adjacencyReqs.computeIfAbsent(type, k -> new ArrayList<>()).add(req);
    }

    public void setDensityPer100m2(float value) { this.densityPer100m2 = value; }
    public void setForceWalled(boolean walled)  { this.forceWalled = walled; }

    // =========================================================================
    // Supporting records
    // =========================================================================

    /**
     * Specifies where a building type should be placed within the village.
     *
     * @param ring   which ring (INNER, MIDDLE, OUTER)
     * @param sector optional angular restriction (degrees); null = any angle
     */
    public record ZoneSpec(Ring ring, @Nullable AngularSector sector) {}

    public enum Ring { INNER, MIDDLE, OUTER }

    /**
     * An angular restriction — buildings of this type may only be placed
     * in atlas-relative angles between {@code startDeg} and {@code endDeg}.
     * Degrees are measured counterclockwise from east (standard math convention).
     */
    public record AngularSector(int startDeg, int endDeg) {
        public boolean contains(double angleDeg) {
            double a = ((angleDeg % 360) + 360) % 360;
            double s = ((startDeg % 360) + 360) % 360;
            double e = ((endDeg   % 360) + 360) % 360;
            return s <= e ? (a >= s && a <= e) : (a >= s || a <= e);
        }
    }

    /**
     * An adjacency requirement — the building must be within {@code maxDist}
     * blocks of the named feature.
     *
     * Supported features:
     * <ul>
     *   <li>{@code river} — freshwater adjacency (river biome or swamp)</li>
     *   <li>{@code coast} — ocean adjacency</li>
     *   <li>{@code water} — either river or coast</li>
     *   <li>{@code forest} — dense tree coverage</li>
     * </ul>
     */
    public record AdjacencyReq(String feature, int maxDist, boolean required) {}
}