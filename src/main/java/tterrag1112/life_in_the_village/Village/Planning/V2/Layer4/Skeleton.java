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
        List<SpineSegment> out = new ArrayList<>(path.segments().size());
        // Each road primitive in the path becomes a SpineSegment whose
        // endpoints are the primitive's from/to. Primitives in V2
        // expose from()/to() via their record components or accessors;
        // use a small dispatch since RoadPrimitive is a sealed
        // interface in V1.
        for (var prim : path.segments()) {
            BlockPos from, to;
            if (prim instanceof tterrag1112.life_in_the_village.Village.Planning
                    .Primitives.RoadPrimitive.StraightRoad sr) {
                from = sr.from(); to = sr.to();
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.CurvedRoad cr) {
                from = cr.from(); to = cr.to();
            } else if (prim instanceof tterrag1112.life_in_the_village.Village
                    .Planning.Primitives.RoadPrimitive.Arc arc) {
                // Arc has centre + radius + startAngle + arcSpan. Approximate
                // start/end from the angles. Tracking exact arc endpoints
                // here would require duplicating the primitive's centerline
                // math; the approximation is good for corridor checks.
                int sx = arc.centre().getX()
                        + (int) Math.round(Math.cos(arc.startAngle()) * arc.radius());
                int sz = arc.centre().getZ()
                        + (int) Math.round(Math.sin(arc.startAngle()) * arc.radius());
                int ex = arc.centre().getX()
                        + (int) Math.round(Math.cos(arc.startAngle() + arc.arcSpan())
                        * arc.radius());
                int ez = arc.centre().getZ()
                        + (int) Math.round(Math.sin(arc.startAngle() + arc.arcSpan())
                        * arc.radius());
                from = new BlockPos(sx, arc.centre().getY(), sz);
                to = new BlockPos(ex, arc.centre().getY(), ez);
            } else {
                // Other primitive types not produced by SpinePathPlanner
                // in V1; defensive fallback uses path bounds.
                from = path.start();
                to = path.end();
            }
            out.add(new SpineSegment(from, to, width));
        }
        return Collections.unmodifiableList(out);
    }
}
