package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.logging.LogUtils;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessGuildRegistrar;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Priority;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProducerSpecs;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskIds;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskSpec;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskFilter;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskId;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T5b-2/3 — the guild &rarr; business &rarr; NPC request cascade.
 *
 * <p>Owns CRAFT {@link Request}s. Run once per day from the top of
 * {@link RequestBoardTicker#dailyTick} (before the abstract acceptance pass,
 * which now SKIPs CRAFT). For each OPEN CRAFT request it:</p>
 *
 * <ol>
 *   <li>resolves the producing {@link Profession} for the requested item via
 *       {@link ProducerSpecs#ALL} (the spec whose {@code finalOutputs}
 *       contains the item);</li>
 *   <li>finds the origin guild's registered businesses
 *       ({@link BusinessSavedData#forGuild}) whose craft profession matches;</li>
 *   <li>splits the requested count across them (even + remainder-to-first,
 *       &ge;1 each);</li>
 *   <li>resolves a delivery destination (the guild hall building, else the
 *       origin village stockpile);</li>
 *   <li>writes a {@code ProvideItem(item, share)} craft task and a
 *       dependent {@code Deliver(item, share, dest)} task onto each business's
 *       board, recording the delivery leg in {@link RequestCascadeLedger};</li>
 *   <li>marks the request ACCEPTED so the abstract ticker leaves it alone.</li>
 * </ol>
 *
 * <h3>Why it re-arms across passes</h3>
 * The producer craft executor runs ONE batch per task run, so a single craft
 * run does not guarantee the whole share. Each daily pass therefore re-ensures
 * the craft + deliver tasks for any ACCEPTED-but-incomplete cascaded request
 * (stable ids; an already-present pair is left in place, a DONE/missing pair is
 * recreated). The {@code DeliverFulfillment} delivers whatever is on hand up to
 * the leg's remaining amount and advances {@link RequestBoard#updateProgress};
 * the existing settlement fires when {@code progress.isComplete()}.
 *
 * <p>Skips (leaves the request OPEN, never crashes) when no producing
 * profession resolves, no matching business exists, or no destination resolves
 * — the abstract ticker's escalation pass can still act on a still-OPEN
 * request.</p>
 */
public final class RequestCascade {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sentinel fulfiller when no producer worker is loaded to credit. */
    private static final UUID SENTINEL_FULFILLER = new UUID(0L, 0L);

    private RequestCascade() {}

    /**
     * Decompose every OPEN CRAFT request, and re-arm every
     * ACCEPTED-but-incomplete cascaded request. Also prunes ledger legs for
     * requests that have reached a terminal state.
     */
    public static void run(ServerLevel level, RequestBoard board, long now) {
        BusinessSavedData bdata = BusinessSavedData.get(level);
        VillageSavedData vdata = VillageSavedData.get(level);
        GuildSavedData gdata = GuildSavedData.get(level);
        TaskSavedData tdata = TaskSavedData.get(level);
        RequestCascadeLedger ledger = RequestCascadeLedger.get(level);

        for (Request r : board.all()) {
            if (r.type() != RequestType.CRAFT) continue;

            // Terminal request: drop its ledger legs and move on.
            if (r.status().isTerminal()) {
                ledger.clearRequest(r.requestId());
                continue;
            }

            // OPEN: first decomposition. Mark ACCEPTED on success.
            if (r.status() == RequestStatus.OPEN) {
                decompose(level, board, bdata, vdata, gdata, tdata, ledger, r, now);
                continue;
            }

            // ACCEPTED / IN_PROGRESS already cascaded by us: re-arm the
            // craft + deliver tasks so production continues batch-by-batch.
            rearm(level, vdata, gdata, tdata, ledger, r, now);
        }
    }

    // ── First decomposition ─────────────────────────────────────────────────

    private static void decompose(ServerLevel level, RequestBoard board,
                                  BusinessSavedData bdata, VillageSavedData vdata,
                                  GuildSavedData gdata, TaskSavedData tdata,
                                  RequestCascadeLedger ledger, Request r, long now) {
        Item item = r.target().targetItem();
        int totalCount = Math.max(1, r.target().targetCount());
        if (item == null) return;

        Profession craft = producingProfession(item);
        if (craft == null) return; // SKIP — leave OPEN; ticker may escalate.

        List<Business> matches = matchingBusinesses(bdata, vdata, r.originGuildId(), craft);
        if (matches.isEmpty()) return; // SKIP — no producer registered.

        GlobalPos dest = resolveDestination(level, gdata, vdata, r).orElse(null);
        if (dest == null) return; // SKIP — no hall + no stockpile.

        int[] shares = split(totalCount, matches.size());

        UUID fulfiller = SENTINEL_FULFILLER;
        for (int idx = 0; idx < matches.size(); idx++) {
            Business b = matches.get(idx);
            int share = shares[idx];
            if (share <= 0) continue;
            UUID worker = firstWorker(b);
            if (worker != null && fulfiller.equals(SENTINEL_FULFILLER)) fulfiller = worker;
            armBusiness(tdata, ledger, r.requestId(), b, item, share, dest);
        }

        // Mark ACCEPTED so the abstract ticker's acceptance pass ignores it.
        // The cascade tracks real producers via the ledger; the fulfiller field
        // is only used by RequestSettlement to credit one wallet on completion.
        if (board.accept(r.requestId(), r.originGuildId(), fulfiller, totalCount, now)) {
            LOGGER.info("[RequestCascade] CRAFT {} ({}x{}) decomposed across {} business(es), dest={}",
                    r.requestId(), totalCount, ProductionTaskIds.key(item), matches.size(), dest.pos());
        }
    }

    // ── Re-arm an in-flight cascaded request ────────────────────────────────

    private static void rearm(ServerLevel level, VillageSavedData vdata,
                              GuildSavedData gdata, TaskSavedData tdata,
                              RequestCascadeLedger ledger, Request r, long now) {
        Item item = r.target().targetItem();
        if (item == null) return;

        GlobalPos dest = resolveDestination(level, gdata, vdata, r).orElse(null);
        if (dest == null) return; // destination vanished — wait; do not crash.

        // Re-create the craft/deliver pair for any leg still owing items.
        for (RequestCascadeLedger.Entry leg : ledger.legsForRequest(r.requestId())) {
            if (leg.remaining() <= 0) continue;
            ensureCraftAndDeliver(tdata, ledger, r.requestId(), leg.businessId(),
                    item, leg.remaining(), dest);
        }
    }

    // ── Task authoring (idempotent, stable ids) ─────────────────────────────

    private static void armBusiness(TaskSavedData tdata, RequestCascadeLedger ledger,
                                    UUID requestId, Business b, Item item,
                                    int share, GlobalPos dest) {
        ensureCraftAndDeliver(tdata, ledger, requestId, b.getBusinessId(), item, share, dest);
    }

    /**
     * Ensure a {@code ProvideItem} craft task and a dependent {@code Deliver}
     * task exist on the business board, and the delivery leg is recorded.
     * Stable ids keyed by (requestId, businessId) so a re-run updates in place
     * rather than duplicating; a DONE/missing task is recreated so production
     * continues batch-by-batch until the leg is delivered.
     */
    private static void ensureCraftAndDeliver(TaskSavedData tdata, RequestCascadeLedger ledger,
                                              UUID requestId, UUID businessId, Item item,
                                              int qtyOwed, GlobalPos dest) {
        IssuerRef issuer = new IssuerRef(LevelKind.BUSINESS, businessId);
        TaskBoard boardT = tdata.board(issuer);

        TaskId craftId = ProductionTaskIds.stable(issuer,
                "cascade-craft:" + requestId + ":" + ProductionTaskIds.key(item));
        TaskId deliverId = ProductionTaskIds.stable(issuer,
                "cascade-deliver:" + requestId + ":" + ProductionTaskIds.key(item));

        // Craft task (HIGH so a request outranks routine demand production).
        ensureTask(tdata, boardT, issuer, craftId,
                new Objective.ProvideItem(item, qtyOwed),
                new Priority(TaskPriority.HIGH, 0.9f), List.of());

        // Deliver task, dependent on the craft task.
        ensureTask(tdata, boardT, issuer, deliverId,
                new Objective.Deliver(item, qtyOwed, dest),
                new Priority(TaskPriority.HIGH, 0.85f), List.of(craftId));

        // Record / refresh the delivery leg (preserves deliveredSoFar).
        ledger.put(deliverId.value(), requestId, businessId, qtyOwed);
    }

    /**
     * Upsert a task: re-prioritise if present and still actionable; recreate if
     * absent or terminal (DONE/FAILED) and unclaimed so a fresh batch can run.
     */
    private static void ensureTask(TaskSavedData tdata, TaskBoard boardT, IssuerRef issuer,
                                   TaskId id, Objective obj, Priority pr, List<TaskId> deps) {
        Task existing = boardT.get(id).orElse(null);
        if (existing != null) {
            boolean terminal = existing.assignment().isTerminal();
            boolean claimed = !existing.assignment().claimants().isEmpty();
            if (!terminal) {
                existing.setPriority(pr);
                tdata.markChanged();
                return;
            }
            if (claimed) return; // in flight; leave it
            boardT.remove(id); // terminal + unclaimed — recreate fresh below
        }
        Task t = new Task(id, issuer, obj, pr, TaskFilter.ANY,
                new Assignment(), deps, 0L, null);
        tdata.addTask(issuer, t);
    }

    // ── Resolution helpers ──────────────────────────────────────────────────

    /** The profession whose production spec lists {@code item} as a final
     *  output, or {@code null} if no spec produces it. */
    static Profession producingProfession(Item item) {
        for (ProductionTaskSpec spec : ProducerSpecs.ALL) {
            if (spec.finalOutputs().contains(item)) return spec.profession();
        }
        return null;
    }

    /** Origin-guild businesses whose craft profession matches {@code craft}. */
    static List<Business> matchingBusinesses(BusinessSavedData bdata, VillageSavedData vdata,
                                             UUID originGuildId, Profession craft) {
        List<Business> out = new ArrayList<>();
        for (Business b : bdata.forGuild(originGuildId)) {
            if (!b.isActive()) continue;
            if (BusinessGuildRegistrar.craftProfession(b, vdata) == craft) out.add(b);
        }
        return out;
    }

    /** The guild hall building's origin as a {@link GlobalPos}, else the origin
     *  village's stockpile building, else empty. */
    static Optional<GlobalPos> resolveDestination(ServerLevel level, GuildSavedData gdata,
                                                  VillageSavedData vdata, Request r) {
        AbstractGuild guild = gdata.get(r.originGuildId()).orElse(null);
        if (guild != null) {
            UUID hallId = guild.guildHallBuildingId().orElse(null);
            if (hallId != null) {
                Building hall = vdata.getBuildingById(hallId).orElse(null);
                if (hall != null) {
                    return Optional.of(GlobalPos.of(level.dimension(),
                            hall.getShape().getOrigin()));
                }
            }
        }
        // Fallback: origin village stockpile.
        Building stockpile = vdata.getVillageById(r.originVillageId())
                .flatMap(v -> v.getBuildingIds().stream()
                        .map(vdata::getBuildingById)
                        .filter(Optional::isPresent).map(Optional::get)
                        .filter(b -> b.getType()
                                == tterrag1112.life_in_the_village.Village.Buildings.BuildingType.STOCKPILE)
                        .findFirst())
                .orElse(null);
        if (stockpile != null) {
            return Optional.of(GlobalPos.of(level.dimension(),
                    stockpile.getShape().getOrigin()));
        }
        return Optional.empty();
    }

    /** Even split of {@code total} across {@code n} buckets, remainder to the
     *  first, each bucket &ge; 1 (when total &ge; n; otherwise the first
     *  {@code total} buckets get 1 and the rest 0). */
    static int[] split(int total, int n) {
        int[] out = new int[n];
        if (n <= 0) return out;
        if (total <= n) {
            for (int i = 0; i < total && i < n; i++) out[i] = 1;
            return out;
        }
        int base = total / n;
        int rem = total % n;
        for (int i = 0; i < n; i++) out[i] = base + (i == 0 ? rem : 0);
        return out;
    }

    private static UUID firstWorker(Business b) {
        for (Business.BusinessWorker w : b.getWorkers()) {
            if (w != null && w.npcId() != null) return w.npcId();
        }
        return null;
    }
}
