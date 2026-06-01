package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadCorridors;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadSegment;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.Skeleton;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 Layer 5 — final consistency audit.
 *
 * <p>Layer 3 + 4's reservation logic should already prevent
 * overlaps, but Layer 5 runs an explicit pass before any blocks
 * are placed: cheap insurance against subtle bugs, and the user-
 * facing "spawn would have produced X conflicts" diagnostic.
 *
 * <p>Audits two relations:
 * <ul>
 *   <li>Building footprint AABB vs another building's footprint
 *       AABB.</li>
 *   <li>Building footprint AABB vs a road segment's corridor
 *       (segment polyline expanded by {@code segment.width()/2})
 *       — uses the shared {@link RoadCorridors#intersects}
 *       utility so this audit and the candidate-rejection check
 *       in {@code PhasedPlanner.findBestCandidate} share one
 *       implementation.</li>
 * </ul>
 *
 * <p>Layout Rework Stage 3 fix-up #2 (roads-last): only a
 * building×building footprint overlap is {@code fatal} (the spawner
 * aborts). Building×road-corridor and parcel×road-corridor findings are
 * diagnostic-only — under roads-last the router routes roads TO REACH
 * building fronts, so a corridor touching a building's edge is expected;
 * these checks test against an inset footprint so only a deep interior
 * crossing is recorded, and never abort.
 */
public final class OverlapAuditor {

    private OverlapAuditor() {}

    public static OverlapReport audit(List<PlacedBuilding> placed, RoadNetwork roads) {
        List<Conflict> conflicts = new ArrayList<>();

        // Building × Building.
        for (int i = 0; i < placed.size(); i++) {
            PlacedBuilding a = placed.get(i);
            int[] aabbA = footprintAabb(a);
            for (int j = i + 1; j < placed.size(); j++) {
                PlacedBuilding b = placed.get(j);
                int[] aabbB = footprintAabb(b);
                if (aabbsOverlap(aabbA, aabbB)) {
                    conflicts.add(new Conflict(
                            "building footprints intersect",
                            new BlockPos(
                                    (a.centre().getX() + b.centre().getX()) / 2,
                                    a.centre().getY(),
                                    (a.centre().getZ() + b.centre().getZ()) / 2),
                            label(a), label(b)));
                }
            }
        }

        // Building × Road corridor. Layout Rework Stage 3 fix-up #2 —
        // roads-last: the router generates roads TO REACH building fronts,
        // so a corridor grazing a building's edge/front is expected by
        // construction, not a defect. Test against an INSET footprint
        // (shrunk by corridorHalf + 1) so only a road crossing DEEP into
        // the interior registers; such a conflict is recorded for
        // diagnostics but is NON-FATAL (the router already avoids footprint
        // interiors — this is defense-in-depth, not a gate).
        Skeleton skeleton = roads.skeleton();
        for (RoadSegment seg : skeleton.allSegments()) {
            int corridorHalf = (seg.width() + 1) / 2;
            int inset = corridorHalf + 1;
            for (PlacedBuilding b : placed) {
                int[] inner = insetAabb(footprintAabb(b), inset);
                if (inner == null) continue;   // too small to have a reachable interior
                if (RoadCorridors.intersects(seg.start(), seg.end(), corridorHalf,
                        inner[0], inner[1], inner[2], inner[3])) {
                    conflicts.add(new Conflict(
                            "road corridor crosses building interior",
                            b.centre(),
                            label(b),
                            seg instanceof tterrag1112.life_in_the_village.Village
                                    .Planning.V2.Layer4.SpineSegment ? "spine" : "cross-street"));
                }
            }
        }

        // Stage 2a — complex parcel × another building's footprint, and
        // parcel × road corridor. Insurance only: the planner already
        // validates parcels against reservations + corridors.
        for (PlacedBuilding owner : placed) {
            int[] parcel = parcelAabb(owner);
            if (parcel == null) continue;
            for (PlacedBuilding other : placed) {
                if (other == owner) continue;
                if (aabbsOverlap(parcel, footprintAabb(other))) {
                    conflicts.add(new Conflict(
                            "complex parcel overlaps building footprint",
                            owner.centre(), label(owner) + " parcel", label(other)));
                }
            }
            for (RoadSegment seg : skeleton.allSegments()) {
                int corridorHalf = (seg.width() + 1) / 2;
                // Same roads-last treatment: a road reaching a complex is
                // expected; only a deep interior crossing is recorded, and
                // it is non-fatal.
                int[] innerParcel = insetAabb(parcel, corridorHalf + 1);
                if (innerParcel == null) continue;
                if (RoadCorridors.intersects(seg.start(), seg.end(), corridorHalf,
                        innerParcel[0], innerParcel[1], innerParcel[2], innerParcel[3])) {
                    conflicts.add(new Conflict(
                            "road corridor crosses complex parcel interior",
                            owner.centre(), label(owner) + " parcel",
                            seg instanceof tterrag1112.life_in_the_village.Village
                                    .Planning.V2.Layer4.SpineSegment ? "spine" : "cross-street"));
                }
            }
        }

        // Layout Rework Stage 3 fix-up #2 — fatal is restricted to
        // building×building overlaps (the real, can-never-happen invariant).
        // Under roads-last, road corridors legitimately reach building
        // fronts, so building×corridor / parcel×corridor conflicts are
        // diagnostic-only and never abort the spawn (a TOWN_HALL-corridor
        // touch is expected, not fatal).
        boolean fatal = conflicts.stream().anyMatch(c ->
                c.description().equals("building footprints intersect"));
        return new OverlapReport(conflicts, fatal);
    }

    /** Shrinks an {@code [minX, minZ, maxX, maxZ]} AABB inward by
     *  {@code inset} on every side. Returns {@code null} when the box is
     *  too small to have any interior left (so an edge/front touch — the
     *  expected roads-last case — produces no conflict; only a genuine
     *  deep crossing of a building large enough to have an interior does). */
    private static int[] insetAabb(int[] a, int inset) {
        int minX = a[0] + inset, minZ = a[1] + inset;
        int maxX = a[2] - inset, maxZ = a[3] - inset;
        if (minX > maxX || minZ > maxZ) return null;
        return new int[]{minX, minZ, maxX, maxZ};
    }

    private static String label(PlacedBuilding b) {
        return b.type().name() + "@(" + b.centre().getX() + "," + b.centre().getZ() + ")";
    }

    /** Returns {@code [minX, minZ, maxX, maxZ]} for the rotated footprint. */
    private static int[] footprintAabb(PlacedBuilding b) {
        Footprint fp = b.footprint();
        boolean swap = b.rotation() == Rotation.CLOCKWISE_90
                || b.rotation() == Rotation.COUNTERCLOCKWISE_90;
        int rotW = swap ? fp.length() : fp.width();
        int rotL = swap ? fp.width() : fp.length();
        int halfW = rotW / 2;
        int halfL = rotL / 2;
        BlockPos c = b.centre();
        return new int[]{c.getX() - halfW, c.getZ() - halfL,
                c.getX() + halfW, c.getZ() + halfL};
    }

    private static boolean aabbsOverlap(int[] a, int[] b) {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
    }

    /** Stage 2a — {@code [minX, minZ, maxX, maxZ]} of the building's
     *  reserved complex parcel budget box, or {@code null} if none. */
    private static int[] parcelAabb(PlacedBuilding b) {
        if (b.parcel() == null) return null;
        var bb = b.parcel().budgetBounds();
        return new int[]{bb.minX(), bb.minZ(), bb.maxX(), bb.maxZ()};
    }

    public record OverlapReport(List<Conflict> conflicts, boolean fatal) {}

    /** A single overlap finding. {@code aDesc}/{@code bDesc} carry
     *  human-readable descriptors (e.g. {@code "TOWN_HALL@(x,z)"} or
     *  {@code "spine"}); the audit doesn't carry typed building/segment
     *  references because both sides may be either. */
    public record Conflict(String description, BlockPos pos,
                           String aDesc, String bDesc) {}
}
