package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An undirected edge in a village's internal road graph.
 *
 * <p>Edges connect two {@link VillageRoadNode}s. {@code fromNodeId} and
 * {@code toNodeId} are interchangeable endpoints — traversal is allowed in
 * either direction.
 *
 * <p>{@code cellPath} holds block-granularity positions along the edge
 * (not atlas-cell granularity). Village roads are short so block positions
 * are used directly; a typical edge is 10–80 blocks long.
 *
 * <p>{@code isTraversable} is reserved. Always {@code true} in this slice.
 * Future slices may set it {@code false} for blocked or damaged sections.
 */
public record VillageRoadEdge(
        UUID edgeId,
        UUID fromNodeId,
        UUID toNodeId,
        List<BlockPos> cellPath,
        EdgeCharacter character,
        boolean isTraversable
) {

    // =========================================================================
    // Edge character
    // =========================================================================

    public enum EdgeCharacter {
        /** Main thoroughfare; wider and more prominent. */
        MAIN_STREET,
        /** Connecting path between buildings; modest. */
        SIDE_PATH,
        /** Spans from one gateway to another through the village. */
        THROUGH_VILLAGE;

        public static final Codec<EdgeCharacter> CODEC =
                Codec.STRING.xmap(EdgeCharacter::valueOf, EdgeCharacter::name);
    }

    // =========================================================================
    // Codec
    // =========================================================================

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<VillageRoadEdge> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("edgeId")
                    .forGetter(VillageRoadEdge::edgeId),
            UUID_CODEC.fieldOf("fromNodeId")
                    .forGetter(VillageRoadEdge::fromNodeId),
            UUID_CODEC.fieldOf("toNodeId")
                    .forGetter(VillageRoadEdge::toNodeId),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("cellPath", new ArrayList<>())
                    .forGetter(VillageRoadEdge::cellPath),
            EdgeCharacter.CODEC.fieldOf("character")
                    .forGetter(VillageRoadEdge::character),
            Codec.BOOL.optionalFieldOf("isTraversable", true)
                    .forGetter(VillageRoadEdge::isTraversable)
    ).apply(i, VillageRoadEdge::new));

    // =========================================================================
    // Convenience
    // =========================================================================

    /** Approximate edge length in blocks, based on the cell path. */
    public int length() {
        if (cellPath.isEmpty()) return 0;
        int total = 0;
        for (int i = 1; i < cellPath.size(); i++) {
            BlockPos a = cellPath.get(i - 1);
            BlockPos b = cellPath.get(i);
            int dx = b.getX() - a.getX();
            int dz = b.getZ() - a.getZ();
            total += (int) Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    /** Returns true if this edge connects either endpoint to the given node id. */
    public boolean isIncidentTo(UUID nodeId) {
        return fromNodeId.equals(nodeId) || toNodeId.equals(nodeId);
    }

    public static VillageRoadEdge create(UUID from, UUID to,
                                          List<BlockPos> path, EdgeCharacter character) {
        return new VillageRoadEdge(UUID.randomUUID(), from, to,
                new ArrayList<>(path), character, true);
    }
}
