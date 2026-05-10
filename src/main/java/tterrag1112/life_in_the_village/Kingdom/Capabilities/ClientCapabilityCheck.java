package tterrag1112.life_in_the_village.Kingdom.Capabilities;

import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.OfficeState;

/**
 * Track D3.3b — coarse client-side capability gate, used by the
 * GUI for grey-out tooltips. The server-side
 * {@link KingdomCapabilityEvaluator} runs the full competence
 * check (skill level vs office minLevel); the client doesn't have
 * NPC skill levels, so this helper only tests whether at least
 * one satisfier office is held (non-vacant).
 *
 * <p>Net effect: the GUI greys a button out only when ALL
 * satisfier offices are vacant — but the server still rejects
 * actions where the holders are under-skilled, surfacing the
 * reason via chat. This is intentional: a slightly-permissive
 * client gate prevents flickering when chunks load (NPC skill
 * data may not be locally cached) while the server enforces
 * truth at action time.
 */
public final class ClientCapabilityCheck {

    private ClientCapabilityCheck() {}

    /**
     * Returns true when at least one office that could satisfy
     * {@code capability} is currently held on the client-cached
     * {@link Kingdom}. Used to set
     * {@code Button#active = canExercise(...)}.
     */
    public static boolean canExercise(Kingdom kingdom, KingdomCapability capability) {
        if (kingdom == null || capability == null) return false;
        OfficeState offices = kingdom.getOffices();
        if (offices == null) return false;
        for (String officeId : KingdomCapabilityEvaluator.satisfiersFor(capability)) {
            var holding = offices.get(officeId).orElse(null);
            if (holding != null && !holding.isVacant()) return true;
        }
        return false;
    }

    /**
     * Returns the satisfier office id that's currently held, if
     * any, or null. Used for tooltips like
     * "Currently held by Chancellor".
     */
    public static String firstHeldSatisfier(Kingdom kingdom, KingdomCapability capability) {
        if (kingdom == null || capability == null) return null;
        OfficeState offices = kingdom.getOffices();
        if (offices == null) return null;
        for (String officeId : KingdomCapabilityEvaluator.satisfiersFor(capability)) {
            var holding = offices.get(officeId).orElse(null);
            if (holding != null && !holding.isVacant()) return officeId;
        }
        return null;
    }

    /**
     * Builds a tooltip line like "Requires King or Magistrate
     * (both vacant)" / "Authorised by Chancellor".
     */
    public static String buildTooltip(Kingdom kingdom, KingdomCapability capability) {
        String held = firstHeldSatisfier(kingdom, capability);
        if (held != null) {
            var def = OfficeRegistry.get(held);
            return "Authorised by " + (def != null ? def.displayName() : held);
        }
        var ids = KingdomCapabilityEvaluator.satisfiersFor(capability);
        StringBuilder sb = new StringBuilder("Requires ");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(i == ids.size() - 1 ? " or " : ", ");
            var def = OfficeRegistry.get(ids.get(i));
            sb.append(def != null ? def.displayName() : ids.get(i));
        }
        sb.append(" (all vacant)");
        return sb.toString();
    }
}
