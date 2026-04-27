package tterrag1112.life_in_the_village.Village.Roads.Events;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 10 — a single placed road event. The infrastructure carrier; specific
 * event content (NPCs, dialogs, unique structures) is added in later phases.
 *
 * <h3>Identity and ownership</h3>
 * Every event has a stable {@link #eventId}. An event is owned by either an
 * edge ({@link #containingEdgeId}) or a node ({@link #containingNodeId}) — the
 * other field may be empty depending on the event's category. Landmarks and
 * junction events tend to be node-owned; travelers and lone structures tend to
 * be edge-owned.
 *
 * <h3>Lifecycle</h3>
 * {@link #placedTick} records when the event came into being. {@link #expiresAtTick}
 * is empty for permanent events; if present, {@link EventLifecycleSystem} will
 * call the type's factory's {@code despawn} once the current tick crosses it.
 *
 * <h3>Property bag</h3>
 * {@link #properties} is intentionally a {@code Map<String,String>}: simple,
 * codec-cheap, extensible. Event factories can stash type-specific state here
 * (e.g. {@code "merchantName" -> "Elaria"}). Complex state should be encoded as
 * multiple keys rather than serialised JSON.
 */
public record RoadEvent(
        UUID eventId,
        String typeId,
        BlockPos position,
        Optional<UUID> containingEdgeId,
        Optional<UUID> containingNodeId,
        long placedTick,
        Optional<Long> expiresAtTick,
        Map<String, String> properties,
        Optional<UUID> historyRefId
) {

    public boolean isPermanent() { return expiresAtTick.isEmpty(); }

    public boolean hasExpired(long currentTick) {
        return expiresAtTick.map(exp -> currentTick >= exp).orElse(false);
    }

    /** Convenience: defensive-copy properties on construction so callers can mutate freely. */
    public RoadEvent withProperty(String key, String value) {
        Map<String, String> next = new HashMap<>(properties);
        next.put(key, value);
        return new RoadEvent(eventId, typeId, position, containingEdgeId, containingNodeId,
                placedTick, expiresAtTick, next, historyRefId);
    }

    public RoadEvent withHistoryRef(UUID historyId) {
        return new RoadEvent(eventId, typeId, position, containingEdgeId, containingNodeId,
                placedTick, expiresAtTick, properties, Optional.of(historyId));
    }

    // =========================================================================
    // Codec
    // =========================================================================

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<RoadEvent> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("eventId").forGetter(RoadEvent::eventId),
            Codec.STRING.fieldOf("typeId").forGetter(RoadEvent::typeId),
            BlockPos.CODEC.fieldOf("position").forGetter(RoadEvent::position),
            UUID_CODEC.optionalFieldOf("containingEdgeId").forGetter(RoadEvent::containingEdgeId),
            UUID_CODEC.optionalFieldOf("containingNodeId").forGetter(RoadEvent::containingNodeId),
            Codec.LONG.fieldOf("placedTick").forGetter(RoadEvent::placedTick),
            Codec.LONG.optionalFieldOf("expiresAtTick").forGetter(RoadEvent::expiresAtTick),
            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                    .optionalFieldOf("properties", new HashMap<>())
                    .forGetter(RoadEvent::properties),
            UUID_CODEC.optionalFieldOf("historyRefId").forGetter(RoadEvent::historyRefId)
    ).apply(i, RoadEvent::new));
}
