package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkEdge;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.DensityProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * City-morphology step 1 — per-edge road FORMALITY for VILLAGE edges, keyed
 * to the density gradient (design doc {@code 11-CITY-MORPHOLOGY-DESIGN.md}
 * §4): regular, straight, stone streets in the dense core; the current
 * organic look kept at the outskirts and beyond.
 *
 * <p><b>Step 2a — reads the {@link DensityProfile}.</b> The v1 fixed
 * cost-distance thresholds are gone; formality is now the profile's zone at
 * the edge midpoint — FORMAL = CORE, MIXED = MIDTOWN, ORGANIC =
 * OUTSKIRTS/RURAL (design doc §4 keyed to §1's area-budget gradient). The
 * underlying field is still the terrain-warped {@code Cell#distToAnchor}
 * Dijkstra, so bands follow valleys instead of drawing circles. Tier
 * semantics:
 * <ul>
 *   <li>{@link #FORMAL} — straight segments between waypoints (the routed
 *       cell-path micro-wiggle is simplified away), district approaches
 *       snapped axis-aligned, and at paint time a crisp full-width
 *       stone-brick-dominant surface.</li>
 *   <li>{@link #MIXED} — light curvature, current mixed paving: geometry
 *       and paint are byte-identical to today.</li>
 *   <li>{@link #ORGANIC} — byte-identical to today (also the fallback for
 *       null/missing field data, so non-village callers never change).</li>
 * </ul>
 *
 * <p><b>Planner over realiser.</b> {@link #applyGeometry} rewrites the
 * routed {@link NetworkSpec} ONCE, immediately after the router emits it
 * ({@code PhasedPlanner}), so every downstream consumer — skeleton segments,
 * vegetation clearing, building orientation, the nav-graph commit
 * ({@code InternalRoadCommitter}, NPCs walk the same centerline that gets
 * painted) and the realizer — sees one consistent geometry. The realizer
 * re-samples only for the MATERIAL/crisp decision, which is why the sample
 * point is the midpoint of the edge ENDPOINTS (invariant under the rewrite),
 * not the middle waypoint.
 */
public enum RoadFormality implements StringRepresentable {
    FORMAL, MIXED, ORGANIC;

    /** Persistence codec — {@code PlazaRegion} stores the plan-time
     *  formality of its plaza so the decorator's paver matches the
     *  surrounding streets without rebuilding the density profile.
     *  Codec stability: append-only. */
    public static final Codec<RoadFormality> CODEC =
            StringRepresentable.fromEnum(RoadFormality::values);

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadFormality.class);

    /** RDP tolerance (blocks) for straightening FORMAL centerlines. Small on
     *  purpose: the routed cell path's micro-wiggle is ~1 cell (2 blocks),
     *  and the painted strip is ≥ 5 wide, so a ≤ 1.5-block deviation always
     *  stays inside the corridor the original path already claimed. */
    private static final double STRAIGHTEN_EPSILON = 1.5;
    /** District-approach snap — skip when the final segment's lateral offset
     *  is already ≤ this (near-aligned; nothing to fix). */
    private static final int SNAP_ALIGNED = 1;
    /** District-approach snap — skip when the lateral offset exceeds this
     *  (too diagonal; a bounded adjustment can't honestly square it). */
    private static final int SNAP_MAX_DEVIATION = 6;
    /** Router district-node id prefix ({@code BlockServingRouter.buildTerminals}). */
    private static final String DISTRICT_ID_PREFIX = "district:";

    /** Formality at a world position: the density profile's zone there.
     *  ORGANIC (= today's look) for a null profile — the profile itself
     *  answers RURAL for out-of-bounds / unreached positions. */
    public static RoadFormality at(DensityProfile profile, int worldX, int worldZ) {
        if (profile == null) return ORGANIC;
        return switch (profile.zoneAt(worldX, worldZ)) {
            case CORE -> FORMAL;
            case MIDTOWN -> MIXED;
            case OUTSKIRTS, RURAL -> ORGANIC;
        };
    }

    /** Formality at the edge midpoint — the midpoint of the ENDPOINTS, not
     *  the middle waypoint, so the classification is invariant under the
     *  FORMAL geometry rewrite (which never moves endpoints) and the
     *  realizer's paint-time re-sample always agrees with the planner. */
    public static RoadFormality atMid(DensityProfile profile, List<BlockPos> waypoints) {
        if (profile == null || waypoints == null || waypoints.size() < 2) return ORGANIC;
        BlockPos a = waypoints.get(0);
        BlockPos b = waypoints.get(waypoints.size() - 1);
        return at(profile, (a.getX() + b.getX()) / 2, (a.getZ() + b.getZ()) / 2);
    }

    /**
     * FORMAL-zone geometry rewrite over the routed network: every FORMAL
     * {@link RoadPrimitive.SmoothedPath} edge is simplified to straight
     * segments (RDP, {@link #STRAIGHTEN_EPSILON}) and, where it terminates
     * on a district connection node, its final approach segment is snapped
     * into an axis-aligned L so it meets the district's (axis-aligned BSP)
     * street grid at a right angle. MIXED/ORGANIC edges are passed through
     * UNTOUCHED (same object). Endpoints never move, so gateway position
     * matching and node bindings are unaffected. One summary INFO per
     * village (plan-time; no per-tick logging).
     */
    public static NetworkSpec applyGeometry(NetworkSpec spec, DensityProfile profile) {
        if (spec == null || profile == null || spec.edges().isEmpty()) return spec;
        List<NetworkEdge> out = new ArrayList<>(spec.edges().size());
        int formal = 0, snaps = 0, mixed = 0, organic = 0;
        for (NetworkEdge e : spec.edges()) {
            if (!(e.primitive() instanceof RoadPrimitive.SmoothedPath sp)
                    || sp.waypoints().size() < 2) {
                out.add(e);
                continue;
            }
            RoadFormality f = atMid(profile, sp.waypoints());
            if (f != FORMAL) {
                if (f == MIXED) mixed++; else organic++;
                out.add(e);              // MIXED/ORGANIC: geometry untouched
                continue;
            }
            formal++;
            List<BlockPos> wp = simplify(sp.waypoints(), STRAIGHTEN_EPSILON);
            if (isDistrictNode(e.toNodeId())) {
                List<BlockPos> s = snapApproach(wp);
                if (s != null) { wp = s; snaps++; }
            }
            if (isDistrictNode(e.fromNodeId())) {
                List<BlockPos> rev = new ArrayList<>(wp);
                Collections.reverse(rev);
                List<BlockPos> s = snapApproach(rev);
                if (s != null) {
                    Collections.reverse(s);
                    wp = s;
                    snaps++;
                }
            }
            out.add(new NetworkEdge(e.id(), e.fromNodeId(), e.toNodeId(),
                    new RoadPrimitive.SmoothedPath(wp, sp.tension(),
                            sp.driftAmplitude(), sp.tier(), sp.seed()),
                    e.width()));
        }
        LOGGER.info("road formality (density profile): {} formal"
                + " edge(s) straightened ({} approach snap(s)), {} mixed,"
                + " {} organic", formal, snaps, mixed, organic);
        return new NetworkSpec(spec.topology(), spec.nodes(), out,
                spec.primaryBindings());
    }

    @Override
    public String getSerializedName() { return name(); }

    private static boolean isDistrictNode(String nodeId) {
        return nodeId != null && nodeId.startsWith(DISTRICT_ID_PREFIX);
    }

    /** Ramer–Douglas–Peucker on the XZ plane; endpoints always kept; the
     *  kept waypoints retain their original Y (the placer re-snaps heights
     *  when it densifies). */
    private static List<BlockPos> simplify(List<BlockPos> pts, double eps) {
        if (pts.size() <= 2) return pts;
        boolean[] keep = new boolean[pts.size()];
        keep[0] = true;
        keep[pts.size() - 1] = true;
        rdp(pts, 0, pts.size() - 1, eps, keep);
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i]) out.add(pts.get(i));
        }
        return out;
    }

    private static void rdp(List<BlockPos> pts, int a, int b, double eps,
                            boolean[] keep) {
        if (b - a < 2) return;
        double ax = pts.get(a).getX(), az = pts.get(a).getZ();
        double dx = pts.get(b).getX() - ax, dz = pts.get(b).getZ() - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        int worst = -1;
        double worstD = eps;
        for (int i = a + 1; i < b; i++) {
            double d = len < 1e-9
                    ? Math.hypot(pts.get(i).getX() - ax, pts.get(i).getZ() - az)
                    : Math.abs((pts.get(i).getX() - ax) * dz
                            - (pts.get(i).getZ() - az) * dx) / len;
            if (d > worstD) {
                worstD = d;
                worst = i;
            }
        }
        if (worst < 0) return;
        keep[worst] = true;
        rdp(pts, a, worst, eps, keep);
        rdp(pts, worst, b, eps, keep);
    }

    /** Replaces the final segment into the LAST waypoint (the district node)
     *  with an axis-aligned L whose final leg runs along the segment's
     *  dominant axis — a right-angle meeting with the district's
     *  axis-aligned streets. Bounded: returns null (no change) when the
     *  segment is already near-aligned ({@link #SNAP_ALIGNED}) or too
     *  diagonal ({@link #SNAP_MAX_DEVIATION}). The endpoint never moves. */
    private static List<BlockPos> snapApproach(List<BlockPos> wp) {
        if (wp.size() < 2) return null;
        BlockPos end = wp.get(wp.size() - 1);
        BlockPos prev = wp.get(wp.size() - 2);
        int dx = end.getX() - prev.getX();
        int dz = end.getZ() - prev.getZ();
        int lateral = Math.min(Math.abs(dx), Math.abs(dz));
        if (lateral <= SNAP_ALIGNED || lateral > SNAP_MAX_DEVIATION) return null;
        BlockPos corner = Math.abs(dx) >= Math.abs(dz)
                ? new BlockPos(prev.getX(), prev.getY(), end.getZ())  // final leg along X
                : new BlockPos(end.getX(), prev.getY(), prev.getZ()); // final leg along Z
        List<BlockPos> out = new ArrayList<>(wp.subList(0, wp.size() - 1));
        out.add(corner);
        out.add(end);
        return out;
    }
}
