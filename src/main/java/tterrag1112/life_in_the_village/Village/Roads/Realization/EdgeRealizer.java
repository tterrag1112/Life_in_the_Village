package tterrag1112.life_in_the_village.Village.Roads.Realization;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.PathMaterial;
import tterrag1112.life_in_the_village.Village.Decoration.Roads.RoadShape;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.ConnectorAllee;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.MilestoneDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.RoadOvergrowthDecorator;
import tterrag1112.life_in_the_village.Village.Roads.Docking.VillageDockingPoint;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Converts a graph {@link RoadEdge}'s cell path into placed blocks using
 * the {@link RoadPrimitive} chain pipeline.
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Resolve material context via {@link EdgeMaterialResolver} (biome, culture,
 *       maintenance, season axes applied).</li>
 *   <li>Build (or reuse) the edge's primitive chain via
 *       {@link PrimitiveChainBuilder}.</li>
 *   <li>For each primitive, compute its centerline and place road blocks via
 *       {@link UnifiedRoadPlacer} — passing culture for architectural detail passes.</li>
 *   <li>{@link RoadPrimitive.ArmApproach} segments use the owning village's
 *       biome-appropriate path material and receive a roadside allée via
 *       {@link ConnectorAllee}.</li>
 *   <li>All per-primitive centerlines are concatenated and the edge is
 *       marked realized via {@link RoadEdge#markRealized}.</li>
 * </ol>
 */
public final class EdgeRealizer {

    private EdgeRealizer() {}

    public static void realizeEdge(ServerLevel level,
                                   RoadEdge edge,
                                   WorldRoadGraph graph,
                                   VillageSavedData data) {
        // Fast path: fully realized with no terrain changes since last realization.
        if (edge.isRealized() && edge.getStaleCells().isEmpty()) return;

        // Stale-cell fallback: one or more cells were modified by terrain events.
        // Full re-realization is simpler and correct; selective cell patching is deferred.
        if (edge.isRealized() && !edge.getStaleCells().isEmpty()) {
            System.out.println("[EdgeRealizer] Edge " + edge.getEdgeId().toString().substring(0, 8)
                    + " has " + edge.getStaleCells().size() + " stale cell(s); full re-realization");
            edge.unrealize();
            edge.clearStaleness();
        }

        if (edge.getCellPath().isEmpty()) return;

        String shortId = edge.getEdgeId().toString().substring(0, 8);
        System.out.println("[EdgeRealizer] Realizing edge " + shortId
                + ", tier=" + edge.getTier()
                + ", cellPath=" + edge.getCellPath().size() + " cells");

        // Resolve material and culture for this edge
        EdgeMaterialResolver.MaterialContext matCtx =
                EdgeMaterialResolver.resolveForEdge(level, edge, graph, data);
        PathMaterial edgeMaterial = matCtx.material();
        String culture = matCtx.culture();

        // Build or reuse primitive chain
        List<RoadPrimitive> primitives = edge.hasPrimitives()
                ? edge.getPrimitives()
                : PrimitiveChainBuilder.buildPrimitivesForEdge(level, graph, data, edge);

        if (primitives.isEmpty()) {
            System.out.println("[EdgeRealizer] FAILED: PrimitiveChainBuilder returned empty chain for " + shortId);
            return;
        }

        // Persist the chain on the edge so future re-realizations reuse it
        if (!edge.hasPrimitives()) {
            edge.setPrimitives(primitives);
        }

        List<BlockPos> fullPath = new ArrayList<>();
        RoadShape.RoadTier edgeTier = PrimitiveChainBuilder.edgeTierToRoadTier(edge.getTier());

        for (RoadPrimitive primitive : primitives) {
            List<BlockPos> centerline = primitive.computeCenterline(level, level.getSeed());
            if (centerline.size() < 2) continue;

            List<BlockPos> placed = switch (primitive) {
                case RoadPrimitive.ArmApproach arm ->
                        placeArm(level, arm, centerline, edge, data);
                case RoadPrimitive.SmoothedPath sp ->
                        UnifiedRoadPlacer.place(level, centerline, edgeMaterial, sp.tier(), edge, culture);
                default ->
                        UnifiedRoadPlacer.place(level, centerline, edgeMaterial, edgeTier, edge, culture);
            };

            if (fullPath.isEmpty()) {
                fullPath.addAll(placed.isEmpty() ? centerline : placed);
            } else {
                // Skip first block of each successive primitive to avoid duplicates at joins
                List<BlockPos> src = placed.isEmpty() ? centerline : placed;
                if (src.size() > 1) fullPath.addAll(src.subList(1, src.size()));
            }
        }

        if (fullPath.isEmpty()) {
            System.out.println("[EdgeRealizer] FAILED: no blocks placed for edge " + shortId);
            return;
        }

        System.out.println("[EdgeRealizer] Realized edge " + shortId
                + ": " + fullPath.size() + " blocks, "
                + primitives.size() + " primitives, culture=" + culture);

        edge.markRealized(fullPath);

        // Decoration passes (run even on re-realization; decorators skip already-placed positions)
        MilestoneDecorator.decorate(level, edge, graph);       // GREAT_ROAD only
        RoadOvergrowthDecorator.decorate(level, edge, graph);  // all tiers
    }

    // =========================================================================
    // Approach segment placement
    // =========================================================================

    private static List<BlockPos> placeArm(ServerLevel level,
                                            RoadPrimitive.ArmApproach arm,
                                            List<BlockPos> centerline,
                                            RoadEdge edge,
                                            VillageSavedData data) {
        Village village = findVillage(data, arm.villageId());

        PathMaterial material;
        String villageType = null;
        if (village != null) {
            VillageBiomeStyle biome = VillageBiomeStyle.detect(level, arm.armEndpoint());
            material = PathMaterial.forBiomeAndTier(biome, village.getPathTier());
            villageType = village.getVillageType();
        } else {
            material = PathMaterial.cobblestone();
            System.out.println("[EdgeRealizer] WARNING: no village found for ArmApproach "
                    + arm.villageId().toString().substring(0, 8)
                    + "; using cobblestone material");
        }

        // Arm approaches use the village's own culture for architectural details
        List<BlockPos> placed = UnifiedRoadPlacer.place(
                level, centerline, material, arm.tier(), edge, villageType);

        if (village != null) {
            double armDirRad = Math.atan2(
                    arm.armEndpoint().getZ() - arm.dockingAnchor().getZ(),
                    arm.armEndpoint().getX() - arm.dockingAnchor().getX());
            VillageDockingPoint dock = new VillageDockingPoint(
                    arm.armEndpoint(), arm.dockingAnchor(),
                    armDirRad, arm.villageId(),
                    VillageDockingPoint.DEFAULT_APPROACH_LENGTH);
            ConnectorAllee.place(level, dock, centerline, villageType);
        }

        System.out.println("[EdgeRealizer] Placed ArmApproach for village "
                + arm.villageId().toString().substring(0, 8)
                + ": " + centerline.size() + " blocks"
                + ", anchor=" + arm.dockingAnchor().toShortString()
                + " → arm=" + arm.armEndpoint().toShortString());

        return placed;
    }

    private static Village findVillage(VillageSavedData data, UUID villageId) {
        return data.getAllVillages().stream()
                .filter(v -> v.getId().equals(villageId))
                .findFirst()
                .orElse(null);
    }
}
