package tterrag1112.life_in_the_village.Village.Roads.Poi;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Track C3.2 (Phase 12) — finds worldgen-placed structures near online
 * players and registers them as POI candidates.
 *
 * <h3>Discovery model</h3>
 * Player-proximity scan: every tick of {@code PoiDiscoveryTickSystem}
 * (which runs every 10 seconds), the system enumerates loaded chunks
 * within {@link #SCAN_RADIUS_CHUNKS} of each online player and reads
 * each chunk's {@link ChunkAccess#getAllStarts() structure starts}.
 * Only chunks that are already loaded are inspected — no chunks are
 * force-loaded for discovery.
 *
 * <h3>Targets</h3>
 * The default target list ({@link #DEFAULT_TARGETS}) is six common
 * vanilla structures: pillager outposts, ruined portals, the four
 * biome-flavour shrines (desert / jungle pyramids, igloos, swamp huts).
 * Mod-added structures can extend this list by adding to
 * {@link #DEFAULT_TARGETS} in a future tweak.
 *
 * <h3>Skip rules (layered)</h3>
 * <ul>
 *   <li>Skip if {@code position} is inside any village's claim radius.</li>
 *   <li>Skip if another {@code DiscoveredPoi} already lives within
 *       {@link #DEDUP_RADIUS_BLOCKS} of {@code position}.</li>
 *   <li>If no graph node / edge is within
 *       {@link #PLANNER_RADIUS_BLOCKS}, the POI is registered but flagged
 *       {@code ISOLATED} (no edge created). The {@code PoiSubroadPlanner}
 *       skips ISOLATED records on subsequent runs.</li>
 * </ul>
 *
 * <h3>Determinism</h3>
 * Discovery is deterministic per (chunk position, structure type) pair —
 * the same chunk always produces the same {@code DiscoveredPoi} hit. The
 * actual registration order depends on which player loads the chunk
 * first, but registration is idempotent once the dedup radius rule
 * fires. Subroad routing is deterministic per
 * {@code (worldSeed, fromPos, toPos)} — same world, same path.
 */
public final class PoiDiscovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PoiDiscovery() {}

    /** Half-radius in chunks scanned around each player per tick. 16 chunks ≈ 256 blocks. */
    public static final int SCAN_RADIUS_CHUNKS = 16;

    /** Two POIs within this many blocks dedup to one (the first registered wins). */
    public static final int DEDUP_RADIUS_BLOCKS = 200;

    /** Planner search radius — beyond this the POI is registered ISOLATED. */
    public static final int PLANNER_RADIUS_BLOCKS = 500;

    /**
     * Default vanilla structures considered POIs. Six structures chosen
     * for surface-presence and "isolated landmark" feel: pillager outpost
     * (watchtower), ruined portal (ruin), four biome shrines.
     */
    public static final Set<Identifier> DEFAULT_TARGETS = Set.of(
            Identifier.fromNamespaceAndPath("minecraft", "pillager_outpost"),
            Identifier.fromNamespaceAndPath("minecraft", "ruined_portal"),
            Identifier.fromNamespaceAndPath("minecraft", "ruined_portal_desert"),
            Identifier.fromNamespaceAndPath("minecraft", "ruined_portal_jungle"),
            Identifier.fromNamespaceAndPath("minecraft", "ruined_portal_swamp"),
            Identifier.fromNamespaceAndPath("minecraft", "ruined_portal_mountain"),
            Identifier.fromNamespaceAndPath("minecraft", "ruined_portal_ocean"),
            Identifier.fromNamespaceAndPath("minecraft", "ruined_portal_nether"),
            Identifier.fromNamespaceAndPath("minecraft", "desert_pyramid"),
            Identifier.fromNamespaceAndPath("minecraft", "jungle_pyramid"),
            Identifier.fromNamespaceAndPath("minecraft", "igloo"),
            Identifier.fromNamespaceAndPath("minecraft", "swamp_hut")
    );

    /**
     * Top-level scan entry. Called by {@code PoiDiscoveryTickSystem}.
     */
    public static void scanForPlayers(ServerLevel level) {
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        VillageSavedData vdata = VillageSavedData.get(level);

        // Build a small lookup of existing POI positions so the dedup check
        // is O(P) per candidate rather than O(P) per chunk.
        List<DiscoveredPoi> existing =
                List.copyOf(saved.getAllPois().values());

        for (ServerPlayer player : level.players()) {
            scanAroundPlayer(level, player, saved, vdata, existing);
        }
    }

    private static void scanAroundPlayer(ServerLevel level,
                                          ServerPlayer player,
                                          WorldRoadSavedData saved,
                                          VillageSavedData vdata,
                                          List<DiscoveredPoi> existing) {
        ChunkPos centre = player.chunkPosition();
        int cxMin = centre.x - SCAN_RADIUS_CHUNKS;
        int cxMax = centre.x + SCAN_RADIUS_CHUNKS;
        int czMin = centre.z - SCAN_RADIUS_CHUNKS;
        int czMax = centre.z + SCAN_RADIUS_CHUNKS;

        Set<Identifier> targets = DEFAULT_TARGETS;

        // Build a Structure→Identifier table from the registry once per
        // scan, so each chunk lookup is an IdentityHashMap probe rather
        // than a registry walk.
        java.util.IdentityHashMap<Structure, Identifier> idByStructure =
                new java.util.IdentityHashMap<>();
        level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .listElements()
                .forEach(holder ->
                        idByStructure.put(holder.value(), holder.key().identifier()));

        for (int cx = cxMin; cx <= cxMax; cx++) {
            for (int cz = czMin; cz <= czMax; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                ChunkAccess chunk = level.getChunk(cx, cz);
                Map<Structure, StructureStart> starts = chunk.getAllStarts();
                if (starts.isEmpty()) continue;

                for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                    StructureStart start = entry.getValue();
                    if (!start.isValid()) continue;

                    Identifier id = idByStructure.get(entry.getKey());
                    if (id == null || !targets.contains(id)) continue;

                    BlockPos pos = pieceCentre(start);
                    if (pos == null) continue;

                    // Skip rules
                    if (insideAnyVillageClaim(pos, vdata)) continue;
                    if (dedupHit(pos, existing)) continue;

                    // Register
                    DiscoveredPoi fresh = new DiscoveredPoi(
                            UUID.randomUUID(), id, pos,
                            Optional.empty(), Optional.empty(),
                            level.getGameTime());
                    saved.putPoi(fresh);
                    existing = appendCopy(existing, fresh);
                    LOGGER.info("[PoiDiscovery] registered {} at {}",
                            id, pos.toShortString());
                }
            }
        }
    }

    /** First piece's bounding-box centre, surface-Y. */
    private static BlockPos pieceCentre(StructureStart start) {
        var pieces = start.getPieces();
        if (pieces.isEmpty()) return null;
        var bb = pieces.get(0).getBoundingBox();
        return new BlockPos(
                (bb.minX() + bb.maxX()) / 2,
                bb.minY(),
                (bb.minZ() + bb.maxZ()) / 2);
    }

    /**
     * Inside-village test using {@code Village.getRing2Radius()} (or a 96-
     * block fallback for villages without ring2 data) as the claim radius.
     * A POI within this radius is part of the village footprint and is
     * skipped for subroad eligibility.
     */
    public static final int VILLAGE_CLAIM_FALLBACK_BLOCKS = 96;

    private static boolean insideAnyVillageClaim(BlockPos pos, VillageSavedData vdata) {
        for (Village v : vdata.getAllVillages()) {
            BlockPos a = v.getAnchorPos();
            if (a == null) continue;
            int claim = v.getRing2Radius();
            if (claim <= 0) claim = VILLAGE_CLAIM_FALLBACK_BLOCKS;
            int dx = pos.getX() - a.getX();
            int dz = pos.getZ() - a.getZ();
            if ((long) dx * dx + (long) dz * dz <= (long) claim * claim) return true;
        }
        return false;
    }

    private static boolean dedupHit(BlockPos pos, List<DiscoveredPoi> existing) {
        long radSq = (long) DEDUP_RADIUS_BLOCKS * DEDUP_RADIUS_BLOCKS;
        for (DiscoveredPoi p : existing) {
            long dx = pos.getX() - p.position().getX();
            long dz = pos.getZ() - p.position().getZ();
            if (dx * dx + dz * dz <= radSq) return true;
        }
        return false;
    }

    private static List<DiscoveredPoi> appendCopy(List<DiscoveredPoi> src,
                                                    DiscoveredPoi added) {
        java.util.ArrayList<DiscoveredPoi> out = new java.util.ArrayList<>(src.size() + 1);
        out.addAll(src);
        out.add(added);
        return out;
    }

    /**
     * Returns true if any graph node / edge centre is within
     * {@link #PLANNER_RADIUS_BLOCKS} of {@code pos}. Used by
     * {@code PoiSubroadPlanner} to decide between routing and
     * registering the POI as ISOLATED.
     */
    public static boolean hasNearbyInfrastructure(BlockPos pos, WorldRoadGraph graph) {
        long radSq = (long) PLANNER_RADIUS_BLOCKS * PLANNER_RADIUS_BLOCKS;
        for (RoadNode n : graph.allNodes()) {
            if (n.type() == RoadNode.NodeType.POI_STUB) continue;
            long dx = pos.getX() - n.position().getX();
            long dz = pos.getZ() - n.position().getZ();
            if (dx * dx + dz * dz <= radSq) return true;
        }
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getCellPath().isEmpty()) continue;
            // Only test endpoints; the planner does its own corridor search.
            for (long cellKey : new long[] {
                    edge.getCellPath().get(0),
                    edge.getCellPath().get(edge.getCellPath().size() - 1)}) {
                BlockPos centre = tterrag1112.life_in_the_village.Village.Economy.Trade
                        .AtlasRouteRouter.cellKeyToBlockCenter(cellKey);
                long dx = pos.getX() - centre.getX();
                long dz = pos.getZ() - centre.getZ();
                if (dx * dx + dz * dz <= radSq) return true;
            }
        }
        return false;
    }
}
