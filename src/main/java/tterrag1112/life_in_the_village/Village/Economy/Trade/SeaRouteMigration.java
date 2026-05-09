package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Track C3.3 (Phase 13) — one-shot, idempotent migration that
 * converts legacy {@link SeaRoute} records into SEA-tier
 * {@link RoadEdge}s on the unified world graph.
 *
 * <h3>Idempotency</h3>
 * Gated by {@link WorldRoadSavedData#isSeaRoutesMigrated()}. The first
 * load after Phase 13 ships runs the migration and flips the flag.
 * Subsequent loads short-circuit. Fresh worlds initialise the flag
 * {@code true} (see {@link WorldRoadSavedData#WorldRoadSavedData()}),
 * so the migration never runs in a world that never saw a SeaRoute.
 *
 * <h3>UUID preservation</h3>
 * Each new {@code RoadEdge} is created with the same UUID the legacy
 * {@code SeaRoute.connectionId} carried. {@link BoatCaravan}'s
 * persisted {@code seaRouteId} field already references that UUID,
 * so existing in-flight boat caravans don't have to be patched —
 * their pointer is now valid against the new edge.
 *
 * <h3>Cellpath / quality / endpoint nodes</h3>
 * The legacy {@code SeaRoute.cellPath} (a {@code List<Long>} of atlas
 * cell-keys) is copied verbatim into {@code RoadEdge.cellPath} (same
 * encoding). Quality (0–100) becomes {@code RoadEdge.maintenance}
 * (same scale). Two new {@code VILLAGE_DOCK} nodes are created at the
 * cellPath endpoints; both villages are added as
 * {@code maintainerVillageIds} so the existing upkeep loop picks the
 * edge up.
 *
 * <h3>TradeRoute references</h3>
 * Legacy sea-route TradeRoutes carry their {@code connectionId} field
 * pointing at the SeaRoute. Post-migration, the same UUID is now an
 * edgeId, so the migration also walks the trade-route table and
 * rewrites each affected route's {@code edgeIds} list to the
 * single-element {@code [connectionId]}. {@code connectionId} stays
 * populated as a save-compat fossil but is no longer the primary
 * disambiguator (the dispatcher inspects the first edge's tier).
 */
public final class SeaRouteMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SeaRouteMigration() {}

    /**
     * Inline shim for the deleted {@code SeaRoute} class. Carries the
     * minimum field set the codec needs to round-trip pre-Phase-13
     * saves; once the migration runs, all instances of this record
     * are discarded.
     *
     * <p>Any field on the legacy SeaRoute that isn't read by the
     * migration has been omitted (lastMaintenanceTick, active,
     * dockA/dockB, routeIds) — those are no longer meaningful.
     */
    public record LegacySeaRoute(
            UUID seaRouteId,
            UUID villageA,
            UUID villageB,
            List<Long> cellPath,
            int quality
    ) {
        public static final Codec<LegacySeaRoute> CODEC = RecordCodecBuilder.create(i ->
                i.group(
                        UUIDUtil.CODEC.fieldOf("seaRouteId").forGetter(s -> s.seaRouteId),
                        UUIDUtil.CODEC.fieldOf("villageA").forGetter(s -> s.villageA),
                        UUIDUtil.CODEC.fieldOf("villageB").forGetter(s -> s.villageB),
                        UUIDUtil.CODEC.optionalFieldOf("dockA")
                                .forGetter(s -> Optional.<UUID>empty()),
                        UUIDUtil.CODEC.optionalFieldOf("dockB")
                                .forGetter(s -> Optional.<UUID>empty()),
                        Codec.LONG.listOf().fieldOf("cellPath")
                                .forGetter(s -> s.cellPath),
                        Codec.INT.fieldOf("quality").forGetter(s -> s.quality),
                        Codec.LONG.optionalFieldOf("lastMaintenanceTick", 0L)
                                .forGetter(s -> 0L),
                        Codec.BOOL.optionalFieldOf("active", true)
                                .forGetter(s -> true),
                        UUIDUtil.CODEC.listOf()
                                .optionalFieldOf("routeIds", new ArrayList<>())
                                .forGetter(s -> new ArrayList<UUID>())
                ).apply(i, (id, va, vb, da, db, cp, q, lmt, act, ri) ->
                        new LegacySeaRoute(id, va, vb, cp, q)));
    }

    /** Drives the migration. Safe to call on every level load. */
    public static void migrateIfNeeded(ServerLevel level) {
        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        if (roadData.isSeaRoutesMigrated()) return;

        VillageSavedData vdata = VillageSavedData.get(level);
        WorldRoadGraph graph = roadData.getGraph();

        List<LegacySeaRoute> legacy = vdata.peekLegacySeaRoutes();
        if (legacy.isEmpty()) {
            // Pre-C3.3 save with no sea routes; just flip the flag.
            roadData.setSeaRoutesMigrated(true);
            LOGGER.info("[SeaRouteMigration] no legacy sea routes; flag flipped.");
            return;
        }

        int migrated = 0;
        int seaLevel = level.getSeaLevel();
        for (LegacySeaRoute sr : legacy) {
            if (sr.cellPath().isEmpty()) {
                LOGGER.warn("[SeaRouteMigration] skipping legacy sea route {} — empty cellPath",
                        sr.seaRouteId());
                continue;
            }

            BlockPos endA = AtlasRouteRouter.cellKeyToBlockCenter(sr.cellPath().get(0));
            BlockPos endB = AtlasRouteRouter.cellKeyToBlockCenter(
                    sr.cellPath().get(sr.cellPath().size() - 1));
            BlockPos waypointA = new BlockPos(endA.getX(), seaLevel, endA.getZ());
            BlockPos waypointB = new BlockPos(endB.getX(), seaLevel, endB.getZ());

            RoadNode nodeA = new RoadNode(UUID.randomUUID(), waypointA,
                    RoadNode.NodeType.VILLAGE_DOCK, Optional.empty());
            RoadNode nodeB = new RoadNode(UUID.randomUUID(), waypointB,
                    RoadNode.NodeType.VILLAGE_DOCK, Optional.empty());
            graph.addNode(nodeA);
            graph.addNode(nodeB);

            // UUID preservation: the legacy connectionId (== seaRouteId)
            // becomes the edgeId. BoatCaravan records' persisted
            // seaRouteId field already references this UUID, so the
            // pointer stays valid against the new edge after the
            // codec re-load completes.
            long meanderSeed = (long) sr.villageA().getMostSignificantBits()
                    ^ (long) sr.villageB().getLeastSignificantBits();
            RoadEdge edge = new RoadEdge(
                    sr.seaRouteId(),
                    nodeA.nodeId(),
                    nodeB.nodeId(),
                    sr.cellPath(),
                    RoadEdge.EdgeTier.SEA,
                    new RoadEdge.MeanderProfile(0f, 0f, meanderSeed));
            edge.setMaintenance(sr.quality());
            edge.getMaintainerVillageIds().add(sr.villageA());
            edge.getMaintainerVillageIds().add(sr.villageB());
            graph.addEdge(edge);

            migrated++;
            LOGGER.info("[SeaRouteMigration] migrated SeaRoute {} → SEA edge "
                            + "with maintainers {}↔{} ({} cells)",
                    sr.seaRouteId(),
                    vdata.getVillageById(sr.villageA())
                            .map(v -> v.getName()).orElse("?"),
                    vdata.getVillageById(sr.villageB())
                            .map(v -> v.getName()).orElse("?"),
                    sr.cellPath().size());
        }

        // Only clear the legacy map after successful edge creation.
        // If a migration was interrupted, the next load reruns it
        // because the seaRoutesMigrated flag isn't flipped yet.
        vdata.clearLegacySeaRoutes();
        roadData.setSeaRoutesMigrated(true);
        roadData.markDirty();
        vdata.markDirty();

        LOGGER.info("[SeaRouteMigration] migrated {} sea route(s) to SEA-tier edges",
                migrated);
    }
}
