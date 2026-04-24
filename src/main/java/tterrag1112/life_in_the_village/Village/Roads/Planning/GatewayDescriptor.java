package tterrag1112.life_in_the_village.Village.Roads.Planning;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadNode;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight descriptor emitted by {@link tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe#describeGateways}
 * before the village road graph is populated.
 *
 * <p>Each descriptor becomes one {@link VillageRoadNode} of type GATEWAY in the village graph.
 * The PRIMARY descriptor must always be present; it corresponds to the legacy
 * single-dock position so backward compatibility is preserved.
 *
 * <p>Arm endpoint is computed later by
 * {@link GatewayPopulator} once the world is available for height sampling.
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

    // =========================================================================
    // Shared derivation from layout
    // =========================================================================

    /**
     * Derives gateway descriptors from the gate positions recorded in
     * {@code pctx.layout} during {@code compose()}. Used by the default
     * {@link tterrag1112.life_in_the_village.Village.Planning.Primitives.ShapeRecipe#describeGateways}
     * and by the explicit overrides in LINEAR / ROADSIDE / CHAIN recipes.
     *
     * <p>The main gate endpoint becomes the PRIMARY descriptor; all other
     * gate positions become SIDE descriptors.  If no gate positions were
     * added, a single PRIMARY at the village center is returned.
     *
     * <p>Direction is computed as the 8-way compass direction from village
     * center toward the gate position, using {@code Math.atan2(dz, dx)}
     * with Minecraft's coordinate convention (+X = East, +Z = South).
     */
    public static List<GatewayDescriptor> deriveFromLayout(PlanContext pctx) {
        net.minecraft.core.BlockPos center = pctx.layout.getCenter();
        java.util.List<net.minecraft.core.BlockPos> gates = pctx.layout.getGatePositions();
        net.minecraft.core.BlockPos mainGate = pctx.layout.getMainGateEndpoint();

        if (gates.isEmpty()) {
            net.minecraft.core.BlockPos primary = mainGate != null ? mainGate : center;
            return List.of(new GatewayDescriptor(
                    primary,
                    directionFromTo(center, primary),
                    VillageRoadNode.GatewayRole.PRIMARY));
        }

        List<GatewayDescriptor> result = new ArrayList<>();
        boolean hasExplicitPrimary = false;

        for (net.minecraft.core.BlockPos gate : gates) {
            boolean isPrimary = gate.equals(mainGate);
            if (isPrimary) hasExplicitPrimary = true;
            result.add(new GatewayDescriptor(
                    gate,
                    directionFromTo(center, gate),
                    isPrimary ? VillageRoadNode.GatewayRole.PRIMARY : VillageRoadNode.GatewayRole.SIDE));
        }

        // Ensure at least one PRIMARY
        if (!hasExplicitPrimary && !result.isEmpty()) {
            GatewayDescriptor first = result.get(0);
            result.set(0, new GatewayDescriptor(first.position(), first.outwardDirection(),
                    VillageRoadNode.GatewayRole.PRIMARY));
        }
        return result;
    }

    private static VillageRoadNode.OutwardDirection directionFromTo(
            net.minecraft.core.BlockPos from, net.minecraft.core.BlockPos to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < 0.001 && Math.abs(dz) < 0.001) {
            return VillageRoadNode.OutwardDirection.EAST; // degenerate — same position
        }
        return VillageRoadNode.OutwardDirection.fromAngle(Math.atan2(dz, dx));
    }
}
