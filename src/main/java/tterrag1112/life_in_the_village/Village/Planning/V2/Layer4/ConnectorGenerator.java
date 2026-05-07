package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * V2 Layer 4 connectors. For each placed building, attaches a
 * straight spur from the nearest existing road point to the
 * nearest point on the building's footprint edge.
 *
 * <p>Buildings are processed in order of distance-to-spine
 * (ascending), so civic buildings near the anchor connect first.
 * Outer buildings can then attach to those earlier connectors if
 * one is nearer than the spine itself — the resulting graph is a
 * greedy tree, no MST.
 *
 * <p>Skip rules:
 * <ul>
 *   <li>If the building footprint is already within
 *       {@link #ON_ROAD_DISTANCE} blocks of an existing road,
 *       no connector needed.</li>
 *   <li>If the connector segment crosses any other building's
 *       footprint, the connector is dropped and the target
 *       becomes road-isolated. Recorded as a warning; visible in
 *       the debug output.</li>
 * </ul>
 */
public final class ConnectorGenerator {

    /** Building footprints within this distance of an existing
     *  road don't need a connector. */
    private static final int ON_ROAD_DISTANCE = 5;
    /** Drift on connector StraightRoads. 0 keeps them crisp. */
    private static final double CONNECTOR_DRIFT = 0.0;

    private ConnectorGenerator() {}

    public static Result generate(Road spine, List<PlacedBuilding> placed, Culture culture) {
        List<Road> connectors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Road> existingRoads = new ArrayList<>();
        existingRoads.add(spine);

        // Sort by distance-to-spine ascending — civic-first attach order.
        List<PlacedBuilding> sorted = new ArrayList<>(placed);
        sorted.sort(Comparator.comparingDouble(b ->
                RoadGeometry.distanceToRoad(b.centre(), spine.primitive())));

        for (PlacedBuilding building : sorted) {
            // Find nearest road and the point on it.
            Road nearestRoad = null;
            BlockPos nearestPoint = null;
            double nearestDist = Double.MAX_VALUE;
            for (Road road : existingRoads) {
                BlockPos cp = RoadGeometry.closestPointOnRoad(building.centre(), road.primitive());
                double d = Math.sqrt(squaredXZ(building.centre(), cp));
                if (d < nearestDist) {
                    nearestDist = d;
                    nearestPoint = cp;
                    nearestRoad = road;
                }
            }
            if (nearestRoad == null) continue;  // unreachable; defensive

            // How far is the road from the FOOTPRINT edge (not the centre)?
            BlockPos buildingEntry = RoadGeometry.closestPointOnFootprint(nearestPoint, building);
            double entryDist = Math.sqrt(squaredXZ(nearestPoint, buildingEntry));
            if (entryDist <= ON_ROAD_DISTANCE) continue;  // already on a road

            // Reject connectors that cross another building.
            if (crossesOther(nearestPoint, buildingEntry, building, placed)) {
                warnings.add("connector for " + building.type().name()
                        + " crosses another building footprint — dropped, "
                        + building.type().name() + " is road-isolated");
                continue;
            }

            RoadPrimitive primitive = new RoadPrimitive.StraightRoad(
                    nearestPoint, buildingEntry, CONNECTOR_DRIFT,
                    RoadTier.VILLAGE_PATH.v1Tier());
            Road connector = new Road(RoadTier.VILLAGE_PATH, primitive, culture.roadMaterial());
            connectors.add(connector);
            existingRoads.add(connector);
        }

        return new Result(connectors, warnings);
    }

    private static boolean crossesOther(BlockPos a, BlockPos b, PlacedBuilding target,
                                        List<PlacedBuilding> placed) {
        for (PlacedBuilding other : placed) {
            if (other == target) continue;
            if (RoadGeometry.segmentCrossesFootprint(a, b, other)) return true;
        }
        return false;
    }

    private static long squaredXZ(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    public record Result(List<Road> connectors, List<String> warnings) {}
}
