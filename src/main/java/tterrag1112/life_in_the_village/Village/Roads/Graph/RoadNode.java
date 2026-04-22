package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A named point in the world road graph. Nodes are the endpoints and
 * junctions that edges connect.
 *
 * <p>Converted from record to class to support mutable {@link #decorationPositions}
 * tracking, while keeping the same constructor signature and accessor method names
 * so all existing call sites remain unchanged.
 *
 * <h3>Old Realm context</h3>
 * {@code GREAT_ROAD_ANCHOR} and {@code WAYSTATION} nodes originate from
 * Old Realm infrastructure. All other types are placed by the current age's
 * kingdoms and villages. Great-road nodes carry no kingdom affinity.
 */
public class RoadNode {

    public enum NodeType {
        /** Anchor point on an Old Realm great road. No kingdom affinity. */
        GREAT_ROAD_ANCHOR,
        /** Junction where a trunk road branches or joins another. */
        TRUNK_JUNCTION,
        /** The point where a village's connector arm meets the wider network. */
        VILLAGE_DOCK,
        /** Stub node for a dungeon, ruin, or shrine sub-road (Phase 12). */
        POI_STUB,
        /** Dead-end node; may exist without any live edges during lifecycle transitions. */
        TERMINUS,
        /** Kingdom border checkpoint on a trunk or great road (Phase 8d). */
        TOLL_GATE,
        /** Rest-stop node along a great road (Phase 8c). */
        WAYSTATION;

        public static final Codec<NodeType> CODEC =
                Codec.STRING.xmap(NodeType::valueOf, NodeType::name);
    }

    // ── Codec ────────────────────────────────────────────────────────────────

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<RoadNode> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("nodeId")
                    .forGetter(RoadNode::nodeId),
            BlockPos.CODEC.fieldOf("position")
                    .forGetter(RoadNode::position),
            NodeType.CODEC.fieldOf("type")
                    .forGetter(RoadNode::type),
            UUID_CODEC.optionalFieldOf("kingdomAffinity")
                    .forGetter(RoadNode::kingdomAffinity),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("decorationPositions", new ArrayList<>())
                    .forGetter(n -> n.decorationPositions)
    ).apply(i, RoadNode::fromCodec));

    private static RoadNode fromCodec(UUID nodeId, BlockPos position, NodeType type,
                                      Optional<UUID> kingdomAffinity,
                                      List<BlockPos> decorationPositions) {
        RoadNode n = new RoadNode(nodeId, position, type, kingdomAffinity);
        n.decorationPositions = new ArrayList<>(decorationPositions);
        return n;
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    private final UUID nodeId;
    private final BlockPos position;
    private final NodeType type;
    private final Optional<UUID> kingdomAffinity;

    /** Block positions of all decoration blocks placed at this node (persistent). */
    private List<BlockPos> decorationPositions;

    // ── Constructor ──────────────────────────────────────────────────────────

    /** Canonical constructor — matches the old record constructor signature exactly. */
    public RoadNode(UUID nodeId, BlockPos position, NodeType type, Optional<UUID> kingdomAffinity) {
        this.nodeId           = nodeId;
        this.position         = position;
        this.type             = type;
        this.kingdomAffinity  = kingdomAffinity;
        this.decorationPositions = new ArrayList<>();
    }

    // ── Accessors (same names as the old record) ─────────────────────────────

    public UUID           nodeId()           { return nodeId; }
    public BlockPos       position()         { return position; }
    public NodeType       type()             { return type; }
    public Optional<UUID> kingdomAffinity()  { return kingdomAffinity; }

    // ── Decoration tracking ──────────────────────────────────────────────────

    public List<BlockPos> getDecorationPositions() { return decorationPositions; }

    public void addDecorationPosition(BlockPos pos) {
        decorationPositions.add(pos.immutable());
    }

    public void clearDecorationPositions() { decorationPositions.clear(); }

    // ── Standard object methods ──────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoadNode other)) return false;
        return nodeId.equals(other.nodeId);
    }

    @Override
    public int hashCode() { return nodeId.hashCode(); }

    @Override
    public String toString() {
        return "RoadNode[nodeId=" + nodeId + ", position=" + position + ", type=" + type + "]";
    }
}
