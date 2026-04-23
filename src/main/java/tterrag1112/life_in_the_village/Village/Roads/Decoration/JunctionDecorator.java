package tterrag1112.life_in_the_village.Village.Roads.Decoration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Decorates road graph nodes with atmospheric structures.
 *
 * <h3>TRUNK_JUNCTION nodes</h3>
 * Receives one of four kingdom-era decor variants, culture-biased via
 * majority vote of incident-edge maintainer villages:
 * <ul>
 *   <li><b>Well</b> — cobblestone ring + water center + fence-gate windlass</li>
 *   <li><b>Shrine</b> — 2-block pillar + stone pressure-plate cap</li>
 *   <li><b>Notice board</b> — fence post + oak sign (blank, functional placeholder)</li>
 *   <li><b>Covered rest spot</b> — 4 fence posts with slab roof, stone bench</li>
 * </ul>
 * Variant is chosen deterministically by node UUID hash.
 * Road signs are placed for each outgoing edge that leads to a VILLAGE_DOCK within
 * 3 graph hops.
 *
 * <h3>GREAT_ROAD_ANCHOR nodes</h3>
 * Always an Old Realm ruin — one of three variants chosen by node UUID hash:
 * <ul>
 *   <li><b>Collapsed waystation</b> — mossy-cobblestone foundation ring, 2 partial pillars, rubble</li>
 *   <li><b>Cracked statue</b> — polished-andesite base, torso, fallen head, moss carpet</li>
 *   <li><b>Sunken altar</b> — polished-andesite floor, central water, cracked-stone-bricks ring</li>
 * </ul>
 *
 * <p>All placed block positions are recorded on {@link RoadNode#getDecorationPositions()}
 * for persistence and duplicate-skip on re-decoration.
 */
public final class JunctionDecorator {

    // ── Decor variant keys ────────────────────────────────────────────────────
    private static final int JUNCTION_WELL         = 0;
    private static final int JUNCTION_SHRINE       = 1;
    private static final int JUNCTION_NOTICE_BOARD = 2;
    private static final int JUNCTION_REST_SPOT    = 3;

    private static final int ANCHOR_WAYSTATION     = 0;
    private static final int ANCHOR_STATUE         = 1;
    private static final int ANCHOR_ALTAR          = 2;

    /** Max hops walked for road-sign destination resolution. */
    private static final int SIGN_MAX_HOPS = 3;

    private JunctionDecorator() {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Decorates the node. No-ops for node types not listed above, or if the
     * node is already decorated (non-empty {@code decorationPositions}).
     */
    public static void decorate(ServerLevel level,
                                RoadNode node,
                                WorldRoadGraph graph,
                                VillageSavedData villageData) {
        if (!node.getDecorationPositions().isEmpty()) return; // already decorated

        if (node.type() == RoadNode.NodeType.TRUNK_JUNCTION) {
            decorateTrunkJunction(level, node, graph, villageData);
        } else if (node.type() == RoadNode.NodeType.GREAT_ROAD_ANCHOR) {
            decorateGreatRoadAnchor(level, node, graph);
        }
    }

    // =========================================================================
    // TRUNK_JUNCTION
    // =========================================================================

    private static void decorateTrunkJunction(ServerLevel level,
                                               RoadNode node,
                                               WorldRoadGraph graph,
                                               VillageSavedData villageData) {
        List<RoadEdge> incident = incidentEdges(graph, node.nodeId());
        String culture = resolveCulture(incident, villageData);

        // Choose corner furthest from any incident edge direction
        BlockPos corner = bestCorner(level, node.position(), incident);
        if (corner == null) return;

        int variant = (int) ((Math.abs(node.nodeId().getLeastSignificantBits()) % 4));

        List<BlockPos> placed = switch (variant) {
            case JUNCTION_WELL         -> placeWell(level, corner, culture);
            case JUNCTION_SHRINE       -> placeShrine(level, corner, culture);
            case JUNCTION_NOTICE_BOARD -> placeNoticeBoard(level, corner, culture);
            default                    -> placeRestSpot(level, corner, culture);
        };
        placed.forEach(node::addDecorationPosition);

        // Road signs: one per outgoing edge that reaches a village
        placeRoadSigns(level, node, incident, graph, villageData, placed);
        placed.forEach(p -> {}); // already added above per sign
    }

    // ── Well ──────────────────────────────────────────────────────────────────

    private static List<BlockPos> placeWell(ServerLevel level, BlockPos base, String culture) {
        List<BlockPos> placed = new ArrayList<>();
        Block wellWall = cultureWallBlock(culture);
        Block wellGate = cultureFenceGateBlock(culture);

        // 3×3 area: walls in cardinal direction from center, water in center
        int y = base.getY();
        int cx = base.getX();
        int cz = base.getZ();

        // Foundation: cobblestone under center
        set(level, new BlockPos(cx, y - 1, cz),
                Blocks.COBBLESTONE.defaultBlockState(), placed);
        // Water source in center
        set(level, base, Blocks.WATER.defaultBlockState(), placed);
        // Four walls in cross
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos wp = base.relative(dir);
            int wy = surfaceY(level, wp.getX(), wp.getZ());
            set(level, new BlockPos(wp.getX(), wy, wp.getZ()),
                    wellWall.defaultBlockState(), placed);
        }
        // Fence gate on top as a windlass beam (fence gates have HORIZONTAL_FACING)
        BlockPos gatePos = base.above();
        set(level, gatePos,
                wellGate.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
                placed);
        return placed;
    }

    // ── Shrine ────────────────────────────────────────────────────────────────

    private static List<BlockPos> placeShrine(ServerLevel level, BlockPos base, String culture) {
        List<BlockPos> placed = new ArrayList<>();
        Block pillarBlock = culturePillarBlock(culture);

        // Base slab
        set(level, base, Blocks.COBBLESTONE_SLAB.defaultBlockState(), placed);
        // Pillar
        set(level, base.above(), pillarBlock.defaultBlockState(), placed);
        // Cap: stone pressure plate
        set(level, base.above(2), Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), placed);

        // Imperial: polished_andesite accents on north/south
        if ("imperial".equals(culture)) {
            for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
                BlockPos accentPos = base.relative(dir);
                int ay = surfaceY(level, accentPos.getX(), accentPos.getZ());
                set(level, new BlockPos(accentPos.getX(), ay, accentPos.getZ()),
                        Blocks.POLISHED_ANDESITE.defaultBlockState(), placed);
            }
        }
        return placed;
    }

    // ── Notice board ──────────────────────────────────────────────────────────

    private static List<BlockPos> placeNoticeBoard(ServerLevel level, BlockPos base, String culture) {
        List<BlockPos> placed = new ArrayList<>();
        Block fence = cultureFenceBlock(culture);

        // Fence post
        set(level, base, fence.defaultBlockState(), placed);
        // Standing sign on top — default rotation (south-facing)
        BlockPos signPos = base.above();
        Block signBlock = cultureSignBlock(culture);
        set(level, signPos,
                signBlock.defaultBlockState()
                        .setValue(BlockStateProperties.ROTATION_16, 0),
                placed);
        // Write "Notice Board" text
        writeSign(level, signPos,
                Component.literal("Notice Board"),
                Component.empty(),
                Component.empty(),
                Component.empty());
        return placed;
    }

    // ── Covered rest spot ─────────────────────────────────────────────────────

    private static List<BlockPos> placeRestSpot(ServerLevel level, BlockPos base, String culture) {
        List<BlockPos> placed = new ArrayList<>();
        Block fence = cultureFenceBlock(culture);
        Block roofSlab = cultureRoofSlab(culture);

        // 4 corner fence posts
        int[][] corners = {{0, 0}, {2, 0}, {0, 2}, {2, 2}};
        int bx = base.getX();
        int bz = base.getZ();
        int by = base.getY();

        for (int[] c : corners) {
            int px = bx + c[0];
            int pz = bz + c[1];
            int py = surfaceY(level, px, pz);
            BlockPos post = new BlockPos(px, py, pz);
            set(level, post, fence.defaultBlockState(), placed);
            // Roof slab on top
            set(level, post.above(), roofSlab.defaultBlockState(), placed);
        }

        // Fill interior roof (3 blocks between corner top slabs)
        for (int rx = 0; rx <= 2; rx++) {
            for (int rz = 0; rz <= 2; rz++) {
                if ((rx == 0 || rx == 2) && (rz == 0 || rz == 2)) continue; // skip corners
                int py = surfaceY(level, bx + rx, bz + rz) + 1;
                set(level, new BlockPos(bx + rx, py, bz + rz),
                        roofSlab.defaultBlockState(), placed);
            }
        }

        // Stone slab bench in center
        int benchY = surfaceY(level, bx + 1, bz + 1);
        set(level, new BlockPos(bx + 1, benchY, bz + 1),
                Blocks.STONE_SLAB.defaultBlockState(), placed);
        return placed;
    }

    // =========================================================================
    // GREAT_ROAD_ANCHOR
    // =========================================================================

    private static void decorateGreatRoadAnchor(ServerLevel level,
                                                 RoadNode node,
                                                 WorldRoadGraph graph) {
        BlockPos base = surfacePos(level, node.position());
        int variant = (int) ((Math.abs(node.nodeId().getLeastSignificantBits()) % 3));

        List<BlockPos> placed = switch (variant) {
            case ANCHOR_WAYSTATION -> placeCollapsedWaystation(level, base);
            case ANCHOR_STATUE     -> placeCrackedStatue(level, base);
            default                -> placeSunkenAltar(level, base);
        };
        placed.forEach(node::addDecorationPosition);
    }

    // ── Collapsed waystation ──────────────────────────────────────────────────

    private static List<BlockPos> placeCollapsedWaystation(ServerLevel level, BlockPos base) {
        List<BlockPos> placed = new ArrayList<>();
        int bx = base.getX();
        int bz = base.getZ();
        int by = base.getY();

        // 3×3 mossy-cobblestone foundation (just the perimeter = 8 blocks)
        for (int rx = -1; rx <= 1; rx++) {
            for (int rz = -1; rz <= 1; rz++) {
                if (rx == 0 && rz == 0) continue;
                int py = surfaceY(level, bx + rx, bz + rz);
                set(level, new BlockPos(bx + rx, py, bz + rz),
                        Blocks.MOSSY_COBBLESTONE.defaultBlockState(), placed);
            }
        }

        // Two standing chiseled-stone-brick pillars (NW and SE corners, 2 blocks tall)
        int[][] pillars = {{-1, -1}, {1, 1}};
        for (int[] p : pillars) {
            int px = bx + p[0];
            int pz = bz + p[1];
            int py = surfaceY(level, px, pz);
            set(level, new BlockPos(px, py, pz),
                    Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), placed);
            set(level, new BlockPos(px, py + 1, pz),
                    Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), placed);
        }

        // Rubble pile: random cobblestone/cracked-stone-bricks at surface
        long posHash = (long) bx * 374761393L ^ (long) bz * 668265263L;
        for (int rx = -2; rx <= 2; rx++) {
            for (int rz = -2; rz <= 2; rz++) {
                if (Math.abs(rx) < 2 && Math.abs(rz) < 2) continue; // skip center 3×3
                long h = posHash ^ (rx * 31L + rz * 37L);
                if ((h & 0x3L) != 0) continue; // ~25% density
                int py = surfaceY(level, bx + rx, bz + rz);
                Block rubble = ((h & 0x4L) != 0) ? Blocks.COBBLESTONE : Blocks.CRACKED_STONE_BRICKS;
                set(level, new BlockPos(bx + rx, py, bz + rz), rubble.defaultBlockState(), placed);
            }
        }

        // Overgrowth: short_grass and oak_sapling around the ruin
        int sy = surfaceY(level, bx + 2, bz - 1);
        BlockPos saplingPos = new BlockPos(bx + 2, sy, bz - 1);
        if (level.getBlockState(saplingPos).isAir()
                && isPlantable(level.getBlockState(saplingPos.below()))) {
            set(level, saplingPos, Blocks.OAK_SAPLING.defaultBlockState(), placed);
        }
        return placed;
    }

    // ── Cracked statue ────────────────────────────────────────────────────────

    private static List<BlockPos> placeCrackedStatue(ServerLevel level, BlockPos base) {
        List<BlockPos> placed = new ArrayList<>();

        // Pedestal
        set(level, base, Blocks.POLISHED_ANDESITE.defaultBlockState(), placed);
        // Torso (2 blocks)
        set(level, base.above(), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), placed);
        set(level, base.above(2), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), placed);
        // Fallen head: cobblestone on the ground beside the pedestal
        BlockPos headPos = base.east(2);
        int hy = surfaceY(level, headPos.getX(), headPos.getZ());
        set(level, new BlockPos(headPos.getX(), hy, headPos.getZ()),
                Blocks.COBBLESTONE.defaultBlockState(), placed);
        // Moss carpet on top of torso
        set(level, base.above(3), Blocks.MOSS_CARPET.defaultBlockState(), placed);
        return placed;
    }

    // ── Sunken altar ──────────────────────────────────────────────────────────

    private static List<BlockPos> placeSunkenAltar(ServerLevel level, BlockPos base) {
        List<BlockPos> placed = new ArrayList<>();
        int bx = base.getX();
        int bz = base.getZ();
        int by = base.getY();

        // 3×3 polished_andesite floor
        for (int rx = -1; rx <= 1; rx++) {
            for (int rz = -1; rz <= 1; rz++) {
                int py = surfaceY(level, bx + rx, bz + rz);
                set(level, new BlockPos(bx + rx, py, bz + rz),
                        Blocks.POLISHED_ANDESITE.defaultBlockState(), placed);
            }
        }

        // Central water source (1 block depression at center)
        set(level, base, Blocks.WATER.defaultBlockState(), placed);

        // Cracked-stone-bricks ring around the water (only cardinal neighbors)
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos rp = base.relative(dir);
            int ry = surfaceY(level, rp.getX(), rp.getZ());
            set(level, new BlockPos(rp.getX(), ry, rp.getZ()),
                    Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), placed);
        }

        // Oak_leaves debris (2 random positions outside the 3×3)
        long[][] debrisOffsets = {{2, 1}, {-1, 2}};
        for (long[] d : debrisOffsets) {
            int lx = bx + (int) d[0];
            int lz = bz + (int) d[1];
            int ly = surfaceY(level, lx, lz);
            BlockPos lp = new BlockPos(lx, ly, lz);
            if (level.getBlockState(lp).isAir()) {
                set(level, lp, Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(BlockStateProperties.PERSISTENT, true), placed);
            }
        }
        return placed;
    }

    // =========================================================================
    // Road signs (D4)
    // =========================================================================

    private static void placeRoadSigns(ServerLevel level,
                                        RoadNode node,
                                        List<RoadEdge> incident,
                                        WorldRoadGraph graph,
                                        VillageSavedData villageData,
                                        List<BlockPos> placed) {
        BlockPos nodePos = surfacePos(level, node.position());
        String culture = resolveCulture(incident, villageData);

        for (RoadEdge edge : incident) {
            // Direction from this node toward the other endpoint
            UUID otherId = edge.getNodeAId().equals(node.nodeId())
                    ? edge.getNodeBId() : edge.getNodeAId();
            RoadNode other = graph.getNode(otherId);
            if (other == null) continue;

            // Resolve destination village within SIGN_MAX_HOPS hops
            String destName = resolveDestination(otherId, node.nodeId(), graph, villageData, SIGN_MAX_HOPS);
            if (destName == null) continue; // no interesting destination in range

            // Sign post position: 1 block in the edge direction from the junction corner
            int dx = other.position().getX() - node.position().getX();
            int dz = other.position().getZ() - node.position().getZ();
            double dist = Math.max(1.0, Math.sqrt((double) dx * dx + (double) dz * dz));
            int nx = nodePos.getX() + (int) Math.round(dx / dist);
            int nz = nodePos.getZ() + (int) Math.round(dz / dist);
            int ny = surfaceY(level, nx, nz);

            // Skip if the sign position overlaps another road block
            BlockPos signBase = new BlockPos(nx, ny, nz);
            if (!level.isLoaded(signBase)) continue;
            if (isRoadMaterial(level.getBlockState(signBase))) continue;

            Block fenceBlock = cultureFenceBlock(culture);
            Block signBlock  = cultureSignBlock(culture);

            // Fence post
            set(level, signBase, fenceBlock.defaultBlockState(), placed);
            node.addDecorationPosition(signBase.immutable());

            // Standing sign on top
            BlockPos signPos = signBase.above();
            int rotation = (directionToRotation(dx, dz) + 8) % 16;
            set(level, signPos,
                    signBlock.defaultBlockState()
                            .setValue(BlockStateProperties.ROTATION_16, rotation),
                    placed);
            node.addDecorationPosition(signPos.immutable());

            // Direction arrow and destination name
            String arrow = directionArrow(dx, dz);
            String truncated = destName.length() > 15 ? destName.substring(0, 15) : destName;
            writeSign(level, signPos,
                    Component.literal(arrow),
                    Component.literal(truncated),
                    Component.empty(),
                    Component.empty());
        }
    }

    /** Walks graph up to maxHops hops from startId to find a VILLAGE_DOCK. Returns village name or null. */
    @Nullable
    private static String resolveDestination(UUID startId, UUID excludeId,
                                              WorldRoadGraph graph,
                                              VillageSavedData villageData,
                                              int maxHops) {
        UUID currentId = startId;
        UUID prevId    = excludeId;

        for (int hop = 0; hop < maxHops; hop++) {
            RoadNode current = graph.getNode(currentId);
            if (current == null) return null;

            if (current.type() == RoadNode.NodeType.VILLAGE_DOCK) {
                // Find village with this dock node
                for (Village v : villageData.getAllVillages()) {
                    if (v.getDockNodeId().filter(currentId::equals).isPresent()) {
                        return v.getName();
                    }
                }
                return null;
            }

            // Walk to next node via the highest-tier incident edge (prefer TRUNK/GREAT_ROAD)
            UUID finalCurrentId = currentId;
            UUID finalPrevId    = prevId;
            List<RoadEdge> outgoing = new ArrayList<>();
            for (RoadEdge e : graph.allEdges()) {
                if (e.getNodeAId().equals(finalCurrentId) || e.getNodeBId().equals(finalCurrentId)) {
                    UUID nbr = e.getNodeAId().equals(finalCurrentId) ? e.getNodeBId() : e.getNodeAId();
                    if (!nbr.equals(finalPrevId)) outgoing.add(e);
                }
            }
            if (outgoing.isEmpty()) return null;
            // Pick highest-tier edge
            outgoing.sort((a, b) -> a.getTier().ordinal() - b.getTier().ordinal());
            RoadEdge best = outgoing.get(0);
            UUID nextId = best.getNodeAId().equals(currentId) ? best.getNodeBId() : best.getNodeAId();
            prevId    = currentId;
            currentId = nextId;
        }
        return null;
    }

    // =========================================================================
    // Culture helpers
    // =========================================================================

    private static String resolveCulture(List<RoadEdge> incident, VillageSavedData villageData) {
        Map<String, Integer> votes = new HashMap<>();
        for (RoadEdge e : incident) {
            for (UUID vid : e.getMaintainerVillageIds()) {
                villageData.getVillageById(vid).ifPresent(v -> {
                    String c = v.getVillageType();
                    if (c != null) votes.merge(c, 1, Integer::sum);
                });
            }
        }
        return votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("default");
    }

    private static Block cultureWallBlock(String culture) {
        return switch (culture) {
            case "imperial"  -> Blocks.STONE_BRICK_WALL;
            case "highland"  -> Blocks.MOSSY_COBBLESTONE_WALL;
            case "nordic"    -> Blocks.COBBLESTONE_WALL;
            default          -> Blocks.COBBLESTONE_WALL;
        };
    }

    private static Block cultureFenceBlock(String culture) {
        return switch (culture) {
            case "nordic"    -> Blocks.SPRUCE_FENCE;
            case "imperial"  -> Blocks.DARK_OAK_FENCE;
            case "highland"  -> Blocks.BIRCH_FENCE;
            default          -> Blocks.OAK_FENCE;
        };
    }

    private static Block cultureFenceGateBlock(String culture) {
        return switch (culture) {
            case "nordic"    -> Blocks.SPRUCE_FENCE_GATE;
            case "imperial"  -> Blocks.DARK_OAK_FENCE_GATE;
            case "highland"  -> Blocks.BIRCH_FENCE_GATE;
            default          -> Blocks.OAK_FENCE_GATE;
        };
    }

    private static Block culturePillarBlock(String culture) {
        return switch (culture) {
            case "imperial" -> Blocks.POLISHED_ANDESITE;
            case "highland" -> Blocks.MOSSY_COBBLESTONE;
            case "nordic"   -> Blocks.SPRUCE_LOG;
            default         -> Blocks.COBBLESTONE;
        };
    }

    private static Block cultureSignBlock(String culture) {
        return switch (culture) {
            case "nordic"    -> Blocks.SPRUCE_SIGN;
            case "imperial"  -> Blocks.DARK_OAK_SIGN;
            case "highland"  -> Blocks.BIRCH_SIGN;
            default          -> Blocks.OAK_SIGN;
        };
    }

    private static Block cultureRoofSlab(String culture) {
        return switch (culture) {
            case "imperial" -> Blocks.STONE_BRICK_SLAB;
            case "highland" -> Blocks.COBBLESTONE_SLAB;
            case "nordic"   -> Blocks.SPRUCE_SLAB;
            default         -> Blocks.OAK_SLAB;
        };
    }

    // =========================================================================
    // Direction / rotation helpers
    // =========================================================================

    /**
     * Converts a direction vector to a Minecraft sign ROTATION_16 value (0–15).
     * 0 = south (+Z), 4 = west (−X), 8 = north (−Z), 12 = east (+X).
     */
    private static int directionToRotation(int dx, int dz) {
        double angleCwDeg = (Math.atan2(-dx, dz) * 180.0 / Math.PI + 360.0) % 360.0;
        return ((int) Math.round(angleCwDeg / 22.5)) % 16;
    }

    private static String directionArrow(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? ">>" : "<<";
        } else {
            return dz > 0 ? "vv" : "^^";
        }
    }

    // =========================================================================
    // Corner selection
    // =========================================================================

    /**
     * Returns the diagonal corner of the node position that is furthest
     * (largest angular separation) from any incident edge direction. Falls
     * back to the NE corner if no incident edges exist.
     */
    @Nullable
    private static BlockPos bestCorner(ServerLevel level, BlockPos nodePos, List<RoadEdge> incident) {
        // Gather edge directions from this node (using first vs last cell center)
        // Use worldgraph node positions instead — compute direction to each neighbor
        // We only have the edge objects; use first/last of blockPath if available
        double[][] incidentDirs = new double[incident.size()][2];
        for (int i = 0; i < incident.size(); i++) {
            RoadEdge e = incident.get(i);
            List<BlockPos> bp = e.getBlockPath();
            if (bp.size() >= 2) {
                BlockPos fa = bp.get(0);
                BlockPos fb = bp.get(bp.size() - 1);
                // direction from node toward the road
                int dx = (fa.distSqr(nodePos) < fb.distSqr(nodePos))
                        ? fb.getX() - fa.getX() : fa.getX() - fb.getX();
                int dz = (fa.distSqr(nodePos) < fb.distSqr(nodePos))
                        ? fb.getZ() - fa.getZ() : fa.getZ() - fb.getZ();
                double len = Math.max(1.0, Math.sqrt((double) dx * dx + (double) dz * dz));
                incidentDirs[i][0] = dx / len;
                incidentDirs[i][1] = dz / len;
            }
        }

        // 4 diagonal offsets
        int[][] diagonals = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        double bestScore = -1;
        int[] bestDir = diagonals[0];

        for (int[] d : diagonals) {
            double cx = d[0], cz = d[1];
            double len = Math.sqrt(2.0);
            double ux = cx / len, uz = cz / len;
            // Minimum dot product to any incident edge direction
            double minDot = 1.0;
            for (double[] dir : incidentDirs) {
                double dot = ux * dir[0] + uz * dir[1];
                if (dot < minDot) minDot = dot;
            }
            if (minDot > bestScore) {
                bestScore = minDot;
                bestDir = d;
            }
        }

        int bx = nodePos.getX() + bestDir[0] * 3;
        int bz = nodePos.getZ() + bestDir[1] * 3;
        if (!level.isLoaded(new BlockPos(bx, 0, bz))) return null;
        int by = surfaceY(level, bx, bz);
        return new BlockPos(bx, by, bz);
    }

    // =========================================================================
    // Block placement helpers
    // =========================================================================

    private static void set(ServerLevel level, BlockPos pos, BlockState state,
                             List<BlockPos> placed) {
        if (!level.isLoaded(pos)) return;
        level.setBlock(pos, state, 3);
        placed.add(pos.immutable());
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        if (!level.isLoaded(new BlockPos(x, 0, z))) return 64;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static BlockPos surfacePos(ServerLevel level, BlockPos pos) {
        int y = surfaceY(level, pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), y, pos.getZ());
    }

    private static boolean isPlantable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT);
    }

    private static boolean isRoadMaterial(BlockState state) {
        return state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.STONE)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.COARSE_DIRT);
    }

    private static List<RoadEdge> incidentEdges(WorldRoadGraph graph, UUID nodeId) {
        List<RoadEdge> out = new ArrayList<>();
        for (RoadEdge e : graph.allEdges()) {
            if (e.getNodeAId().equals(nodeId) || e.getNodeBId().equals(nodeId)) out.add(e);
        }
        return out;
    }

    private static void writeSign(ServerLevel level, BlockPos pos,
                                   Component line0, Component line1,
                                   Component line2, Component line3) {
        if (!level.isLoaded(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SignBlockEntity sign)) return;
        SignText text = sign.getFrontText();
        text = text.setMessage(0, line0);
        text = text.setMessage(1, line1);
        text = text.setMessage(2, line2);
        text = text.setMessage(3, line3);
        sign.setText(text, true);
        sign.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
    }
}
