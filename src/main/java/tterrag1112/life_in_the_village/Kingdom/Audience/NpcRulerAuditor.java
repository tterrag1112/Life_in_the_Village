package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.5B — daily NPC-ruler audit pass over each kingdom's
 * pending petition queue.
 *
 * <p>Per the user-confirmed lock "trait pool extended with PIETY +
 * SCHOLARSHIP axes", NPC rulers score petitions using a
 * trait-weighted formula that draws on the new axes plus the
 * existing eight. The output is an integer "approval score" — high
 * positive → approve; high negative → deny; near zero → leave
 * pending for a human player or a later tick.
 *
 * <h3>Scoring shape</h3>
 * Each {@link PetitionKind} has a {@link KindWeights} record
 * pinning the relevant trait axes. The score combines:
 * <ul>
 *   <li>Petitioner standing × 0.5 (trusted petitioners get a
 *       leg-up; hostile ones a penalty).</li>
 *   <li>Trait-weighted personality bias from the ruler's
 *       {@link TraitVector}.</li>
 *   <li>Per-kind constant baseline (some petition kinds default
 *       deny, e.g. WAR_DECLARATION).</li>
 * </ul>
 *
 * <h3>Threshold</h3>
 * Score ≥ {@link #APPROVE_THRESHOLD} → approve.
 * Score ≤ {@link #DENY_THRESHOLD} → deny.
 * Otherwise leave pending; expiry sweep handles stale entries.
 *
 * <h3>Player-ruled kingdoms skipped</h3>
 * If {@link Kingdom#getRulerPlayerId} is present, the AI auditor
 * leaves the queue alone — the player is the ruler and resolves
 * via the audience screen.
 */
public final class NpcRulerAuditor {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int APPROVE_THRESHOLD = 25;
    public static final int DENY_THRESHOLD    = -25;

    /**
     * Per-kind trait weighting + baseline. Higher trait weight
     * means the axis pulls harder on the score; baseline is a
     * constant added regardless of personality.
     *
     * @param weights         axis → weight in [-1.0, +1.0]; positive
     *                        weight means a positive axis value
     *                        favours approval.
     * @param baseline        constant added (negative for default-deny
     *                        kinds like WAR_DECLARATION).
     */
    public record KindWeights(java.util.Map<TraitAxis, Double> weights,
                              int baseline) {}

    private static final java.util.EnumMap<PetitionKind, KindWeights> WEIGHTS
            = new java.util.EnumMap<>(PetitionKind.class);

    static {
        // AUDIENCE_GRIEVANCE — generous + compassionate rulers
        // tend to grant; misers + cold rulers tend to deny.
        // Slight default-approve baseline.
        WEIGHTS.put(PetitionKind.AUDIENCE_GRIEVANCE, new KindWeights(
                java.util.Map.of(
                        TraitAxis.GENEROSITY, 0.6,
                        TraitAxis.COMPASSION, 0.4,
                        TraitAxis.AMBITION,  -0.2),
                +5));

        // CHARTER_REQUEST — ambition + scholarship favour granting
        // (rulers who value records / reward service grant readily);
        // miserly rulers slow to grant.
        WEIGHTS.put(PetitionKind.CHARTER_REQUEST, new KindWeights(
                java.util.Map.of(
                        TraitAxis.AMBITION,    0.4,
                        TraitAxis.SCHOLARSHIP, 0.5,
                        TraitAxis.GENEROSITY,  0.3,
                        TraitAxis.HONESTY,     0.2),
                -5));

        // TREATY_RATIFICATION — scholarly + temperate rulers
        // ratify; impulsive courageous rulers reject (prefer
        // wartime independence).
        WEIGHTS.put(PetitionKind.TREATY_RATIFICATION, new KindWeights(
                java.util.Map.of(
                        TraitAxis.SCHOLARSHIP, 0.5,
                        TraitAxis.TEMPERANCE,  0.4,
                        TraitAxis.COURAGE,    -0.3),
                0));

        // WAR_DECLARATION — courageous + ambitious rulers approve;
        // temperate + scholarly + pious rulers reject.
        WEIGHTS.put(PetitionKind.WAR_DECLARATION, new KindWeights(
                java.util.Map.of(
                        TraitAxis.COURAGE,    0.6,
                        TraitAxis.AMBITION,   0.4,
                        TraitAxis.TEMPERANCE,-0.5,
                        TraitAxis.PIETY,     -0.3,
                        TraitAxis.COMPASSION,-0.2),
                -20));

        // BREAK_TREATY — ambitious + dishonest rulers break;
        // scholarly + pious rulers honour. PIETY weighs heavy
        // here per the new axis's purpose.
        WEIGHTS.put(PetitionKind.BREAK_TREATY, new KindWeights(
                java.util.Map.of(
                        TraitAxis.AMBITION,    0.5,
                        TraitAxis.HONESTY,    -0.5,
                        TraitAxis.PIETY,      -0.4,
                        TraitAxis.SCHOLARSHIP,-0.2),
                -10));
    }

    private NpcRulerAuditor() {}

    /**
     * Daily entry point. Iterates every kingdom; for NPC-ruled
     * kingdoms, scores + auto-resolves pending petitions whose
     * absolute score crosses the threshold. Player-ruled kingdoms
     * are skipped.
     */
    public static void dailyTick(ServerLevel level, VillageSavedData data, long tick) {
        for (Kingdom kingdom : data.getAllKingdoms()) {
            if (kingdom.getRulerPlayerId().isPresent()) continue;
            UUID rulerNpc = kingdom.getRulerEntityId().orElse(null);
            if (rulerNpc == null) continue;
            TownspersonMob ruler = TownspersonMob.findByUUID(level, rulerNpc).orElse(null);
            // Vacant or offline ruler — no AI resolutions today;
            // expiry sweep will clean stale entries on its own.
            if (ruler == null) continue;

            TraitVector traits = ruler.getTraitVector();
            for (Petition p : new java.util.ArrayList<>(kingdom.getPetitions())) {
                if (!p.isPending()) continue;
                int score = scorePetition(kingdom, p, traits, tick);
                if (score >= APPROVE_THRESHOLD) {
                    AudienceDriver.approve(level, data, kingdom, p.id(), rulerNpc, tick,
                            "ruler approval (score " + score + ")");
                } else if (score <= DENY_THRESHOLD) {
                    AudienceDriver.deny(level, data, kingdom, p.id(), rulerNpc, tick,
                            "ruler denial (score " + score + ")");
                }
                // Otherwise leave pending.
            }
        }
    }

    /**
     * Computes the approval score for one petition under one
     * ruler's personality. Exposed for debug commands and tests.
     */
    public static int scorePetition(Kingdom kingdom, Petition petition,
                                    TraitVector traits, long tick) {
        KindWeights kw = WEIGHTS.get(petition.kind());
        if (kw == null) return 0;
        double sum = kw.baseline();
        for (var entry : kw.weights().entrySet()) {
            sum += entry.getValue() * traits.get(entry.getKey()) * 30.0;
        }
        // Standing modulation: ±15 at the ±100 extremes.
        PlayerStanding ps = kingdom.getPlayerStanding(petition.playerUuid(), tick);
        sum += ps.score() * 0.15;
        return (int) Math.round(sum);
    }
}
