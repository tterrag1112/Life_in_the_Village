package tterrag1112.life_in_the_village.Village.Buildings;

import tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Building-intrinsic adjacency requirements.
 *
 * <h3>Why this exists separately from rule-stack adjacency</h3>
 * Some adjacency relationships are facts about a building, not a village
 * type. A miller needs water. A fisherman needs a coast. A lighthouse
 * needs an ocean view. These are properties of the building itself —
 * they don't change based on which village the building lives in.
 *
 * <p>Phase 4b's rule-stack adjacency rules continue to work for
 * village-type-level decisions ("this farming village places its mill
 * specifically on the south fork of the river"). This intrinsic spec
 * is the building-level baseline — used for every building of that
 * type, in every village, in every culture, unless a village-type rule
 * explicitly overrides it.
 *
 * <h3>Override behaviour</h3>
 * The planner consults rule-stack adjacency first (per village type),
 * then layers in any intrinsic requirements that are not already
 * specified for the same building type. A village type that wants its
 * fishermen to ignore the coast requirement can do so by adding an
 * adjacency rule with {@code optional: true}.
 *
 * <h3>Features supported</h3>
 * The {@code feature} string is the same vocabulary as
 * {@link tterrag1112.life_in_the_village.Village.Planning.Rules.RuleContext.AdjacencyReq}:
 * {@code river}, {@code coast}, {@code water}, {@code forest}.
 */
public final class BuildingAdjacencySpec {

    /** Empty spec — building has no intrinsic adjacency needs. */
    public static final BuildingAdjacencySpec EMPTY =
            new BuildingAdjacencySpec(Collections.emptyList());

    private final List<RuleContext.AdjacencyReq> requirements;

    public BuildingAdjacencySpec(List<RuleContext.AdjacencyReq> requirements) {
        this.requirements = List.copyOf(requirements);
    }

    public List<RuleContext.AdjacencyReq> getRequirements() {
        return requirements;
    }

    public boolean isEmpty() { return requirements.isEmpty(); }

    // =========================================================================
    // Builder
    // =========================================================================

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<RuleContext.AdjacencyReq> reqs = new ArrayList<>();

        /** Hard requirement: building is dropped if not satisfied. */
        public Builder requires(String feature, int maxDist) {
            reqs.add(new RuleContext.AdjacencyReq(feature, maxDist, true));
            return this;
        }

        /** Soft preference: building tries to honour it but is not dropped. */
        public Builder prefers(String feature, int maxDist) {
            reqs.add(new RuleContext.AdjacencyReq(feature, maxDist, false));
            return this;
        }

        public BuildingAdjacencySpec build() {
            return new BuildingAdjacencySpec(reqs);
        }
    }
}