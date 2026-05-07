package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V2 road skeleton. Owns the spine, all cross streets, and the
 * junction list. Mutable through Phases 2-5 (cross streets added
 * in Phase 4, trims + junction marking in Phase 5); frozen at
 * Phase 6 when the orchestrator wraps the snapshot in
 * {@link RoadNetwork}.
 *
 * <p>Mutability lives behind explicit add/replace methods so the
 * caller can't accidentally swap the spine reference mid-phase.
 */
public final class Skeleton {

    private Spine spine;
    private final List<CrossStreet> crossStreets = new ArrayList<>();
    private final List<Junction> junctions = new ArrayList<>();

    public Skeleton(Spine spine) {
        this.spine = spine;
    }

    public Spine spine() { return spine; }
    public List<CrossStreet> crossStreets() { return crossStreets; }
    public List<Junction> junctions() { return junctions; }

    /** Replace the spine record (Phase 5 trim). Cross streets'
     *  {@code spineJunction} field is unchanged — junction is a
     *  point, not a fraction along the spine. */
    public void replaceSpine(Spine s) { this.spine = s; }

    public void addCrossStreet(CrossStreet cs) { crossStreets.add(cs); }
    public void removeCrossStreet(CrossStreet cs) { crossStreets.remove(cs); }
    public void addJunction(Junction j) { junctions.add(j); }

    /** All segments in order: spine first, then cross streets. */
    public List<RoadSegment> allSegments() {
        List<RoadSegment> out = new ArrayList<>(crossStreets.size() + 1);
        out.add(spine);
        out.addAll(crossStreets);
        return Collections.unmodifiableList(out);
    }
}
