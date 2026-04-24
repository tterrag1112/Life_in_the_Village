package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageRoadsSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Populates a village's {@link VillageRoadGraph} with GATEWAY nodes derived from
 * its layout's gate positions, and links each gateway to a corresponding
 * {@link RoadNode.NodeType#TERMINUS} node in the {@link tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph}.
 *
 * <h3>When to call</h3>
 * Call {@link #populate} immediately after a new village is registered in
 * {@link VillageRoadsSavedData} — i.e. after the spawn pipeline completes in
 * {@link tterrag1112.life_in_the_village.Village.VillageSpawner#spawnVillage}.
 * If the graph already has gateways (e.g. world reload), populate is a no-op.
 *
 * <h3>Arm endpoint</h3>
 * Each gateway gets an arm endpoint 32 blocks outside the village in the
 * outward direction.  If the computed endpoint falls in water or on a steep
 * cliff, it is pulled back in 8-block increments.  If no valid position
 * exists, the gateway position itself is used as a degenerate fallback.
 */
public final class GatewayPopulator {

    private GatewayPopulator() {}

    /** Blocks the arm extends from the gateway position outward. */
    public static final int ARM_LENGTH = 32;

    /** Pull-back increment when the arm endpoint is invalid. */
    private static final int ARM_STEP_BACK = 8;

    /** Max Y difference from gateway Y to neighboring surface before "cliff" is declared. */
    private static final int CLIFF_THRESHOLD = 12;

    // =========================================================================
    // Main entry point
    // =========================================================================

    /**
     * Creates gateway nodes in the village road graph and matching TERMINUS nodes
     * in the world road graph for the given village, based on its layout.
     *
     * <p>No-op if the village already has gateways (reload protection).
     *
     * @param level   server level (for height queries and saved-data access)
     * @param village the newly-spawned village
     * @param layout  the layout produced by the village planner
     */
    public static void populate(ServerLevel level, Village village, VillageLayout layout) {
        UUID villageId = village.getId();
        VillageRoadsSavedData roadsSaved = VillageRoadsSavedData.get(level);
        VillageRoadGraph graph = roadsSaved.getOrCreate(villageId);

        // Reload protection: skip if gateways already present
        if (!graph.gateways().isEmpty()) return;

        // Derive descriptors from the layout's gate positions
        List<GatewayDescriptor> descriptors = deriveDescriptors(village, layout);
        if (descriptors.isEmpty()) return;

        tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph worldGraph =
                WorldRoadSavedData.get(level).getGraph();

        for (GatewayDescriptor desc : descriptors) {
            BlockPos armEndpoint = computeArmEndpoint(level, desc.position(), desc.outwardDirection());

            // Create village-side gateway node (worldNodeId empty until backlink below)
            VillageRoadNode.GatewayInfo info = new VillageRoadNode.GatewayInfo(
                    Optional.empty(),
                    desc.outwardDirection(),
                    armEndpoint,
                    desc.role());
            VillageRoadNode gatewayNode = VillageRoadNode.gateway(desc.position(), info);
            graph.addNode(gatewayNode);

            // Create world-side TERMINUS node at the arm endpoint
            RoadNode terminusNode = new RoadNode(
                    UUID.randomUUID(), armEndpoint,
                    RoadNode.NodeType.TERMINUS, Optional.empty());
            terminusNode.setGatewayLink(
                    new RoadNode.GatewayLink(villageId, gatewayNode.nodeId()));
            worldGraph.addNode(terminusNode);

            // Backlink: update the village gateway with the world node UUID
            VillageRoadNode.GatewayInfo linkedInfo =
                    info.withWorldNodeId(Optional.of(terminusNode.nodeId()));
            graph.replaceNode(gatewayNode.withGatewayInfo(Optional.of(linkedInfo)));
        }

        roadsSaved.setDirty();
        WorldRoadSavedData.get(level).markDirty();

        System.out.println("[GatewayPopulator] village '" + village.getName()
                + "' got " + descriptors.size() + " gateway(s)");
    }

    // =========================================================================
    // Descriptor derivation
    // =========================================================================

    private static List<GatewayDescriptor> deriveDescriptors(Village village,
                                                              VillageLayout layout) {
        List<BlockPos> gates = layout.getGatePositions();
        BlockPos mainGate = layout.getMainGateEndpoint();
        BlockPos center = village.getAnchorPos();
        if (center == null) center = layout.getCenter();
        if (center == null) return List.of();

        if (gates.isEmpty()) {
            BlockPos primary = mainGate != null ? mainGate : center;
            VillageRoadNode.OutwardDirection dir = directionFromTo(center, primary);
            return List.of(new GatewayDescriptor(primary, dir, VillageRoadNode.GatewayRole.PRIMARY));
        }

        List<GatewayDescriptor> result = new java.util.ArrayList<>();
        boolean hasExplicitPrimary = false;

        for (BlockPos gate : gates) {
            boolean isPrimary = gate.equals(mainGate);
            if (isPrimary) hasExplicitPrimary = true;
            VillageRoadNode.GatewayRole role = isPrimary
                    ? VillageRoadNode.GatewayRole.PRIMARY
                    : VillageRoadNode.GatewayRole.SIDE;
            result.add(new GatewayDescriptor(gate, directionFromTo(center, gate), role));
        }

        // Ensure at least one PRIMARY
        if (!hasExplicitPrimary && !result.isEmpty()) {
            GatewayDescriptor first = result.get(0);
            result.set(0, new GatewayDescriptor(
                    first.position(), first.outwardDirection(), VillageRoadNode.GatewayRole.PRIMARY));
        }
        return result;
    }

    private static VillageRoadNode.OutwardDirection directionFromTo(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < 0.001 && Math.abs(dz) < 0.001) {
            return VillageRoadNode.OutwardDirection.EAST;
        }
        return VillageRoadNode.OutwardDirection.fromAngle(Math.atan2(dz, dx));
    }

    // =========================================================================
    // Arm endpoint computation
    // =========================================================================

    /**
     * Computes the arm endpoint for a gateway by projecting {@link #ARM_LENGTH}
     * blocks outward in {@code direction}. If that position is invalid (water or
     * steep cliff), pulls back in {@link #ARM_STEP_BACK}-block increments until a
     * valid position is found, or until falling back to the gateway position itself.
     */
    public static BlockPos computeArmEndpoint(ServerLevel level,
                                              BlockPos gatewayPos,
                                              VillageRoadNode.OutwardDirection direction) {
        double rad = direction.toRadians();
        double unitX = Math.cos(rad);
        double unitZ = Math.sin(rad);

        for (int dist = ARM_LENGTH; dist > 0; dist -= ARM_STEP_BACK) {
            int ax = gatewayPos.getX() + (int) Math.round(unitX * dist);
            int az = gatewayPos.getZ() + (int) Math.round(unitZ * dist);
            int ay = safeHeight(level, ax, az);
            BlockPos candidate = new BlockPos(ax, ay, az);
            if (isValidArmEndpoint(level, candidate)) return candidate;
        }

        // Degenerate fallback: gateway position itself
        System.out.println("[GatewayPopulator] WARNING: arm endpoint fallback to gateway pos at "
                + gatewayPos.toShortString() + " dir=" + direction);
        return new BlockPos(
                gatewayPos.getX(),
                safeHeight(level, gatewayPos.getX(), gatewayPos.getZ()),
                gatewayPos.getZ());
    }

    private static boolean isValidArmEndpoint(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return true; // accept unloaded — will be checked at realization

        // Reject water
        if (level.isWaterAt(pos) || level.isWaterAt(pos.below())) return false;

        // Reject steep cliffs (any neighbor within ±2 more than CLIFF_THRESHOLD above/below)
        int y = pos.getY();
        for (int ddx = -2; ddx <= 2; ddx++) {
            for (int ddz = -2; ddz <= 2; ddz++) {
                int ny = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        pos.getX() + ddx, pos.getZ() + ddz);
                if (Math.abs(ny - y) > CLIFF_THRESHOLD) return false;
            }
        }
        return true;
    }

    private static int safeHeight(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, 0, z))) return 64;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
