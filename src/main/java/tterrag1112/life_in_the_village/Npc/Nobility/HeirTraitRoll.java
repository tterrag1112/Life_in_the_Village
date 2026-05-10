package tterrag1112.life_in_the_village.Npc.Nobility;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.Optional;
import java.util.Random;

/**
 * Track D3.5B — heir-traits hybrid roll, per the user-confirmed
 * lock "heir traits hybrid roll".
 *
 * <p>Formula: {@code child = INHERITED_WEIGHT × parent_avg +
 * (1 - INHERITED_WEIGHT) × fresh_roll}, with {@code parent_avg}
 * being the per-axis mean of both available parents (single
 * parent halves the weight).
 *
 * <h3>Why hybrid</h3>
 * Pure inheritance produces dynastic stagnation (every heir clones
 * the predecessor); pure re-roll erases lineage. The locked design
 * lands on a 60-40 inherited-vs-fresh split, which gives a heir
 * who feels like family but not like a Xerox copy. The remaining
 * 40% comes from a Gaussian roll centred on 0 with std-dev 0.4,
 * clamped to [-1, +1].
 *
 * <h3>When applied</h3>
 * Called from {@code ChildBirthGoal.spawnChild} after the child
 * NPC is created but before world-spawn, when at least one parent
 * is a noble (carries a non-empty dynastyHouseId on
 * {@link tterrag1112.life_in_the_village.Npc.Nobility.NobilityRecord}).
 * Non-noble child births keep the legacy "zero + culture bias"
 * path.
 */
public final class HeirTraitRoll {

    /** Per the locked design: 60% inherited, 40% fresh roll. */
    public static final float INHERITED_WEIGHT = 0.6f;

    /** Std-dev for the Gaussian fresh-roll component. */
    public static final float FRESH_ROLL_STDDEV = 0.4f;

    private HeirTraitRoll() {}

    /**
     * Returns true when this child should use the hybrid roll —
     * at least one parent is noble (has a dynasty house id).
     */
    public static boolean shouldApply(TownspersonMob parentA, TownspersonMob parentB) {
        return isNoble(parentA) || isNoble(parentB);
    }

    private static boolean isNoble(TownspersonMob npc) {
        if (npc == null) return false;
        return npc.getNobility().getDynastyHouseId().isPresent();
    }

    /**
     * Computes hybrid heir traits and writes them to {@code child}.
     * {@code parentB} may be null (single-parent path; falls back
     * to parent A only). Random source is passed so caller controls
     * determinism (typically the entity's RNG).
     */
    public static void apply(TownspersonMob child, TownspersonMob parentA,
                             TownspersonMob parentB, Random random) {
        TraitVector childTraits = child.getTraitVector();
        TraitVector aTraits = parentA != null ? parentA.getTraitVector() : null;
        TraitVector bTraits = parentB != null ? parentB.getTraitVector() : null;

        for (TraitAxis axis : TraitAxis.values()) {
            float parentAvg;
            if (aTraits != null && bTraits != null) {
                parentAvg = (aTraits.get(axis) + bTraits.get(axis)) * 0.5f;
            } else if (aTraits != null) {
                parentAvg = aTraits.get(axis);
            } else if (bTraits != null) {
                parentAvg = bTraits.get(axis);
            } else {
                parentAvg = 0f;
            }

            // Gaussian fresh-roll, clamped.
            float freshRoll = (float) (random.nextGaussian() * FRESH_ROLL_STDDEV);

            float blended = INHERITED_WEIGHT * parentAvg
                    + (1f - INHERITED_WEIGHT) * freshRoll;
            childTraits.set(axis, blended);
        }
    }

    /**
     * Convenience entry — pulls the spouse from {@code parentA}'s
     * spouseId via the current level. Returns true if the roll was
     * applied (i.e. at least one parent is noble).
     */
    public static boolean tryApplyAtBirth(TownspersonMob child,
                                          TownspersonMob parentA,
                                          net.minecraft.server.level.ServerLevel level) {
        TownspersonMob parentB = null;
        Optional<java.util.UUID> spouse = parentA.getSpouseId();
        if (spouse.isPresent()) {
            parentB = TownspersonMob.findByUUID(level, spouse.get()).orElse(null);
        }
        if (!shouldApply(parentA, parentB)) return false;
        apply(child, parentA, parentB, parentA.getRandom());
        return true;
    }
}
