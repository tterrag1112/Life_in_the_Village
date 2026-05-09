package tterrag1112.life_in_the_village.Village.Roads.Poi;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.UUID;

/**
 * Track C3.2 (Phase 12) — record of a worldgen-placed structure that the
 * {@code PoiDiscoveryTickSystem} has registered as a candidate for a
 * footpath-tier subroad.
 *
 * <p>One {@code DiscoveredPoi} is created per detected structure. The
 * {@code nodeId} field is populated when the {@code PoiSubroadPlanner}
 * has successfully placed a {@code POI_STUB} node in the world graph;
 * the {@code edgeId} field is populated when a real {@code RoadEdge}
 * (LOCAL-tier) connects the stub to nearby graph infrastructure.
 *
 * <p>An ISOLATED POI is one with a {@code nodeId} but no {@code edgeId} —
 * either the planner found no graph infrastructure within the search
 * radius, or routing failed for every candidate.
 *
 * <h3>Status interpretation</h3>
 * <ul>
 *   <li>{@code nodeId} empty, {@code edgeId} empty —
 *       {@link Status#PENDING}: discovered but planner has not run yet.</li>
 *   <li>{@code nodeId} present, {@code edgeId} empty —
 *       {@link Status#ISOLATED}: stub created, no edge.</li>
 *   <li>{@code nodeId} present, {@code edgeId} present —
 *       {@link Status#CONNECTED}: stub + edge in the world graph.</li>
 * </ul>
 *
 * @param id                discovery id (used as map key on
 *                          {@code WorldRoadSavedData})
 * @param structureType     full structure id, e.g.
 *                          {@code minecraft:pillager_outpost}
 * @param position          surface-snapped centre of the structure piece
 *                          that triggered discovery
 * @param nodeId            POI_STUB node UUID once the planner has placed it
 * @param edgeId            connector edge UUID once routing succeeded
 * @param discoveredAtTick  server tick when the discovery system first saw it
 */
public record DiscoveredPoi(
        UUID id,
        Identifier structureType,
        BlockPos position,
        Optional<UUID> nodeId,
        Optional<UUID> edgeId,
        long discoveredAtTick
) {

    public enum Status { PENDING, ISOLATED, CONNECTED }

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<DiscoveredPoi> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(DiscoveredPoi::id),
            Identifier.CODEC.fieldOf("structureType")
                    .forGetter(DiscoveredPoi::structureType),
            BlockPos.CODEC.fieldOf("position").forGetter(DiscoveredPoi::position),
            UUID_CODEC.optionalFieldOf("nodeId").forGetter(DiscoveredPoi::nodeId),
            UUID_CODEC.optionalFieldOf("edgeId").forGetter(DiscoveredPoi::edgeId),
            Codec.LONG.optionalFieldOf("discoveredAtTick", 0L)
                    .forGetter(DiscoveredPoi::discoveredAtTick)
    ).apply(i, DiscoveredPoi::new));

    public Status status() {
        if (nodeId.isEmpty()) return Status.PENDING;
        if (edgeId.isEmpty()) return Status.ISOLATED;
        return Status.CONNECTED;
    }

    public DiscoveredPoi withNodeId(UUID newNodeId) {
        return new DiscoveredPoi(id, structureType, position,
                Optional.of(newNodeId), edgeId, discoveredAtTick);
    }

    public DiscoveredPoi withEdgeId(UUID newEdgeId) {
        return new DiscoveredPoi(id, structureType, position,
                nodeId, Optional.of(newEdgeId), discoveredAtTick);
    }

    public DiscoveredPoi clearEdgeId() {
        return new DiscoveredPoi(id, structureType, position,
                nodeId, Optional.empty(), discoveredAtTick);
    }
}
