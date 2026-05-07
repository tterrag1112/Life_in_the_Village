package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Culture.Culture;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * V2 Layer 4 ring road. CITY and TOWN tiers may add a {@link
 * RoadPrimitive.Ring} between the civic centre and the outskirts;
 * HAMLET and OUTPOST never do.
 *
 * <p>Sanity check: if any building footprint intersects the ring's
 * corridor (radius ± 2 blocks), the ring is dropped — Layer 4 doesn't
 * compensate. The ring is optional character; missing one is fine.
 */
public final class RingGenerator {

    /** Fraction of village radius for the ring's actual radius —
     *  between civic centre and outskirts. */
    private static final double RING_RADIUS_FRACTION = 0.65;
    /** Half-width of the ring's avoidance corridor (blocks). */
    private static final int RING_CORRIDOR_HALF = 2;
    /** Drift on the ring (subtle wobble). */
    private static final double RING_DRIFT = 1.0;

    private RingGenerator() {}

    public static Result generate(SiteContext ctx, int villageRadius,
                                  List<PlacedBuilding> placed, Culture culture) {
        List<String> warnings = new ArrayList<>();
        ViabilityTier tier = ctx.tier();
        if (tier != ViabilityTier.CITY && tier != ViabilityTier.TOWN) {
            return new Result(Optional.empty(), warnings);
        }

        BlockPos centre = ctx.anchor();
        int ringRadius = (int) Math.round(villageRadius * RING_RADIUS_FRACTION);

        for (PlacedBuilding pb : placed) {
            if (RoadGeometry.footprintOverlapsRingCorridor(
                    pb, centre, ringRadius, RING_CORRIDOR_HALF)) {
                warnings.add("ring not viable — " + pb.type().name()
                        + "'s footprint sits in the ring corridor at radius "
                        + ringRadius);
                return new Result(Optional.empty(), warnings);
            }
        }

        RoadPrimitive primitive = new RoadPrimitive.Ring(
                centre, ringRadius, RING_DRIFT, RoadTier.RING_ROAD.v1Tier());
        Road ring = new Road(RoadTier.RING_ROAD, primitive, culture.roadMaterial());
        return new Result(Optional.of(ring), warnings);
    }

    public record Result(Optional<Road> ring, List<String> warnings) {}
}
