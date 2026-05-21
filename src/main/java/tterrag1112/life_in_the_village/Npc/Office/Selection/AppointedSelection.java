package tterrag1112.life_in_the_village.Npc.Office.Selection;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Office.OfficeHolding;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Office.SelectionMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Filled by a higher-office holder. v1 algorithm when no explicit
 * appointment has been made (typical for newly-founded villages):
 *
 * <ol>
 *   <li>Look up the appointer office for this office id (e.g. village
 *   leader appoints treasurer / constable / bailiff / scribe).</li>
 *   <li>If the appointer is held by an NPC, pick the eligible candidate
 *   the appointer most likes (relationship-weighted, with skill as
 *   tiebreaker).</li>
 *   <li>If the appointer is held by a player, leave the office vacant —
 *   the player picks via the {@code AppointToOfficeVerb}.</li>
 *   <li>If no appointer is seated yet, leave vacant; the next election
 *   pass retries.</li>
 * </ol>
 *
 * <p>Spec: "For Phase 3, the appointing UI is a debug command + dialogue
 * verb on the appointer NPC."</p>
 */
public final class AppointedSelection implements OfficeSelectionEngine {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Map of office id → appointer office id. */
    private static final Map<String, String> APPOINTER_OF;
    static {
        Map<String, String> m = new HashMap<>();
        m.put(OfficeRegistry.VILLAGE_TREASURER, OfficeRegistry.VILLAGE_LEADER);
        m.put(OfficeRegistry.VILLAGE_CONSTABLE, OfficeRegistry.VILLAGE_LEADER);
        m.put(OfficeRegistry.VILLAGE_BAILIFF,   OfficeRegistry.VILLAGE_LEADER);
        m.put(OfficeRegistry.VILLAGE_SCRIBE,    OfficeRegistry.VILLAGE_LEADER);
        m.put(OfficeRegistry.GUILD_TREASURER,   OfficeRegistry.GUILD_MASTER);
        m.put(OfficeRegistry.GUILD_REGISTRAR,   OfficeRegistry.GUILD_MASTER);
        m.put(OfficeRegistry.BUSINESS_FOREMAN,   OfficeRegistry.BUSINESS_OWNER);
        m.put(OfficeRegistry.BUSINESS_BOOKKEEPER,OfficeRegistry.BUSINESS_OWNER);
        m.put(OfficeRegistry.KINGDOM_CHANCELLOR,OfficeRegistry.KINGDOM_KING);
        m.put(OfficeRegistry.KINGDOM_TREASURER, OfficeRegistry.KINGDOM_KING);
        APPOINTER_OF = Map.copyOf(m);
    }

    /** Returns the appointer office id, or null when none is registered. */
    public static String appointerFor(String officeId) {
        return APPOINTER_OF.get(officeId);
    }

    @Override public SelectionMethod method() { return SelectionMethod.APPOINTED; }

    @Override
    public Optional<OfficeHolding> select(OfficeSelectionContext ctx) {
        String appointerId = APPOINTER_OF.get(ctx.def().id());
        if (appointerId == null) {
            LOGGER.debug("[OfficeElection] APPOINTED on '{}' has no registered appointer; leaving vacant",
                    ctx.def().id());
            return Optional.empty();
        }
        OfficeHolding appointerHolding = ctx.orgState().get(appointerId).orElse(null);
        if (appointerHolding == null || appointerHolding.isVacant()) {
            // The appointer office isn't filled yet. The driver re-runs
            // the daily sweep, so this office gets re-tried tomorrow.
            return Optional.empty();
        }
        if (appointerHolding.holderIsPlayer()) {
            // Player appointment is explicit; engine declines.
            return Optional.empty();
        }
        UUID appointerNpcId = appointerHolding.holderNpcId().orElse(null);
        if (appointerNpcId == null) return Optional.empty();
        TownspersonMob appointer = TownspersonMob.findByUUID(ctx.level(), appointerNpcId).orElse(null);
        if (appointer == null) return Optional.empty();

        List<OfficeCandidate> pool = CandidatePool.buildFor(ctx);
        if (pool.isEmpty()) return Optional.empty();

        // Score: appointer-relationship + skill bonus.
        OfficeCandidate winner = pool.stream()
                .map(c -> {
                    double rel = appointer.getNpcRelationships().getScore(c.uuid());
                    double s = c.primarySkillLevel() + 0.4d * rel;
                    return c.withScore(s);
                })
                .sorted(SelectionTiebreaker.byScoreThenTiebreak())
                .findFirst().orElse(null);
        if (winner == null) return Optional.empty();

        long termEnd = ctx.def().isIndefiniteTerm()
                ? 0L
                : ctx.tick() + (long) ctx.def().termDays() * 24000L;
        return Optional.of(OfficeHolding.heldByNpc(
                ctx.def().id(), ctx.orgId(), winner.uuid(),
                ctx.tick(), termEnd, SelectionMethod.APPOINTED));
    }
}
