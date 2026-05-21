package tterrag1112.life_in_the_village.Npc.Career;

/**
 * Phase 6.3.3.a — listener for profession-change requests.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li><strong>Idempotent</strong> — listeners may be called multiple
 *       times for logically-equivalent requests; do not perform
 *       irreversible side effects from inside the listener.</li>
 *   <li><strong>Non-blocking</strong> — listeners run synchronously on
 *       the server thread inside the request flow. Return promptly.</li>
 *   <li><strong>Return semantics</strong> — see
 *       {@link ProfessionChangeResult}. {@link ProfessionChangeResult.Rejected
 *       Rejected} halts the chain; {@link ProfessionChangeResult.Accepted
 *       Accepted} and {@link ProfessionChangeResult.DeferredToHandlerDecision
 *       DeferredToHandlerDecision} continue. Null is treated as Accepted.</li>
 *   <li><strong>Stateless preferred</strong> — predicates that consult
 *       NPC / world state are fine; mutating world state from a listener
 *       is a code smell (signal it via an event/scheduled task instead).</li>
 * </ul>
 */
@FunctionalInterface
public interface ProfessionChangeListener {
    ProfessionChangeResult onProfessionChangeRequested(ProfessionChangeRequest request);
}
