package tterrag1112.life_in_the_village.Kingdom.War;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Track D3.6.4 — political war record.
 *
 * <p>Created via {@link WarEngine#declareWar}. Lives on the
 * attacker kingdom's {@code wars} list (one-source authority);
 * the defender accesses participation through
 * {@code VSD.findActiveWarsInvolving(kingdomId)}.
 *
 * <h3>Score semantics</h3>
 * {@code attackerScore} starts at {@link CasusBelli#defaultWarScoreSeed}
 * for the casus belli. Battles add +/- scores per
 * {@link BattleResolver}. Attrition + supply also nudge daily.
 *
 * <p>Resolution thresholds:
 * <ul>
 *   <li>{@code attackerScore ≥ ATTACKER_VICTORY_THRESHOLD} →
 *       attacker wins; war goals applied.</li>
 *   <li>{@code attackerScore ≤ DEFENDER_VICTORY_THRESHOLD} →
 *       defender wins; white peace; attacker takes legitimacy
 *       hit.</li>
 *   <li>Otherwise after {@code STALEMATE_TICKS} elapsed →
 *       white peace.</li>
 * </ul>
 *
 * @param id              stable UUID.
 * @param attackerKingdomId / defenderKingdomId — combatants.
 * @param casusBelli      justification.
 * @param goals           list of attacker's war goals; outcome
 *                        applies each on attacker victory.
 * @param startTick       declaration tick.
 * @param attackerScore   current attacker advantage (positive →
 *                        attacker winning).
 * @param battles         resolved + pending battles.
 * @param status          ACTIVE / WON / LOST / WHITE_PEACE / STALEMATE.
 * @param resolvedTick    tick the war ended (0 for active).
 * @param outcomeSummary  one-line outcome description for newsfeed.
 */
public record War(
        UUID id,
        UUID attackerKingdomId,
        UUID defenderKingdomId,
        CasusBelli casusBelli,
        List<WarGoal> goals,
        long startTick,
        int attackerScore,
        List<Battle> battles,
        Status status,
        long resolvedTick,
        String outcomeSummary
) {

    public enum Status { ACTIVE, WON, LOST, WHITE_PEACE, STALEMATE }

    /** Score threshold for attacker victory (after seed). */
    public static final int ATTACKER_VICTORY_THRESHOLD = 60;

    /** Score threshold below which defender wins. */
    public static final int DEFENDER_VICTORY_THRESHOLD = -40;

    /** Stalemate timeout — wars older than this resolve to white peace. */
    public static final long STALEMATE_TICKS = 120L * 24000L;

    public War {
        if (goals == null) goals = List.of();
        else goals = List.copyOf(goals);
        if (battles == null) battles = List.of();
        else battles = List.copyOf(battles);
        if (outcomeSummary == null) outcomeSummary = "";
    }

    public static final Codec<War> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(War::id),
            UUIDUtil.CODEC.fieldOf("attackerKingdomId").forGetter(War::attackerKingdomId),
            UUIDUtil.CODEC.fieldOf("defenderKingdomId").forGetter(War::defenderKingdomId),
            Codec.STRING.xmap(CasusBelli::valueOf, Enum::name)
                    .fieldOf("casusBelli").forGetter(War::casusBelli),
            WarGoal.CODEC.listOf().optionalFieldOf("goals", List.of())
                    .forGetter(War::goals),
            Codec.LONG.fieldOf("startTick").forGetter(War::startTick),
            Codec.INT.optionalFieldOf("attackerScore", 0).forGetter(War::attackerScore),
            Battle.CODEC.listOf().optionalFieldOf("battles", List.of())
                    .forGetter(War::battles),
            Codec.STRING.xmap(Status::valueOf, Enum::name)
                    .optionalFieldOf("status", Status.ACTIVE).forGetter(War::status),
            Codec.LONG.optionalFieldOf("resolvedTick", 0L).forGetter(War::resolvedTick),
            Codec.STRING.optionalFieldOf("outcomeSummary", "").forGetter(War::outcomeSummary)
    ).apply(i, War::new));

    public boolean isActive()    { return status == Status.ACTIVE; }
    public boolean involves(UUID kingdomId) {
        return attackerKingdomId.equals(kingdomId) || defenderKingdomId.equals(kingdomId);
    }

    public War withScore(int newScore) {
        return new War(id, attackerKingdomId, defenderKingdomId, casusBelli, goals,
                startTick, newScore, battles, status, resolvedTick, outcomeSummary);
    }

    public War withBattleAppended(Battle battle) {
        List<Battle> next = new ArrayList<>(battles);
        next.add(battle);
        return new War(id, attackerKingdomId, defenderKingdomId, casusBelli, goals,
                startTick, attackerScore, next, status, resolvedTick, outcomeSummary);
    }

    public War asResolved(Status newStatus, long tick, String summary) {
        return new War(id, attackerKingdomId, defenderKingdomId, casusBelli, goals,
                startTick, attackerScore, battles, newStatus, tick,
                summary == null ? "" : summary);
    }

    public static War freshDeclaration(UUID id, UUID attacker, UUID defender,
                                       CasusBelli cb, List<WarGoal> goals, long tick) {
        return new War(id, attacker, defender, cb, goals, tick,
                cb.defaultWarScoreSeed(), List.of(), Status.ACTIVE, 0L, "");
    }
}
