package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;

import java.util.Optional;

/**
 * Phase 6.3.3.c.6 — pluggable predicate for resolving a Business's
 * next owner. Functional interface; predicates compose via
 * {@link SuccessionRegistry} into a stack that's walked in priority
 * order until one returns non-empty.
 *
 * <p>Pattern parallels 6.3.2.d's {@code OfficeEligibilityPredicate}
 * for office seating — there the registry stacks AND-composed
 * predicates per (culture, office); here it stacks OR-composed
 * predicates per (culture, businessType) where the first non-empty
 * result wins.
 */
@FunctionalInterface
public interface SuccessionPredicate {

    /**
     * @return the heir UUID if this predicate can resolve a successor;
     *         {@link Optional#empty()} to defer to the next predicate
     *         in the stack
     */
    Optional<SuccessionResult> resolve(Business business, ServerLevel level);
}
