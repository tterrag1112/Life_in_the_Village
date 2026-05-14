package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import net.minecraft.core.BlockPos;

/**
 * Track E1 prompt-3 — a graph node in the village road network.
 *
 * <p>Node ids are stable strings ({@code "anchor:a0"},
 * {@code "gateway:PRIMARY"}, {@code "junction:0"},
 * {@code "synthetic:0"}). They appear verbatim in
 * {@link NetworkEdge#fromNodeId()} and {@link NetworkEdge#toNodeId()}
 * so the dump can be inspected without resolving by index.
 *
 * @param id     stable identifier (see class doc for format)
 * @param pos    world-space position of the node
 * @param kind   taxonomic tag (see {@link NodeKind})
 */
public record NetworkNode(String id, BlockPos pos, NodeKind kind) {
    public NetworkNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("NetworkNode id must be non-blank");
        }
        if (pos == null) {
            throw new IllegalArgumentException("NetworkNode pos required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("NetworkNode kind required");
        }
    }
}
