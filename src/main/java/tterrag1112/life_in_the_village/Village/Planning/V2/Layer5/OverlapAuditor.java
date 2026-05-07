package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.Footprint;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
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
 *       (segment polyline expanded by {@code segment.width()/2}).</li>
 * </ul>
 *
 * <p>If any conflict involves a {@code required:true} type
 * (TOWN_HALL in V1) the report is {@code fatal} and the spawner
 * aborts.
 */
public final class OverlapAuditor {

    /** Road-corridor sample step (1-block resolution). */
    private static final int CORRIDOR_SAMPLE_STEP = 1;

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

        // Building × Road corridor.
        Skeleton skeleton = roads.skeleton();
        for (RoadSegment seg : skeleton.allSegments()) {
            int corridorHalf = (seg.width() + 1) / 2;
            for (PlacedBuilding b : placed) {
                if (segmentCrossesAabb(seg.start(), seg.end(), corridorHalf,
                        footprintAabb(b))) {
                    conflicts.add(new Conflict(
                            "building footprint inside road corridor",
                            b.centre(),
                            label(b),
                            seg instanceof tterrag1112.life_in_the_village.Village
                                    .Planning.V2.Layer4.Spine ? "spine" : "cross-street"));
                }
            }
        }

        boolean fatal = conflicts.stream().anyMatch(c ->
                c.aDesc().contains("TOWN_HALL") || c.bDesc().contains("TOWN_HALL"));
        return new OverlapReport(conflicts, fatal);
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

    /** Walk the segment at {@link #CORRIDOR_SAMPLE_STEP}-block intervals;
     *  if any sample (expanded by {@code corridorHalf} perpendicular to
     *  the segment) lands inside {@code aabb}, the corridor crosses the
     *  building. Approximate but sufficient for V1's short segments. */
    private static boolean segmentCrossesAabb(BlockPos a, BlockPos b,
                                              int corridorHalf, int[] aabb) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-9) return aabbContains(aabb, a.getX(), a.getZ());
        int steps = Math.max(1, (int) Math.round(len / CORRIDOR_SAMPLE_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            int[] expanded = {aabb[0] - corridorHalf, aabb[1] - corridorHalf,
                    aabb[2] + corridorHalf, aabb[3] + corridorHalf};
            if (aabbContains(expanded, x, z)) return true;
        }
        return false;
    }

    private static boolean aabbContains(int[] aabb, int x, int z) {
        return x >= aabb[0] && x <= aabb[2] && z >= aabb[1] && z <= aabb[3];
    }

    public record OverlapReport(List<Conflict> conflicts, boolean fatal) {}

    /** A single overlap finding. {@code aDesc}/{@code bDesc} carry
     *  human-readable descriptors (e.g. {@code "TOWN_HALL@(x,z)"} or
     *  {@code "spine"}); the audit doesn't carry typed building/segment
     *  references because both sides may be either. */
    public record Conflict(String description, BlockPos pos,
                           String aDesc, String bDesc) {}
}
