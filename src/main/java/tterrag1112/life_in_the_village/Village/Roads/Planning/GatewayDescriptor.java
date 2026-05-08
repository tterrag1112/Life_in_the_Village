package tterrag1112.life_in_the_village.Village.Roads.Planning;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;

/**
 * Lightweight descriptor for a gateway position in the village road graph.
 *
 * <p>Each descriptor becomes one {@link VillageRoadNode} of type GATEWAY in
 * the village graph. The PRIMARY descriptor must always be present; it
 * corresponds to the legacy single-dock position so backward compatibility
 * is preserved.
 *
 * <p>Arm endpoint is computed later by {@code GatewayPopulator} once the
 * world is available for height sampling.
 */
public record GatewayDescriptor(
        BlockPos position,
        VillageRoadNode.OutwardDirection outwardDirection,
        VillageRoadNode.GatewayRole role
) {

    public static final Codec<GatewayDescriptor> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("position")
                    .forGetter(GatewayDescriptor::position),
            VillageRoadNode.OutwardDirection.CODEC.fieldOf("outwardDirection")
                    .forGetter(GatewayDescriptor::outwardDirection),
            VillageRoadNode.GatewayRole.CODEC.fieldOf("role")
                    .forGetter(GatewayDescriptor::role)
    ).apply(i, GatewayDescriptor::new));

    // Track A1b — deriveFromLayout(PlanContext) was removed alongside
    // V1 ShapeRecipe.describeGateways. The V2 planner emits gateway
    // descriptors directly when wiring its road graph.
}
