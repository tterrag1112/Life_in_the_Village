package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.SiteContext;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 Layer 4 orchestrator. Produces a {@link RoadNetwork} with the
 * spine, the per-building connectors, and the optional ring. Layer
 * 5 will hand the network off to the existing decoration pipeline.
 *
 * <p>The {@link BuildResult} additionally carries any overlap
 * warnings the generators recorded — used by the {@code /litv layout}
 * debug command. Warnings are advisory; Layer 4 doesn't repair them
 * (Layer 3 weights or Layer 2 spine direction are the right place
 * to tune).
 */
public final class RoadGraphBuilder {

    /** Spine corridor half-width for the spine-vs-building overlap
     *  warning. Matches the prompt's "2-block corridor". */
    private static final int SPINE_CORRIDOR_HALF = 2;

    private RoadGraphBuilder() {}

    public static BuildResult build(SiteContext ctx, PlacementResult placement,
                                    int villageRadius) {
        List<PlacedBuilding> placed = placement.placed();
        List<String> warnings = new ArrayList<>();

        Road spine = SpineGenerator.generate(ctx, villageRadius, placed);
        warnings.addAll(spineOverlapWarnings(spine, placed));

        ConnectorGenerator.Result conn = ConnectorGenerator.generate(
                spine, placed, ctx.culture());
        warnings.addAll(conn.warnings());

        RingGenerator.Result ringRes = RingGenerator.generate(
                ctx, villageRadius, placed, ctx.culture());
        warnings.addAll(ringRes.warnings());

        RoadNetwork network = new RoadNetwork(spine, List.copyOf(conn.connectors()),
                ringRes.ring());
        return new BuildResult(network, List.copyOf(warnings));
    }

    /** Buildings whose footprints sit inside the spine's corridor.
     *  TOWN_HALL is excluded since the spine is deliberately offset
     *  to graze it. */
    private static List<String> spineOverlapWarnings(Road spine,
                                                     List<PlacedBuilding> placed) {
        List<String> warnings = new ArrayList<>();
        if (!(spine.primitive() instanceof RoadPrimitive.StraightRoad sr)) {
            // CurvedRoad spine — sample-based corridor check would be
            // analogous; defer to a follow-up cycle if it proves needed.
            return warnings;
        }
        for (PlacedBuilding pb : placed) {
            if (pb.type() == BuildingType.TOWN_HALL) continue;
            if (RoadGeometry.footprintOverlapsCorridor(
                    pb, sr.from(), sr.to(), SPINE_CORRIDOR_HALF)) {
                warnings.add(pb.type().name() + " at "
                        + pb.centre().getX() + "," + pb.centre().getZ()
                        + " overlaps spine corridor (footprint "
                        + pb.footprint().width() + "x" + pb.footprint().length() + ")");
            }
        }
        return warnings;
    }

    public record BuildResult(RoadNetwork network, List<String> warnings) {}
}
