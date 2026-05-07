package tterrag1112.life_in_the_village.Village.Planning.V2.Layer5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacedBuilding;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.PlacementResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * V2 Layer 5 — post-terrain viability gate.
 *
 * <p>Runs after {@link TerrainAdapter} has moved unadaptable
 * buildings from {@code placed} into {@code dropped}. Layer 3's
 * {@link PlacementResult#villageViable()} flag is computed at
 * planning time and does not see terrain drops, so a village
 * that loses its TOWN_HALL (or its remaining building count)
 * to terrain still reports {@code villageViable=true}.
 *
 * <p>This validator inspects the post-terrain placement and
 * fails the spawn if:
 * <ul>
 *   <li>TOWN_HALL is missing from {@code placed},</li>
 *   <li>placed count is below the tier's minimum, or</li>
 *   <li>distinct building types is below the tier's diversity
 *       minimum.</li>
 * </ul>
 *
 * <p>Failure reasons are returned in
 * {@link ViabilityCheck#failureReasons()} for the caller to log
 * and to thread into the spawn-aborted reason string.
 */
public final class ViabilityValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ViabilityValidator.class);

    private ViabilityValidator() {}

    public static ViabilityCheck validate(PlacementResult placement, ViabilityTier tier) {
        List<String> reasons = new ArrayList<>();

        boolean townHallPresent = false;
        Set<BuildingType> distinct = EnumSet.noneOf(BuildingType.class);
        for (PlacedBuilding pb : placement.placed()) {
            if (pb.type() == BuildingType.TOWN_HALL) townHallPresent = true;
            distinct.add(pb.type());
        }
        int placedCount = placement.placed().size();
        int distinctCount = distinct.size();

        int minCount = minBuildingCountFor(tier);
        int minDiversity = minDiversityFor(tier);

        if (!townHallPresent) {
            reasons.add("missing TOWN_HALL after terrain drops");
        }
        if (placedCount < minCount) {
            reasons.add("only " + placedCount + " buildings spawned, tier "
                    + tier + " requires " + minCount);
        }
        if (distinctCount < minDiversity) {
            reasons.add("only " + distinctCount + " distinct building types, tier "
                    + tier + " requires " + minDiversity);
        }

        boolean viable = reasons.isEmpty();
        LOGGER.info("viability check: TOWN_HALL={} placed={} (min={}) distinct={} (min={}) result={}",
                townHallPresent ? "yes" : "no",
                placedCount, minCount, distinctCount, minDiversity,
                viable ? "VIABLE" : "NOT VIABLE");
        if (!viable) {
            for (String r : reasons) LOGGER.info("  not viable: {}", r);
        }
        return new ViabilityCheck(viable, List.copyOf(reasons));
    }

    private static int minBuildingCountFor(ViabilityTier tier) {
        return switch (tier) {
            case CITY -> 10;
            case TOWN -> 5;
            case HAMLET -> 2;
            case OUTPOST -> 1;
            case UNVIABLE -> 1;
        };
    }

    private static int minDiversityFor(ViabilityTier tier) {
        return switch (tier) {
            case CITY -> 6;
            case TOWN -> 4;
            case HAMLET -> 2;
            case OUTPOST -> 1;
            case UNVIABLE -> 1;
        };
    }

    public record ViabilityCheck(boolean viable, List<String> failureReasons) {}
}
