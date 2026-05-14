package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SpinePath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V2 road skeleton. Owns the spine path, all cross streets, and the
 * junction list. Mutable through Phases 4a-5 (cross streets added
 * in 4a, trims + junction marking in 5); frozen at Phase 6 when the
 * orchestrator wraps the snapshot in {@link RoadNetwork}.
 *
 * <p>The spine is now a multi-segment {@link SpinePath} (list of
 * road primitives along {@code primaryAxis}). For corridor /
 * frontage checks, callers iterate {@link #allSegments()} which
 * yields each piece of the spine path as a {@link SpineSegment}
 * plus each {@link CrossStreet}.
 */
public final class Skeleton {

    private final SpinePath spinePath;
    /** Cached one-{@link SpineSegment}-per-primitive view of the spine
     *  for corridor-vs-AABB iteration. Recomputed if the spine path
     *  is replaced (Phase 5 trim). */
    private List<SpineSegment> spineSegments;
    private final List<CrossStreet> crossStreets = new ArrayList<>();
    private final List<Junction> junctions = new ArrayList<>();

    public Skeleton(SpinePath spinePath, int spineWidth) {
        this.spinePath = spinePath;
        this.spineSegments = computeSpineSegments(spinePath, spineWidth);
    }

    public SpinePath spinePath() { return spinePath; }
    public List<SpineSegment> spineSegments() { return spineSegments; }
    public List<CrossStreet> crossStreets() { return crossStreets; }
    public List<Junction> junctions() { return junctions; }

    /** Spine path's overall start (path's "near" endpoint). */
    public BlockPos spineStart() { return spinePath.start(); }

    /** Spine path's overall end (path's "far" endpoint). */
    public BlockPos spineEnd() { return spinePath.end(); }

    public void addCrossStreet(CrossStreet cs) { crossStreets.add(cs); }
    public void removeCrossStreet(CrossStreet cs) { crossStreets.remove(cs); }
    public void addJunction(Junction j) { junctions.add(j); }

    /** All road segments in order: each spine piece, then cross
     *  streets. Used for corridor checks and frontage attachment. */
    public List<RoadSegment> allSegments() {
        List<RoadSegment> out = new ArrayList<>(spineSegments.size()
                + crossStreets.size());
        out.addAll(spineSegments);
        out.addAll(crossStreets);
        return Collections.unmodifiableList(out);
    }

    private static List<SpineSegment> computeSpineSegments(SpinePath path, int width) {
        List<SpineSegment> out = new ArrayList<>(path.segments().size() * 2);
        // Each road primitive becomes one or more SpineSegments
        // (straight chord-approximations). Curvy primitives — Ring,
        // Arc, CurvedRoad — chord-decompose into multiple segments
        // so frontage scoring around their actual curve works.
        for (var prim : path.segments()) {
            if (prim instanceof tterrag1112.life_in_the_village.Village.Planning
                    .Primitives.RoadPrimitive.StraightRoad sr) {
                out.add(new SpineSegment(sr.from(), sr.to(), width));
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.CurvedRoad cr) {
                addBezierChords(out, cr.from(), cr.to(), cr.curvature(), 6, width);
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.Arc arc) {
                addArcChords(out, arc.centre(), arc.radius(), arc.startAngle(),
                        arc.arcSpan(), 8, width);
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.Ring ring) {
                addArcChords(out, ring.centre(), ring.radius(), 0,
                        2 * Math.PI, 16, width);
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.Spur s) {
                BlockPos start = nearestOnCenterline(s.parentCenterline(),
                        s.branchPointHint());
                int endX = start.getX()
                        + (int) Math.round(Math.cos(s.directionRad()) * s.length());
                int endZ = start.getZ()
                        + (int) Math.round(Math.sin(s.directionRad()) * s.length());
                out.add(new SpineSegment(start,
                        new BlockPos(endX, start.getY(), endZ), width));
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.ArmApproach aa) {
                out.add(new SpineSegment(aa.dockingAnchor(), aa.armEndpoint(), width));
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.Bridge br) {
                out.add(new SpineSegment(br.from(), br.to(), width));
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.Stairway st) {
                out.add(new SpineSegment(st.from(), st.to(), width));
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.SmoothedPath sp) {
                var wp = sp.waypoints();
                for (int i = 0; i + 1 < wp.size(); i++) {
                    out.add(new SpineSegment(wp.get(i), wp.get(i + 1), width));
                }
            } else {
                // Defensive fallback for any future primitive types
                // — chord between the path's bounds.
                out.add(new SpineSegment(path.start(), path.end(), width));
            }
        }
        return Collections.unmodifiableList(out);
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
