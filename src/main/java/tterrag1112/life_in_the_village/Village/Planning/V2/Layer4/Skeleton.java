package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.CardinalAxis;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkEdge;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkPlanner;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V2 road skeleton — chord-decomposed view of the planned road
 * {@link NetworkSpec network} plus any cross-streets the placer
 * adds in Phase 4a. Frozen at Phase 6 when the orchestrator wraps
 * the snapshot in {@link RoadNetwork}.
 *
 * <h3>A6 teardown</h3>
 *
 * Skeleton is constructed from the {@link NetworkSpec} directly.
 * The chord-decomposition logic converts each {@link NetworkEdge}
 * into one or more {@link SpineSegment}s. {@code SpinePath} and
 * {@code CrossStreet} have been deleted; {@link #primarySegments()}
 * is the canonical segment list, and {@link #allSegments()} is
 * now identical to it.
 */
public final class Skeleton {

    private final NetworkSpec network;
    private final CardinalAxis primaryAxis;
    private final BlockPos fallbackAnchor;
    private final int spineWidth;

    /** Chord-decomposed view of every network edge. Recomputed
     *  here at construction; cross-streets added separately via
     *  the placer adds. */
    private final List<SpineSegment> primarySegments;
    private final List<Junction> junctions = new ArrayList<>();

    /** Constructs skeleton from the network spec by chord-decomposing
     *  each {@link tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.NetworkEdge} into {@link SpineSegment}s. */
    public Skeleton(NetworkSpec network, CardinalAxis primaryAxis,
                    BlockPos fallbackAnchor, int spineWidth) {
        this.network = network;
        this.primaryAxis = primaryAxis;
        this.fallbackAnchor = fallbackAnchor;
        this.spineWidth = spineWidth;
        this.primarySegments = computePrimarySegments(network, spineWidth);
    }

    /** The planner's source of truth — the network graph. */
    public NetworkSpec network() { return network; }

    /** Convenience accessor for edge iteration. Equivalent to
     *  {@code network().edges()}. */
    public List<NetworkEdge> edges() {
        return network != null ? network.edges() : List.of();
    }

    /** Track E1 prompt 3 fix-up — chord-decomposed view of every
     *  network edge. Was {@code spineSegments()} pre-fix-up.
     *  Stable rename — the segments aren't a spine-only subset;
     *  they are the network. */
    public List<SpineSegment> primarySegments() { return primarySegments; }

    public List<Junction> junctions() { return junctions; }

    public void addJunction(Junction j) { junctions.add(j); }

    /** All road segments — the chord-decomposed primary network edges.
     *  Used for frontage scoring in {@link PhasedPlanner#findBestCandidate}. */
    public List<RoadSegment> allSegments() {
        return primarySegments;
    }

    // =========================================================================
    // Network-edge chord decomposition
    // =========================================================================

    /** Iterate the network's edges and chord-decompose each
     *  primitive into one or more {@link SpineSegment}s. The
     *  per-primitive chord counts mirror the pre-fix-up logic
     *  exactly (Ring=16, CurvedRoad=6, Arc=8, Spur=1, …) so
     *  placement geometry is bit-for-bit identical to the
     *  spine-derived path the planner used previously. */
    private static List<SpineSegment> computePrimarySegments(NetworkSpec network,
                                                             int width) {
        if (network == null || network.edges().isEmpty()) return List.of();
        List<SpineSegment> out = new ArrayList<>(network.edges().size() * 2);
        for (NetworkEdge edge : network.edges()) {
            addPrimitiveAsSegments(out, edge.primitive(), width);
        }
        return Collections.unmodifiableList(out);
    }

    private static void addPrimitiveAsSegments(List<SpineSegment> out,
                                               RoadPrimitive prim, int width) {
        if (prim instanceof RoadPrimitive.StraightRoad sr) {
            out.add(new SpineSegment(sr.from(), sr.to(), width));
        } else if (prim instanceof RoadPrimitive.CurvedRoad cr) {
            addBezierChords(out, cr.from(), cr.to(), cr.curvature(), 6, width);
        } else if (prim instanceof RoadPrimitive.Arc arc) {
            addArcChords(out, arc.centre(), arc.radius(), arc.startAngle(),
                    arc.arcSpan(), 8, width);
        } else if (prim instanceof RoadPrimitive.Ring ring) {
            addArcChords(out, ring.centre(), ring.radius(), 0,
                    2 * Math.PI, 16, width);
        } else if (prim instanceof RoadPrimitive.Spur s) {
            BlockPos start = nearestOnCenterline(s.parentCenterline(),
                    s.branchPointHint());
            int endX = start.getX()
                    + (int) Math.round(Math.cos(s.directionRad()) * s.length());
            int endZ = start.getZ()
                    + (int) Math.round(Math.sin(s.directionRad()) * s.length());
            out.add(new SpineSegment(start,
                    new BlockPos(endX, start.getY(), endZ), width));
        } else if (prim instanceof RoadPrimitive.ArmApproach aa) {
            out.add(new SpineSegment(aa.dockingAnchor(), aa.armEndpoint(), width));
        } else if (prim instanceof RoadPrimitive.Bridge br) {
            out.add(new SpineSegment(br.from(), br.to(), width));
        } else if (prim instanceof RoadPrimitive.Stairway st) {
            out.add(new SpineSegment(st.from(), st.to(), width));
        } else if (prim instanceof RoadPrimitive.SmoothedPath sp) {
            var wp = sp.waypoints();
            for (int i = 0; i + 1 < wp.size(); i++) {
                out.add(new SpineSegment(wp.get(i), wp.get(i + 1), width));
            }
        }
        // Defensive: future primitives without an entry here contribute
        // no segments; the placer simply ignores their frontage. Better
        // than a degenerate fallback that produces a misleading chord.
    }

    private static void addArcChords(List<SpineSegment> out, BlockPos centre,
                                     int radius, double startAngle, double arcSpan,
                                     int chords, int width) {
        BlockPos prev = null;
        for (int i = 0; i <= chords; i++) {
            double t = i / (double) chords;
            double a = startAngle + arcSpan * t;
            BlockPos pt = new BlockPos(
                    centre.getX() + (int) Math.round(Math.cos(a) * radius),
                    centre.getY(),
                    centre.getZ() + (int) Math.round(Math.sin(a) * radius));
            if (prev != null) out.add(new SpineSegment(prev, pt, width));
            prev = pt;
        }
    }

    private static void addBezierChords(List<SpineSegment> out, BlockPos a,
                                        BlockPos b, double curvature,
                                        int chords, int width) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double chord = Math.sqrt(dx * dx + dz * dz);
        double pX = chord < 1e-9 ? 0 : -dz / chord;
        double pZ = chord < 1e-9 ? 0 : dx / chord;
        double bow = curvature * chord;
        BlockPos prev = null;
        for (int i = 0; i <= chords; i++) {
            double t = i / (double) chords;
            double s = 4 * t * (1 - t);
            int x = (int) Math.round(a.getX() + dx * t + pX * bow * s);
            int z = (int) Math.round(a.getZ() + dz * t + pZ * bow * s);
            BlockPos pt = new BlockPos(x, a.getY(), z);
            if (prev != null) out.add(new SpineSegment(prev, pt, width));
            prev = pt;
        }
    }

    private static BlockPos nearestOnCenterline(List<BlockPos> line, BlockPos hint) {
        if (line.isEmpty()) return hint;
        BlockPos best = line.get(0);
        double bestD = best.distSqr(hint);
        for (BlockPos p : line) {
            double d = p.distSqr(hint);
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }
}
