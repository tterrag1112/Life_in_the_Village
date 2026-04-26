package tterrag1112.life_in_the_village.Npc.Laws;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Npc.Traits.TraitVector;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.NeedLevel;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * NPC-leader autonomy. Spec line 99-111.
 *
 * <p>Daily flow per village whose {@code village_leader} is held by
 * an NPC:</p>
 * <ol>
 *   <li>Assess village needs (food deficit, treasury low, etc.).</li>
 *   <li>Score each candidate law via {@link #candidateScore}.</li>
 *   <li>Apply leader trait bias (Ambition / Compassion / Honesty).</li>
 *   <li>Roll: only the highest-scoring law beats the per-month
 *   {@code 1/30} base chance; high scores reduce the gate.</li>
 *   <li>If a roll succeeds, route through {@link LawEnactment#enact}
 *   so the lifecycle (gossip / mood / rep) fires identically.</li>
 * </ol>
 *
 * <p>Player-leader villages are skipped — they get the management UI
 * and verb path instead. Only laws this leader is plausibly motivated
 * to enact are scored, so a Compassionate leader doesn't randomly
 * propose CURFEW, etc.</p>
 */
public final class LawDecisionEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Random instance — seeded per-call so tests can swap if needed. */
    private static final Random RANDOM = new Random();

    /** Base per-day enactment chance for the highest-scoring candidate. */
    private static final float BASE_DAILY_CHANCE = 1f / 30f;

    private LawDecisionEngine() {}

    /** Daily sweep entry point. */
    public static void dailyTick(ServerLevel level) {
        if (level == null) return;
        VillageSavedData data = VillageSavedData.get(level);
        for (Village v : data.getAllVillages()) {
            try { tickOne(level, data, v); }
            catch (Throwable t) {
                LOGGER.warn("[LawDecisionEngine] {} threw: {}", v.getName(), t.getMessage());
            }
        }
    }

    private static void tickOne(ServerLevel level, VillageSavedData data, Village village) {
        Optional<TownspersonMob> leaderOpt = leaderOf(level, village);
        if (leaderOpt.isEmpty()) return;       // no NPC leader — player or vacant
        TownspersonMob leader = leaderOpt.get();
        VillagePolicy policy = village.getPolicy();
        if (policy == null) return;

        // Apply daily reputation drift for unpopular laws (spec line 134).
        applyDailyReputationDrift(village, data, level, leader);

        // Score every law the leader could enact today.
        List<Scored> scored = scoreCandidates(village, data, level, leader);
        if (scored.isEmpty()) return;

        scored.sort((a, b) -> Float.compare(b.score, a.score));
        Scored best = scored.get(0);
        if (best.score <= 0f) return; // nothing positive — skip the roll

        float chance = BASE_DAILY_CHANCE * (1f + best.score / 50f);
        if (RANDOM.nextFloat() > chance) return;

        LawEnactment.Outcome result = LawEnactment.enact(village, best.law, null,
                level, leader.getUUID());
        if (!result.success()) {
            LOGGER.debug("[LawDecisionEngine] {} declined to enact {}: {}",
                    village.getName(), best.law, result.reason());
        } else {
            LOGGER.debug("[LawDecisionEngine] {} enacted {} (score={})",
                    village.getName(), best.law, best.score);
        }
    }

    // ── Candidate scoring ──────────────────────────────────────────────────

    private record Scored(VillageLaw law, float score) {}

    private static List<Scored> scoreCandidates(Village village, VillageSavedData data,
                                                ServerLevel level, TownspersonMob leader) {
        List<Scored> out = new ArrayList<>();
        VillagePolicy policy = village.getPolicy();
        for (VillageLaw law : VillageLaw.values()) {
            if (policy.hasLaw(law)) continue;
            if (policy.firstConflictWith(law).isPresent()) continue;
            float score = candidateScore(law, village, data, level, leader);
            if (score <= 0f) continue;
            out.add(new Scored(law, score));
        }
        return out;
    }

    /**
     * Combined need-fit + trait-fit score. Higher = more likely to be
     * picked. Returns 0 when the leader has no reason to enact.
     */
    public static float candidateScore(VillageLaw law, Village village,
                                       VillageSavedData data, ServerLevel level,
                                       TownspersonMob leader) {
        float score = 0f;
        score += needFit(law, village);
        score += traitBias(law, leader.getTraitVector());

        // Penalise unpopular candidates so a Compassionate leader still
        // hesitates over an unpopular law that fits the village's needs.
        int popularity = LawPopularity.compute(law, village, data, level);
        if (popularity < 0) score += popularity / 10f; // negative
        return score;
    }

    /** Needs-driven baseline. Bigger when the village currently suffers. */
    private static float needFit(VillageLaw law, Village village) {
        return switch (law) {
            case SUBSIDIZE_FARMER -> isFoodPoor(village) ? 25f : 0f;
            case PRICE_CEILING_FOOD -> isFoodPoor(village) ? 15f : 0f;
            case SUBSIDIZE_HEALER, SUBSIDIZE_SCHOLAR, SUBSIDIZE_BLACKSMITH -> 5f;
            case PROPERTY_TAX_DOUBLE, MARKET_TAX_DOUBLE, PROFITS_TO_TREASURY ->
                    isTreasuryLow(village) ? 20f : 5f;
            case PROPERTY_TAX_WAIVED, MARKET_TAX_REDUCED ->
                    isTreasuryHealthy(village) ? 10f : -5f;
            case CURFEW, DOUBLE_PUNISHMENT -> 5f; // Phase 3 doc 19 will replace with crime metric
            case PARDON_FIRST_OFFENSE, BAN_EXECUTION -> 5f;
            case TOLL_ENTRY -> isTreasuryLow(village) ? 8f : 0f;
            case FOREIGN_TRADER_BAN -> 0f;
            case PILGRIM_WELCOME_BONUS, FESTIVAL_MANDATORY, COMPULSORY_SCHOOLING,
                 TEMPLE_ATTENDANCE_EXPECTED -> 4f;
            case GUILD_MEMBERSHIP_REQUIRED -> 0f;
            case PRICE_FLOOR_FOOD -> isFoodGlut(village) ? 8f : 0f;
        };
    }

    /** Per-axis trait bias: leaders prefer laws their personality fits. */
    private static float traitBias(VillageLaw law, TraitVector traits) {
        if (traits == null) return 0f;
        float sum = 0f;
        for (var entry : law.traitFit().entrySet()) {
            TraitAxis axis = entry.getKey();
            float weight = entry.getValue();
            sum += traits.get(axis) * weight * 10f;
        }
        return sum;
    }

    // ── Village state helpers ──────────────────────────────────────────────

    private static boolean isFoodPoor(Village v) {
        var n = v.getNeeds().get(NeedCategory.FOOD);
        return n != null && (n.getLevel() == NeedLevel.LOW || n.getLevel() == NeedLevel.CRITICAL);
    }

    private static boolean isFoodGlut(Village v) {
        var n = v.getNeeds().get(NeedCategory.FOOD);
        return n != null && n.getLevel() == NeedLevel.SURPLUS;
    }

    private static boolean isTreasuryLow(Village v) {
        return v.getTreasuryBronze() < 100L;
    }

    private static boolean isTreasuryHealthy(Village v) {
        return v.getTreasuryBronze() > 1000L;
    }

    private static Optional<TownspersonMob> leaderOf(ServerLevel level, Village village) {
        var holding = village.getOffices().get(OfficeRegistry.VILLAGE_LEADER).orElse(null);
        if (holding == null || holding.isVacant() || !holding.holderIsNpc()) return Optional.empty();
        return holding.holderUuid().flatMap(id -> TownspersonMob.findByUUID(level, id));
    }

    // ── Daily reputation drift for unpopular laws ──────────────────────────

    private static void applyDailyReputationDrift(Village village, VillageSavedData data,
                                                  ServerLevel level, TownspersonMob leader) {
        VillagePolicy policy = village.getPolicy();
        if (policy == null) return;
        // Drift tied to leader's NPC↔NPC relationships? Spec line 134
        // says "leader rep -1/day while law active" — for an NPC leader
        // we don't have a player rep ledger to tap; the relationship
        // ledger is per-NPC. Skipping the mechanic for NPC leaders in
        // v1 and documenting for follow-up; player leaders pick up the
        // delta via {@link LawAnnouncement#applyLeaderReputationDelta}
        // each enact / repeal which is a stronger signal anyway.
    }
}
