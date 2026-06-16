package tterrag1112.life_in_the_village.Npc.Tasks.Business;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopProcurement;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.AcquireObjectives;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PB4b — buy fulfillment for business-issued {@link Objective.Acquire} tasks
 * emitted by the recursive DAG resolver in
 * {@link BusinessProductionTaskSource} when a raw-leaf input is missing and
 * the business treasury can afford it.
 *
 * <h3>Purpose</h3>
 * When a recipe input has no recipe of its own (oak_log, iron_ore, diamond,
 * …), the resolver can't craft it — so it emits an {@code Acquire} task.
 * This fulfillment picks that task up and purchases the item from the market
 * via {@link WorkshopProcurement#buyForBusiness}, debiting the business
 * treasury exclusively.
 *
 * <h3>canFulfill gates</h3>
 * <ol>
 *   <li>Objective is {@link Objective.Acquire}.</li>
 *   <li>Task issuer is a {@link LevelKind#BUSINESS} board.</li>
 *   <li>Acting NPC has a {@code businessId} matching the task's issuer.</li>
 *   <li>The business record and work building resolve successfully.</li>
 * </ol>
 * No profession gate, no skill gate: any employee of the business can run
 * a market errand (COMMERCE skill affects discount via
 * {@link tterrag1112.life_in_the_village.Village.Economy.Currency.CommerceModifier},
 * not eligibility).
 *
 * <h3>Mutual exclusivity with producer {@link tterrag1112.life_in_the_village.Npc.Tasks.Producer.BuyFulfillment}</h3>
 * {@code BuyFulfillment} gates on {@code npc.getProfession() == spec.profession()},
 * where every spec is a real craft profession — never {@code COMPANY_WORKER}.
 * A skilled-profession NPC hired into a business has a real profession
 * (BLACKSMITH, CARPENTER, …) so it could in theory match {@code BuyFulfillment},
 * but {@code BuyFulfillment} additionally checks {@code spec.acquirableInputs(npc)}
 * which is scoped to the spec's own intermediate items and is not aware of
 * business-issued tasks.  More decisively, the {@code Assignment} claim is
 * exclusive: one claimant wins, no double-execution risk.
 *
 * <h3>Executor (one-shot)</h3>
 * Recomputes the shortfall each time (want − currentStock) so a re-activated
 * Acquire after a partial fill only buys the remainder.  Calls
 * {@link WorkshopProcurement#buyForBusiness}; items land in the business
 * building; returns DONE immediately after placing the order (channel
 * machinery is synchronous).
 */
public final class BusinessBuyFulfillment implements Fulfillment {

    // Score: above BuyFulfillment (2.0) and FarmAcquireFulfillment (2.0);
    // below BusinessCraftFulfillment (9.0) — acquire is always lower-priority
    // than craft, but this must beat producer buy strategies on business Acquire
    // tasks to ensure the business treasury is used (not a building economy).
    private static final double SCORE = 3.0;

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        // Only Acquire objectives.
        if (AcquireObjectives.itemOf(task.objective()).isEmpty()) return false;

        // Task must be business-issued.
        IssuerRef issuer = task.issuer();
        if (issuer.level() != LevelKind.BUSINESS) return false;

        // Acting NPC must have a businessId matching this task's business.
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        UUID npcBizId = npc.getBusinessId().orElse(null);
        if (npcBizId == null || !npcBizId.equals(issuer.id())) return false;

        // Business and building must resolve.
        ServerLevel level = ctx.level();
        return resolveBusiness(level, issuer.id()).isPresent()
                && resolveBuilding(level, npc).isPresent();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return SCORE;
    }

    @Override
    public TaskExecutor executor() {
        return new OrderExecutor();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Optional<Business> resolveBusiness(ServerLevel level, UUID businessId) {
        return BusinessSavedData.get(level).getById(businessId);
    }

    private static Optional<Building> resolveBuilding(ServerLevel level, TownspersonMob npc) {
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }

    // ── Executor ──────────────────────────────────────────────────────────────

    /**
     * One-shot buy-order executor.
     *
     * <p>Re-evaluates the shortfall each tick so that a re-activated Acquire
     * (after a partial fill resolved some of the deficit) buys only what is
     * still missing.  Returns {@link Result#DONE} immediately after placing
     * the order — channel execution is synchronous.  If the market has no
     * seller the buy is a no-op (channel declines), the items don't arrive,
     * and the parent DAG node re-blocks on the missing input at the next
     * resolver refresh (benign — the resolver will re-emit the Acquire or
     * leave the parent BLOCKED as appropriate).</p>
     */
    private static final class OrderExecutor implements TaskExecutor {
        private final WorkshopProcurement.DiagFlags diag = new WorkshopProcurement.DiagFlags();
        private boolean placed;

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            if (placed) return Result.DONE;

            TownspersonMob npc = ctx.npc().orElse(null);
            if (npc == null) return Result.FAILED;

            IssuerRef issuer = task.issuer();
            ServerLevel level = ctx.level();

            Business business = resolveBusiness(level, issuer.id()).orElse(null);
            if (business == null) return Result.FAILED;

            Building building = resolveBuilding(level, npc).orElse(null);
            if (building == null) return Result.FAILED;

            Item item = AcquireObjectives.itemOf(task.objective()).orElse(null);
            if (item == null) return Result.FAILED;
            int want = AcquireObjectives.qtyOf(task.objective());
            if (want <= 0) return Result.DONE; // nothing to buy

            // Recompute shortfall so a re-activated task buys only the remainder.
            int stock = BuildingStorageAccess.countItem(level, building, item);
            int shortfall = Math.max(0, want - stock);
            if (shortfall <= 0) {
                placed = true;
                return Result.DONE; // already satisfied (e.g. another employee delivered)
            }

            WorkshopProcurement.buyForBusiness(
                    npc, building, Map.of(item, shortfall),
                    business, level, diag, "BusinessBuy");

            placed = true;
            return Result.DONE;
        }
    }
}
