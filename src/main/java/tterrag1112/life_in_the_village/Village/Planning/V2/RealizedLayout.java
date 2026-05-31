package tterrag1112.life_in_the_village.Village.Planning.V2;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadNetwork;

import java.util.List;

/**
 * Layout rework Phase 3a — the V2-native realisation record handed to
 * the post-placement consumers ({@link
 * tterrag1112.life_in_the_village.Village.Village#applyLayout},
 * {@code GatewayPopulator.populate}, {@code
 * VillageDecorator.decorateVillage}, {@code DecorationPass.run}).
 *
 * <p>It replaces the synthetic V1 {@code VillageLayout} the {@link
 * V2VillageSpawnerAdapter} used to build for the same consumers. The
 * fields here are exactly the live consumer surface the read-only
 * audit established — nothing speculative:</p>
 *
 * <ul>
 *   <li>{@code center} / {@code townSquarePos} — the V2 site anchor.</li>
 *   <li>{@code townSquareRadius} — the adapter's fallback plaza
 *       half-extent (the synth used the same constant).</li>
 *   <li>{@code mainGateEndpoint} — the V2 spine end (nullable, exactly
 *       as the synth left it when no spine end existed).</li>
 *   <li>{@code gatePositions} — spine end + spine start + every
 *       cross-street outer endpoint, the multi-gateway source {@code
 *       GatewayPopulator} reads.</li>
 *   <li>{@code ring1Radius} / {@code ring2Radius} — from {@code
 *       LayoutDensityProfile.forLevel}.</li>
 *   <li>{@code roadNetwork} — the V2 {@link RoadNetwork} (D1(b)). Not
 *       consumed for decoration yet; it is the future centerline
 *       source for road-side decoration (see {@code
 *       DecorationSlotEmitter.emitRoadSide}).</li>
 * </ul>
 */
public record RealizedLayout(
        BlockPos center,
        BlockPos townSquarePos,
        int townSquareRadius,
        @Nullable BlockPos mainGateEndpoint,
        List<BlockPos> gatePositions,
        int ring1Radius,
        int ring2Radius,
        RoadNetwork roadNetwork) {

    public RealizedLayout {
        gatePositions = gatePositions == null
                ? List.of()
                : List.copyOf(gatePositions);
    }
}
