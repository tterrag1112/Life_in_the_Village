package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.UUID;

/**
 * A named point in the world road graph. Nodes are the endpoints and
 * junctions that edges connect. They are immutable — positional changes
 * create a new node rather than mutating an existing one.
 *
 * <h3>Old Realm context</h3>
 * {@code GREAT_ROAD_ANCHOR} and {@code WAYSTATION} nodes originate from
 * Old Realm infrastructure. All other types are placed by the current age's
 * kingdoms and villages. Great-road nodes carry no kingdom affinity.
 */
public record RoadNode(
        UUID nodeId,
        BlockPos position,
        NodeType type,
        Optional<UUID> kingdomAffinity
) {

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
                    .forGetter(RoadNode::kingdomAffinity)
    ).apply(i, RoadNode::new));
}
