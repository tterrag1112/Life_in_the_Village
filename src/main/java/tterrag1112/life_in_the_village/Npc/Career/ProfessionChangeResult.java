package tterrag1112.life_in_the_village.Npc.Career;

import net.minecraft.network.chat.Component;

/**
 * Phase 6.3.3.a — sealed result type for a profession change request.
 *
 * <p>Listeners return one of:
 * <ul>
 *   <li>{@link Accepted} — listener has no objection; processing
 *       continues to the next listener and, if none reject, to the
 *       leaf {@code setProfession} call.</li>
 *   <li>{@link Rejected} — veto. The facade halts and returns the
 *       rejection; the leaf is not called.</li>
 *   <li>{@link DeferredToHandlerDecision} — "the legacy handler at
 *       this callsite is internally authoritative; do not second-guess
 *       it". Treated as Accepted for the purpose of continuing the
 *       chain, but communicates intent to other listeners that this
 *       change is already validated upstream.</li>
 * </ul>
 */
public sealed interface ProfessionChangeResult
        permits ProfessionChangeResult.Accepted,
                ProfessionChangeResult.Rejected,
                ProfessionChangeResult.DeferredToHandlerDecision {

    boolean shouldProceed();

    record Accepted() implements ProfessionChangeResult {
        public static final Accepted INSTANCE = new Accepted();
        @Override public boolean shouldProceed() { return true; }
    }

    record Rejected(Component reason) implements ProfessionChangeResult {
        @Override public boolean shouldProceed() { return false; }
    }

    record DeferredToHandlerDecision() implements ProfessionChangeResult {
        public static final DeferredToHandlerDecision INSTANCE = new DeferredToHandlerDecision();
        @Override public boolean shouldProceed() { return true; }
    }
}
