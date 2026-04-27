package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 28 — single per-world board carrying every active
 * {@link Request}. Visibility filtering is done at query time via
 * {@link #availableFor(AbstractGuild, ServerLevel)} so the spec's
 * three nested boards (per-village / per-kingdom / global) collapse
 * to one structure plus a {@link RequestScope} field. v1 ships the
 * single-store form; Phase 5 can split if profiling demands it.
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>{@link #post}: writes a new OPEN request.</li>
 *   <li>{@link #accept}: transitions OPEN → ACCEPTED + records the
 *   accepting guild + fulfiller.</li>
 *   <li>{@link #updateProgress}: bumps current/total counters; the
 *   {@link RequestBoardTicker} flips status to FULFILLED on completion.</li>
 *   <li>{@link #fulfill} / {@link #fail} / {@link #expire}: terminal
 *   transitions; the daily ticker drives {@link #expireStale} and the
 *   scope-escalation pass.</li>
 * </ul>
 */
public class RequestBoard extends SavedData {

    public static final SavedDataType<RequestBoard> TYPE = new SavedDataType<>(
            "life_in_the_village_request_board",
            RequestBoard::new,
            RecordCodecBuilder.create(i -> i.group(
                    Request.CODEC.listOf().optionalFieldOf("requests", List.of())
                            .forGetter(b -> new ArrayList<>(b.requests.values()))
            ).apply(i, RequestBoard::fromCodec)));

    private final Map<UUID, Request> requests = new LinkedHashMap<>();

    public RequestBoard() {}

    private static RequestBoard fromCodec(List<Request> seed) {
        RequestBoard b = new RequestBoard();
        if (seed != null) for (Request r : seed) {
            if (r != null) b.requests.put(r.requestId(), r);
        }
        return b;
    }

    public static RequestBoard get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public void markDirty() { setDirty(); }

    // ── Mutations ─────────────────────────────────────────────────────────

    public void post(Request request) {
        if (request == null) return;
        requests.put(request.requestId(), request);
        setDirty();
    }

    public Optional<Request> get(UUID requestId) {
        return Optional.ofNullable(requestId == null ? null : requests.get(requestId));
    }

    public List<Request> all() { return List.copyOf(requests.values()); }

    /**
     * Transitions a request to ACCEPTED. No-op if the request is
     * missing or no longer OPEN — the spec says the first acceptance
     * wins (line 330) so we just refuse later commits.
     */
    public boolean accept(UUID requestId, UUID acceptingGuildId,
                          UUID fulfillerNpcId, int totalProgress, long tick) {
        Request r = requests.get(requestId);
        if (r == null || r.status() != RequestStatus.OPEN) return false;
        Request updated = r.withProgress(r.progress().withAccepted(
                acceptingGuildId, fulfillerNpcId, tick, totalProgress))
                .withStatus(RequestStatus.ACCEPTED);
        requests.put(requestId, updated);
        setDirty();
        return true;
    }

    public boolean updateProgress(UUID requestId, int newCurrent) {
        Request r = requests.get(requestId);
        if (r == null) return false;
        if (r.status() != RequestStatus.ACCEPTED && r.status() != RequestStatus.IN_PROGRESS) {
            return false;
        }
        Request updated = r.withProgress(r.progress().withCurrent(newCurrent));
        if (updated.status() == RequestStatus.ACCEPTED) {
            updated = updated.withStatus(RequestStatus.IN_PROGRESS);
        }
        requests.put(requestId, updated);
        setDirty();
        return true;
    }

    public boolean fulfill(UUID requestId) {
        Request r = requests.get(requestId);
        if (r == null || r.status().isTerminal()) return false;
        requests.put(requestId, r.withStatus(RequestStatus.FULFILLED));
        setDirty();
        return true;
    }

    public boolean fail(UUID requestId) {
        Request r = requests.get(requestId);
        if (r == null || r.status().isTerminal()) return false;
        requests.put(requestId, r.withStatus(RequestStatus.FAILED));
        setDirty();
        return true;
    }

    public boolean expire(UUID requestId) {
        Request r = requests.get(requestId);
        if (r == null || r.status().isTerminal()) return false;
        requests.put(requestId, r.withStatus(RequestStatus.EXPIRED));
        setDirty();
        return true;
    }

    /** Escalates the scope of a request to its next step. Returns
     *  the new scope when the call advanced things, else null. */
    public RequestScope escalate(UUID requestId, long tick) {
        Request r = requests.get(requestId);
        if (r == null || r.status() != RequestStatus.OPEN) return null;
        RequestScope next = r.scope().nextEscalation();
        if (next == null) return null;
        requests.put(requestId, r.withScope(next, tick));
        setDirty();
        return next;
    }

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Returns every {@link RequestStatus#OPEN} request the supplied
     * guild is allowed to accept. Filtering rules per spec lines
     * 65-70: VILLAGE_INTERNAL → only the origin guild;
     * VILLAGE_PUBLIC → guilds in the same village; KINGDOM_WIDE →
     * guilds in the same kingdom; GLOBAL → anyone.
     */
    public List<Request> availableFor(AbstractGuild guild, ServerLevel level) {
        if (guild == null || level == null) return List.of();
        if (!guild.canPostRequests()) {
            // L0 guilds may post but acceptance gating still requires
            // L1 — spec line 199-200. Acceptors below ESTABLISHED are
            // limited to their own posted requests (i.e. nothing).
            return List.of();
        }
        UUID guildId = guild.guildId();
        UUID villageId = guild.homeVillageId();
        UUID kingdomId = kingdomOf(level, villageId);
        boolean canRemote = guild.canAcceptRemoteRequests();
        List<Request> out = new ArrayList<>();
        for (Request r : requests.values()) {
            if (r.status() != RequestStatus.OPEN) continue;
            if (Objects.equals(r.originGuildId(), guildId)) continue; // can't accept own

            switch (r.scope()) {
                case VILLAGE_INTERNAL -> { /* origin-guild only — already excluded above */ }
                case VILLAGE_PUBLIC -> {
                    if (Objects.equals(r.originVillageId(), villageId)) out.add(r);
                }
                case KINGDOM_WIDE -> {
                    if (kingdomId != null
                            && Objects.equals(kingdomId, kingdomOf(level, r.originVillageId()))) {
                        out.add(r);
                    }
                }
                case GLOBAL -> { if (canRemote) out.add(r); }
            }
        }
        return out;
    }

    public List<Request> byOriginGuild(UUID originGuildId) {
        if (originGuildId == null) return List.of();
        return requests.values().stream()
                .filter(r -> Objects.equals(r.originGuildId(), originGuildId))
                .toList();
    }

    public List<Request> byScope(RequestScope scope) {
        if (scope == null) return List.of();
        return requests.values().stream()
                .filter(r -> r.scope() == scope)
                .toList();
    }

    public List<Request> open() {
        return requests.values().stream()
                .filter(r -> r.status() == RequestStatus.OPEN)
                .toList();
    }

    // ── Maintenance ───────────────────────────────────────────────────────

    /** Marks every active request whose deadline has passed as either
     *  EXPIRED (if still OPEN) or FAILED (if accepted but stale). */
    public List<UUID> expireStale(long currentTick) {
        List<UUID> changed = new ArrayList<>();
        for (Map.Entry<UUID, Request> e : requests.entrySet()) {
            Request r = e.getValue();
            if (r.status().isTerminal()) continue;
            if (!r.isExpired(currentTick)) continue;
            RequestStatus next = r.status() == RequestStatus.OPEN
                    ? RequestStatus.EXPIRED : RequestStatus.FAILED;
            e.setValue(r.withStatus(next));
            changed.add(r.requestId());
        }
        if (!changed.isEmpty()) setDirty();
        return changed;
    }

    /** Drops terminal requests older than {@code retentionTicks}. The
     *  ticker calls this once a day to keep the board bounded. */
    public int prune(long currentTick, long retentionTicks) {
        int before = requests.size();
        requests.entrySet().removeIf(entry -> {
            Request r = entry.getValue();
            if (!r.status().isTerminal()) return false;
            long anchor = r.lastEscalatedTick() > 0L ? r.lastEscalatedTick() : r.postedTick();
            return currentTick - anchor > retentionTicks;
        });
        int removed = before - requests.size();
        if (removed > 0) setDirty();
        return removed;
    }

    private static UUID kingdomOf(ServerLevel level, UUID villageId) {
        if (villageId == null) return null;
        return tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level)
                .getKingdomForVillage(villageId)
                .map(tterrag1112.life_in_the_village.Kingdom.Kingdom::getId)
                .orElse(null);
    }

    /** Suppresses unused-import warning on GuildSavedData (used by
     *  acceptance / settlement helpers in companion classes). */
    @SuppressWarnings("unused")
    private static final GuildSavedData DOC_HOOK = null;
}
