package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildMemberRef;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 4 doc 28 — daily maintenance for {@link RequestBoard}:
 *
 * <ol>
 *   <li>Drive {@link Request.Progress#isComplete} → FULFILLED + run
 *   {@link RequestSettlement#settleFulfilled}.</li>
 *   <li>Run {@link RequestBoard#expireStale} for past-deadline
 *   requests; settle each via the matching refund flow.</li>
 *   <li>Escalate any OPEN request that has sat at its current scope
 *   for longer than {@link RequestScope#escalationDays}.</li>
 *   <li>Run a simple acceptance pass per OPEN request: pick the
 *   first eligible guild whose member set contains an unbusy
 *   candidate.</li>
 *   <li>Prune terminal requests older than 30 days to keep the
 *   board bounded.</li>
 * </ol>
 *
 * <p>v1 acceptance is intentionally simple — first-eligible-wins.
 * Spec line 131 calls for a "score by reward-to-effort ratio" which
 * lands as Phase 5 polish once the per-guild capacity model
 * (workshop-load tracking, member-skill matching) ships.</p>
 */
public final class RequestBoardTicker {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long DAY = 24000L;
    /** Spec — terminal requests purged after 30 days. */
    private static final long PRUNE_AFTER_DAYS = 30L;

    private RequestBoardTicker() {}

    public static void dailyTick(ServerLevel level) {
        long now = level.getGameTime();
        RequestBoard board = RequestBoard.get(level);

        // 1. Promote completed in-progress requests + settle.
        for (Request r : board.all()) {
            if (r.status() != RequestStatus.IN_PROGRESS && r.status() != RequestStatus.ACCEPTED) {
                continue;
            }
            if (r.progress().isComplete()) {
                board.fulfill(r.requestId());
                Request fulfilled = board.get(r.requestId()).orElse(r);
                RequestSettlement.settleFulfilled(level, fulfilled);
            }
        }

        // 2. Expire stale requests + settle refunds.
        for (UUID id : board.expireStale(now)) {
            Request r = board.get(id).orElse(null);
            if (r == null) continue;
            if (r.status() == RequestStatus.EXPIRED) {
                RequestSettlement.settleExpired(level, r);
            } else if (r.status() == RequestStatus.FAILED) {
                RequestSettlement.settleFailed(level, r);
            }
        }

        // 3. Escalate OPEN requests past their scope-window.
        for (Request r : board.all()) {
            if (r.status() != RequestStatus.OPEN) continue;
            int windowDays = r.scope().escalationDays();
            if (windowDays == Integer.MAX_VALUE) continue;
            long anchor = r.lastEscalatedTick() > 0L ? r.lastEscalatedTick() : r.postedTick();
            if (now - anchor < windowDays * DAY) continue;
            RequestScope newScope = board.escalate(r.requestId(), now);
            if (newScope != null) {
                LOGGER.info("[RequestBoardTicker] {} escalated to {}", r.requestId(), newScope);
            }
        }

        // 4. Acceptance pass.
        runAcceptancePass(level, board, now);

        // 5. Prune terminal requests.
        board.prune(now, PRUNE_AFTER_DAYS * DAY);
    }

    private static void runAcceptancePass(ServerLevel level, RequestBoard board, long now) {
        GuildSavedData gdata = GuildSavedData.get(level);
        List<AbstractGuild> guilds = List.copyOf(gdata.all());
        if (guilds.isEmpty()) return;

        // board.open() returns a snapshot list. When accept() inside
        // the loop transitions a request OPEN → ACCEPTED, the snapshot
        // still carries the stale OPEN status; subsequent iterations
        // can't catch the change via the loop's local copy. Defence-
        // in-depth: board.accept() re-checks status against the live
        // map, and isEligible re-queries availableFor — both refuse
        // double-acceptance, so the indirect chain stays correct
        // even when the snapshot is stale.
        for (Request r : board.open()) {
            if (r.status() != RequestStatus.OPEN) continue;
            // Pick the highest-level eligible guild that has at least
            // one BRONZE+ member as a candidate fulfiller. Score is
            // just the number of qualifying members; ties resolve by
            // larger treasury (proxy for capacity).
            AbstractGuild best = null;
            UUID bestFulfiller = null;
            int bestScore = -1;
            for (AbstractGuild guild : guilds) {
                if (!isEligible(level, board, guild, r)) continue;
                UUID candidate = pickFulfiller(guild);
                if (candidate == null) continue;
                int score = guild.members().size();
                if (score > bestScore
                        || (score == bestScore && best != null
                                && guild.treasury().balance() > best.treasury().balance())) {
                    best = guild;
                    bestFulfiller = candidate;
                    bestScore = score;
                }
            }
            if (best == null) continue;
            int total = Math.max(1, r.target().targetCount());
            if (board.accept(r.requestId(), best.guildId(), bestFulfiller, total, now)) {
                LOGGER.info("[RequestBoardTicker] {} accepted by {} (fulfiller={})",
                        r.requestId(), best.name(), bestFulfiller);
            }
        }
    }

    private static boolean isEligible(ServerLevel level, RequestBoard board,
                                      AbstractGuild guild, Request r) {
        if (guild.members().isEmpty()) return false;
        // Use the board's own filter to avoid drift between query
        // shape and acceptance-pass scope semantics.
        return board.availableFor(guild, level).stream()
                .anyMatch(open -> open.requestId().equals(r.requestId()));
    }

    private static UUID pickFulfiller(AbstractGuild guild) {
        // Pick the highest-rank member; ties broken by contribution.
        return guild.members().values().stream()
                .max(Comparator.comparingInt((GuildMemberRef m) -> m.rank().seniority())
                        .thenComparingInt(GuildMemberRef::contribution))
                .map(GuildMemberRef::memberId)
                .orElse(null);
    }

    /** Suppresses unused-import warning on Map. */
    @SuppressWarnings("unused")
    private static final Map<UUID, AbstractGuild> DOC_HOOK = Map.of();
}
