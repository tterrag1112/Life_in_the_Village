package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.EnumSet;
import java.util.Set;

/**
 * Track E1 prompt-3 — building types eligible for primary binding.
 *
 * <p>A "lead" type is typically one-per-village in HAMLET / TOWN
 * (possibly 2-3 in CITY for {@code MARKET} / {@code INN}). When a
 * lead type appears in the strategy's {@code intendedBindings},
 * {@link NetworkPlanner} resolves the binding to an anchor and
 * emits a {@link PrimaryBinding}. Subsequent copies (CITY scale)
 * fall through to the general selector.
 *
 * <p>Non-lead types ({@code HOUSE}, {@code FARMHOUSE},
 * {@code STOCKPILE}, …) are not pre-bound; they distribute across
 * the network via normal frontage scoring.
 */
public final class LeadBuildingTypes {

    private static final Set<BuildingType> LEADS = EnumSet.of(
            BuildingType.TOWN_HALL,
            BuildingType.MARKET,
            BuildingType.INN,
            BuildingType.BLACKSMITH,
            BuildingType.BAKERY,
            BuildingType.MINE,
            BuildingType.WOODCUTTER,
            BuildingType.CHAPEL,
            BuildingType.SHRINE,
            BuildingType.TREASURY,
            BuildingType.STABLE,
            BuildingType.CASTLE);

    private LeadBuildingTypes() {}

    public static boolean isLead(BuildingType type) {
        return type != null && LEADS.contains(type);
    }
}
