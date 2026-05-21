package tterrag1112.life_in_the_village.Guilds.Companies.Ai;

import java.util.UUID;

/**
 * Phase 6.3.3.c.6 — sealed result type for a succession resolution.
 *
 * <ul>
 *   <li>{@link HeirFound} — a successor was identified; caller seats
 *       them via {@code Business.setOwnership}.</li>
 *   <li>{@link TransferredToPool} — business is parked, awaiting
 *       reassignment from the village pool. No immediate dissolve;
 *       future content may reassign.</li>
 *   <li>{@link NoEligibleSuccessor} — every predicate declined; the
 *       caller takes the legacy UNDECIDED → 30-day → dissolve path.</li>
 * </ul>
 */
public sealed interface SuccessionResult
        permits SuccessionResult.HeirFound,
                SuccessionResult.TransferredToPool,
                SuccessionResult.NoEligibleSuccessor {

    record HeirFound(UUID uuid) implements SuccessionResult {}
    record TransferredToPool() implements SuccessionResult {
        public static final TransferredToPool INSTANCE = new TransferredToPool();
    }
    record NoEligibleSuccessor() implements SuccessionResult {
        public static final NoEligibleSuccessor INSTANCE = new NoEligibleSuccessor();
    }
}
