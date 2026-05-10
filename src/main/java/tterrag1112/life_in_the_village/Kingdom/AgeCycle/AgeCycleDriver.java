package tterrag1112.life_in_the_village.Kingdom.AgeCycle;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Kingdom.Audience.KingdomNewsfeed;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEvent;
import tterrag1112.life_in_the_village.Kingdom.Events.KingdomEventBus;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomModifier;
import tterrag1112.life_in_the_village.Kingdom.Rebellion.CollapseEngine;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.ArrayList;

/**
 * Track D3.6.6 — daily age-cycle evaluator.
 *
 * <p>Per the locked design (CC's call on tuning, design pass e):
 * Kingdoms transition through {@link KingdomAgeState} driven by
 * age + succession count + ruler legitimacy. Per-state modifiers
 * apply via D1's modifier system (tagged
 * {@code age_cycle.<state>}).
 *
 * <h3>Transition rules (default; per-culture overrides flagged
 * for follow-up)</h3>
 * <ul>
 *   <li>FOUNDING_ERA → MATURE: kingdom age ≥
 *       {@link #MATURE_AGE_DAYS} AND successionCount ≥
 *       {@link #MATURE_SUCCESSION_THRESHOLD}.</li>
 *   <li>MATURE → DECADENT: kingdom age ≥
 *       {@link #DECADENT_AGE_DAYS} AND successionCount ≥
 *       {@link #DECADENT_SUCCESSION_THRESHOLD} AND
 *       effective legitimacy ≤ {@link #DECADENT_LEGITIMACY_THRESHOLD}.</li>
 *   <li>DECADENT → MATURE (renewal): effective legitimacy ≥
 *       {@link #RENEWAL_LEGITIMACY_THRESHOLD} AND ruler is
 *       sanctified
 *       (modifier {@code religion.sanctified} present).</li>
 * </ul>
 *
 * <h3>Determinism</h3>
 * Transitions are deterministic from kingdom state — no RNG.
 *
 * <h3>Skipped kingdoms</h3>
 * Collapsed kingdoms ({@code kingdom.collapsed} modifier) skip
 * evaluation; their age state freezes at whatever it was on
 * collapse.
 */
public final class AgeCycleDriver {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final long MATURE_AGE_DAYS = 90L;
    public static final int  MATURE_SUCCESSION_THRESHOLD = 1;

    public static final long DECADENT_AGE_DAYS = 360L;
    public static final int  DECADENT_SUCCESSION_THRESHOLD = 4;
    public static final int  DECADENT_LEGITIMACY_THRESHOLD = 45;

    public static final int  RENEWAL_LEGITIMACY_THRESHOLD = 75;

    private AgeCycleDriver() {}

    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom kingdom : new ArrayList<>(data.getAllKingdoms())) {
            if (kingdom.hasModifierWithId(CollapseEngine.COLLAPSED_MODIFIER_ID)) continue;
            KingdomAgeState current = currentState(kingdom);
            KingdomAgeState target = computeTarget(kingdom, current, tick);
            if (target != current) {
                transition(kingdom, current, target, tick);
            } else if (current != null && !kingdom.hasModifierWithId(current.modifierId())) {
                // Modifier was lost (expired or not yet stamped); restamp it.
                stampStateModifier(kingdom, current, tick);
            }
        }
    }

    /**
     * Reads the current age state from the kingdom's modifier set.
     * Returns null if no age-cycle modifier is present (fresh
     * kingdom; first-tick stamping picks FOUNDING_ERA).
     */
    public static KingdomAgeState currentState(Kingdom kingdom) {
        for (KingdomAgeState s : KingdomAgeState.values()) {
            if (kingdom.hasModifierWithId(s.modifierId())) return s;
        }
        return null;
    }

    /** Computes the age state the kingdom should be in given its data. */
    public static KingdomAgeState computeTarget(Kingdom kingdom,
                                                KingdomAgeState current,
                                                long tick) {
        long ageDays = (tick - kingdom.getFoundingTick()) / 24000L;
        int successions = kingdom.getHistory().getSuccessionCount();
        int effectiveLegitimacy = kingdom.getLegitimacy() + kingdom.legitimacyModifierSum();

        // Fresh kingdom — start in FOUNDING_ERA.
        if (current == null) return KingdomAgeState.FOUNDING_ERA;

        return switch (current) {
            case FOUNDING_ERA -> {
                if (ageDays >= MATURE_AGE_DAYS
                        && successions >= MATURE_SUCCESSION_THRESHOLD) {
                    yield KingdomAgeState.MATURE;
                }
                yield KingdomAgeState.FOUNDING_ERA;
            }
            case MATURE -> {
                if (ageDays >= DECADENT_AGE_DAYS
                        && successions >= DECADENT_SUCCESSION_THRESHOLD
                        && effectiveLegitimacy <= DECADENT_LEGITIMACY_THRESHOLD) {
                    yield KingdomAgeState.DECADENT;
                }
                yield KingdomAgeState.MATURE;
            }
            case DECADENT -> {
                // Renewal path: high legitimacy + sanctified ruler.
                if (effectiveLegitimacy >= RENEWAL_LEGITIMACY_THRESHOLD
                        && kingdom.hasModifierWithId("religion.sanctified")) {
                    yield KingdomAgeState.MATURE;
                }
                yield KingdomAgeState.DECADENT;
            }
        };
    }

    private static void transition(Kingdom kingdom,
                                   KingdomAgeState fromState,
                                   KingdomAgeState toState,
                                   long tick) {
        if (fromState != null) kingdom.removeModifier(fromState.modifierId());
        stampStateModifier(kingdom, toState, tick);
        KingdomEventBus.fire(new KingdomEvent.AgeCycleTransition(
                kingdom.getId(),
                fromState != null ? fromState.name() : "FRESH",
                toState.name(), tick));
        KingdomNewsfeed.append(kingdom, "age_cycle.transition",
                kingdom.getName() + " entered "
                        + toState.name().toLowerCase().replace('_', ' ')
                        + (fromState != null
                                ? " (was "
                                        + fromState.name().toLowerCase().replace('_', ' ')
                                        + ")"
                                : ""),
                tick);
        LOGGER.info("[AgeCycleDriver] {} transitioned {} → {}",
                kingdom.getName(), fromState, toState);
    }

    private static void stampStateModifier(Kingdom kingdom,
                                           KingdomAgeState state, long tick) {
        kingdom.addModifier(KingdomModifier.permanent(
                state.modifierId(),
                state.description(),
                state.stabilityDelta(),
                state.legitimacyDelta(),
                tick));
    }
}
