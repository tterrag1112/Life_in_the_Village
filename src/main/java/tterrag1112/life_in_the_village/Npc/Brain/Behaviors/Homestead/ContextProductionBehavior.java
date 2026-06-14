package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopMultipliers;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopProcurement;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopVending;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Production Architecture M2/R6b/E-S2 — the context-parameterized production
 * primitive. One {@code skill × amenity × recipe → produce → deposit → (sell)}
 * state machine, shared by every production <i>context</i>: the
 * {@link HomeProductionBehavior} (HOME), the {@code MonkProductionBehavior}
 * (MONASTERY), and (E-S2) the converted profession workshops.
 *
 * <p><b>The split (audit's "one primitive, thin context routers"):</b> this base
 * owns the universal skeleton — the gates (not-child, nav), the phase machine
 * (walk to the workstation → produce for the recipe's ticks → consume the
 * recipe's inputs and deposit its output → award the skill XP → optionally
 * hand off surplus to the universal sell behavior), and the amenity / input
 * helpers. Each context subclass implements only {@link #selectPlan}: which
 * skills it exercises, the motive, and the output destination — returning a
 * {@link Plan} or empty.</p>
 *
 * <h3>E-S2 — workshop parity, all opt-in</h3>
 * The enrichments below give a converted profession full parity with the old
 * {@code AbstractProductionBehavior} workshop loop, every piece <b>opt-in</b> and
 * <b>default no-op</b> so HOME and MONK stay byte-identical:
 * <ul>
 *   <li><b>Buy demand</b> — {@link #resourcesToBuy} (default empty): when
 *       non-empty, the primitive routes a {@link WorkshopProcurement#buy} call.</li>
 *   <li><b>Vending</b> — {@link #vendingIntent} (default empty): when present,
 *       after a deposit the primitive computes surplus
 *       ({@link WorkshopVending#computeSurplus}), gates on
 *       {@link WorkshopVending#isSellTime}, and fires
 *       {@link WorkshopVending#triggerSell} so the universal
 *       {@code SellToMarketBehavior} (priority-1 WORK, on every NPC) runs the
 *       market trip.</li>
 *   <li><b>Multipliers + batch</b> — {@link Plan#batchSize} scales the
 *       consume/produce counts; {@link Plan#applyMultipliers} scales
 *       {@code recipe.ticks()} (industry × crafting speed) and rolls a bonus
 *       output ({@link WorkshopMultipliers#craftingQualityChance}).</li>
 *   <li><b>Ledger</b> — {@link Plan#recordLedger} fires
 *       {@link WorkplaceAssignmentManager#onWorkplaceProduction}.</li>
 *   <li><b>Schedule / liveliness</b> — {@link #checkContextGate} (default true)
 *       lets a context add the work-time / work-blocked / role gate; the
 *       {@code NO_ACTIONABLE_WORK} liveliness signal is held while a
 *       sell-enabled context idles (see {@link #onNoPlan}).</li>
 * </ul>
 *
 * <p>HOME and MONK return the 6-arg {@link Plan} (the legacy shape): batch 1, no
 * multipliers, no ledger, no buy, no vending, no context gate. Their phase logic
 * is the verbatim M2 tick logic.</p>
 */
public abstract class ContextProductionBehavior extends Behavior<TownspersonMob> {

    protected static final Logger LOGGER = LogUtils.getLogger();

    // ── Shared skeleton constants (identical across contexts; were M2's) ─────
    private static final double ARRIVAL_DIST_SQ = 4.0;
    private static final float  WALK_SPEED      = 0.7f;
    private static final int    CLOSE_ENOUGH    = 1;
    protected static final int  MAX_RUN         = 24000;

    /** E-S2 — default per-item surplus keep-floor when a vending intent gives
     *  no per-item quota. Mirrors {@code AbstractProductionBehavior
     *  .DEFAULT_SURPLUS_THRESHOLD} (8). */
    public static final int DEFAULT_SURPLUS_THRESHOLD = 8;
    /** E-S2 — minimum ticks between successive sales. Mirrors APB's
     *  {@code MIN_SELL_INTERVAL}. */
    private static final long MIN_SELL_INTERVAL = 20000L;

    /**
     * A selected production run: the recipe to run, at {@code workstationPos}
     * (null = no workstation, produce in place), consuming/depositing in
     * {@code building}, awarding {@code skill} XP. The context builds this in
     * {@link #selectPlan}.
     *
     * <p>E-S2 widened the record with workshop-parity fields, all defaulted by
     * the legacy 6-arg constructor (batch 1, no multipliers, no ledger) so HOME
     * and MONK construct it exactly as before and behave byte-identically.</p>
     *
     * @param batchSize        units produced per cycle (consume inputs ×
     *                         batchSize, produce outputCount × batchSize). HOME/
     *                         MONK pass 1 via the legacy constructor.
     * @param applyMultipliers when true, scale {@code recipe.ticks()} by industry
     *                         × crafting speed and roll a quality bonus output.
     * @param recordLedger     when true, fire the workplace-production ledger.
     */
    public record Plan(Building building, BlockPos workstationPos,
                       ProductionRecipe recipe, Skill skill,
                       int xpPerBatch, String activityLabel,
                       int batchSize, boolean applyMultipliers, boolean recordLedger) {

        /** Legacy 6-arg shape (HOME / MONK): single batch, no multipliers, no
         *  ledger — byte-identical to the pre-E-S2 record. */
        public Plan(Building building, BlockPos workstationPos,
                    ProductionRecipe recipe, Skill skill,
                    int xpPerBatch, String activityLabel) {
            this(building, workstationPos, recipe, skill, xpPerBatch, activityLabel,
                    1, false, false);
        }
    }

    /**
     * E-S2 — a context's intent to vend surplus after depositing. A context that
     * sells returns one of these from {@link #vendingIntent}; the primitive then
     * runs the same surplus/sell-time/trigger path the APB workshop runs.
     *
     * @param market          the market building to sell at
     * @param sellableOutputs items eligible for sale
     * @param quotas          per-item keep floors (may be empty)
     * @param defaultThreshold fallback keep floor (see {@link #DEFAULT_SURPLUS_THRESHOLD})
     * @param sellWindowDayTick day-tick after which selling is permitted
     */
    public record VendingIntent(Building market, List<Item> sellableOutputs,
                                Map<Item, Integer> quotas, int defaultThreshold,
                                int sellWindowDayTick) {}

    protected enum Phase { WALKING_TO_WORKSTATION, PRODUCING, DEPOSITING, DONE }

    private Phase phase;
    private Plan plan;
    private long startTick;
    private int subTimer;

    /** E-S2 — per-instance sell throttle (mirrors APB's lastDailySellTick). */
    private long lastDailySellTick = Long.MIN_VALUE;
    /** E-S2 — buy diagnostic one-shots, owned by the behavior instance and
     *  passed to {@link WorkshopProcurement#buy} (identical to APB's field). */
    private final WorkshopProcurement.DiagFlags buyDiagFlags = new WorkshopProcurement.DiagFlags();

    protected ContextProductionBehavior() {
        // CARGO_DESTINATION VALUE_ABSENT: stand down while a sell trip is pending
        // (parity with AbstractProductionBehavior — lets the priority-1
        // SellToMarketBehavior take the WORK channel). HOME/MONK never write
        // CARGO_DESTINATION, so this gate is always satisfied for them (no-op).
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.CARGO_DESTINATION.get(), MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    /**
     * Context selection: choose what (if anything) this NPC produces right now.
     * Implementations check their craft table (skill ≥ level + amenity present +
     * inputs available + the context's motive) and return the first qualifying
     * {@link Plan}, or empty to do nothing this tick.
     */
    protected abstract Optional<Plan> selectPlan(ServerLevel level, TownspersonMob entity);

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        // E-S2 — context-supplied gate (schedule / work-blocked / role). Default
        // true keeps HOME/MONK unchanged. When a sell-enabled context is gated
        // out it also records the liveliness signal so the idle director fills in.
        if (!checkContextGate(level, entity)) return false;
        Optional<Plan> selected = selectPlan(level, entity);
        if (selected.isEmpty()) {
            onNoPlan(level, entity);
            return false;
        }
        this.plan = selected.get();
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != null && phase != Phase.DONE
                && (gameTime - startTick) < MAX_RUN;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        startTick = gameTime;
        subTimer = 0;
        entity.setCurrentActivity(plan.activityLabel());
        if (plan.workstationPos() != null) {
            phase = Phase.WALKING_TO_WORKSTATION;
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(plan.workstationPos(), WALK_SPEED, CLOSE_ENOUGH));
        } else {
            phase = Phase.PRODUCING;
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case WALKING_TO_WORKSTATION -> tickWalking(entity);
            case PRODUCING              -> tickProducing(entity);
            case DEPOSITING             -> tickDepositing(level, entity, gameTime);
            case DONE                   -> { /* canStillUse short-circuits */ }
        }
    }

    private void tickWalking(TownspersonMob entity) {
        BlockPos pos = plan.workstationPos();
        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.PRODUCING;
            subTimer = 0;
            return;
        }
        // Safety abort: nav idled for 600 ticks without arriving.
        if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickProducing(TownspersonMob entity) {
        BlockPos pos = plan.workstationPos();
        if (pos != null) {
            entity.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        }
        if (subTimer >= productionTicks(entity)) {
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    /**
     * E-S2 — the number of ticks this cycle takes. Legacy (HOME/MONK) plans use
     * the raw {@code recipe.ticks()}; an {@code applyMultipliers} plan scales by
     * industry × (1 / crafting-speed) like the APB workshop, floored at 1.
     */
    private int productionTicks(TownspersonMob entity) {
        int base = plan.recipe().ticks();
        if (!plan.applyMultipliers()) return base;
        int batch = Math.max(1, plan.batchSize());
        // Mirrors AbstractProductionBehavior.buildSteps: ticks × batch × industry
        // × role / crafting-speed, floored at 20 (APB's Math.max(20, ...)).
        float scaled = base * batch
                * WorkshopMultipliers.industrySpeedMultiplier(entity)
                * roleSpeedMultiplier(entity)
                / WorkshopMultipliers.craftingSpeedMultiplier(entity);
        return Math.max(20, (int) scaled);
    }

    /**
     * E-S2 — workshop-role tick multiplier (APPRENTICE works slower). Default
     * 1.0; a profession context overrides to replicate
     * {@code AbstractProductionBehavior.roleSpeedMultiplier}. Only consulted on
     * an {@code applyMultipliers} plan, so HOME/MONK never see it.
     */
    protected float roleSpeedMultiplier(TownspersonMob entity) {
        return 1.0f;
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ProductionRecipe recipe = plan.recipe();
        Building building = plan.building();
        boolean legacy = plan.batchSize() == 1 && !plan.applyMultipliers();
        int batch = Math.max(1, plan.batchSize());
        // Consume all inputs × batch — multi-input via takeItem per entry.
        for (Map.Entry<Item, Integer> e : recipe.inputs().entrySet()) {
            if (!BuildingStorageAccess.takeItem(level, building, e.getKey(), e.getValue() * batch)) {
                // An input vanished mid-craft (consumed elsewhere). Abort cleanly.
                phase = Phase.DONE;
                return;
            }
        }

        if (legacy) {
            // HOME / MONK byte-exact path: store the producedStack verbatim (its
            // count is the recipe's outputCount, or the override's own count for
            // the monk's scripture). No byproducts, no quality roll — identical to
            // the pre-E-S2 primitive.
            ItemStack output = producedStack(level, entity, building, recipe);
            BuildingStorageAccess.storeWithFallback(level, building, output,
                    entity.getPersonalInventory());
        } else {
            // E-S2 enriched path (converted professions): batch-scaled output,
            // fixed byproducts (per run), and the crafting quality bonus.
            ItemStack output = producedStack(level, entity, building, recipe);
            output.setCount(recipe.outputCount() * batch);
            BuildingStorageAccess.storeWithFallback(level, building, output,
                    entity.getPersonalInventory());
            for (ItemStack byproduct : recipe.byproducts()) {
                BuildingStorageAccess.storeWithFallback(level, building,
                        byproduct.copy(), entity.getPersonalInventory());
            }
            float qualityChance = WorkshopMultipliers.craftingQualityChance(entity);
            if (qualityChance > 0 && entity.getRandom().nextFloat() < qualityChance) {
                BuildingStorageAccess.storeWithFallback(level, building,
                        new ItemStack(recipe.output(), batch), entity.getPersonalInventory());
            }
        }

        SkillXp.award(entity, plan.skill(), plan.xpPerBatch(), gameTime);

        // E-S2 — workplace-production ledger hook (opt-in).
        if (plan.recordLedger()) {
            WorkplaceAssignmentManager.onWorkplaceProduction(level, building.getId(),
                    BuiltInRegistries.ITEM.getKey(recipe.output()).toString(),
                    recipe.outputCount() * batch);
        }

        LOGGER.info("[{}] {} ({} {}) made {}x {}; building stock now {}",
                getClass().getSimpleName(), entity.getNpcName(), plan.skill(),
                entity.getSkills().getLevel(plan.skill()),
                recipe.outputCount() * batch, recipe.output(),
                BuildingStorageAccess.countItem(level, building, recipe.output()));

        // E-S2 — vending: after depositing, hand surplus off to the universal
        // SellToMarketBehavior (opt-in; HOME/MONK return empty, so this is a
        // no-op for them). On a successful trigger CARGO_DESTINATION is set, so
        // when we DONE this tick and stop, canStillUse returns false and the
        // priority-1 sell behavior takes the WORK channel.
        tryVend(level, entity, gameTime);
        phase = Phase.DONE;
    }

    /**
     * E-S2 — surplus → sell hand-off, mirroring the APB workshop's deposit-tail.
     * Returns true if the sell trigger fired (so the caller yields). No-op (false)
     * for any context that returns no {@link #vendingIntent} (HOME / MONK).
     */
    private boolean tryVend(ServerLevel level, TownspersonMob entity, long gameTime) {
        Optional<VendingIntent> vi = vendingIntent(level, entity, plan);
        if (vi.isEmpty()) return false;
        VendingIntent intent = vi.get();
        if (intent.market() == null) return false;
        if (!WorkshopVending.isSellTime(gameTime, lastDailySellTick,
                intent.sellWindowDayTick(), MIN_SELL_INTERVAL)) {
            return false;
        }
        Map<Item, Integer> surplus = WorkshopVending.computeSurplus(level,
                plan.building(), intent.sellableOutputs(), intent.quotas(),
                intent.defaultThreshold());
        if (surplus.isEmpty()) return false;
        lastDailySellTick = gameTime;
        WorkshopVending.triggerSell(entity, intent.market(), level);
        return true;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        // No persistent cooldown — eligibility gates (output at quota, motive
        // lapsed, recipe cycle finished) naturally prevent immediate re-fire.
    }

    // ── E-S2 opt-in hooks (default no-op → HOME/MONK unchanged) ──────────────

    /**
     * Context-supplied start gate, beyond the universal not-child / nav gates.
     * Default true (HOME/MONK add no extra gate). A profession context returns
     * the work-time / work-blocked / role gate here; it should also surface the
     * liveliness blocking reason itself when it returns false.
     */
    protected boolean checkContextGate(ServerLevel level, TownspersonMob entity) {
        return true;
    }

    /**
     * Called when {@link #selectPlan} returns empty (no runnable work this tick).
     * Default no-op. A sell-enabled context overrides to (a) attempt input
     * procurement via {@link #procure} and (b) hold the {@code NO_ACTIONABLE_WORK}
     * liveliness signal so the WORK idle director fills the gap — the same
     * semantics APB's {@code goIdle("...")} carries.
     */
    protected void onNoPlan(ServerLevel level, TownspersonMob entity) {
    }

    /**
     * The buy-side demand for a context that procures inputs. Default empty
     * (HOME/MONK never buy). A profession context returns the item → quantity
     * map; {@link #procure} routes it through the shared ChannelRouter pipeline.
     */
    protected Map<Item, Integer> resourcesToBuy(ServerLevel level, TownspersonMob entity,
                                                Building workBuilding) {
        return Map.of();
    }

    /**
     * Route {@code toBuy} through {@link WorkshopProcurement#buy} with this
     * behavior instance's diagnostic flags. Contexts call this from
     * {@link #onNoPlan} (or wherever they detect missing inputs).
     */
    protected final void procure(ServerLevel level, TownspersonMob entity,
                                 Building workBuilding, Map<Item, Integer> toBuy) {
        if (toBuy.isEmpty()) return;
        WorkshopProcurement.buy(entity, workBuilding, toBuy, level,
                buyDiagFlags, getClass().getSimpleName());
    }

    /**
     * The vending intent for this cycle, or empty for a non-selling context.
     * Default empty (HOME/MONK never vend). A profession context returns the
     * market + sellable list + quotas so the primitive can hand surplus off to
     * the universal sell behavior after depositing.
     */
    protected Optional<VendingIntent> vendingIntent(ServerLevel level, TownspersonMob entity,
                                                    Plan plan) {
        return Optional.empty();
    }

    // ── Shared selection helpers (used by subclasses' selectPlan) ────────────

    /**
     * The ItemStack a completed cycle deposits — by default the recipe's plain
     * output (count overwritten by the batch multiplier in the deposit phase). A
     * context may override to produce a special stack (D3: the monk's
     * COPY_MANUSCRIPT deposits the monastery's faith scripture). HOME does not
     * override, so it stays byte-exact.
     */
    protected ItemStack producedStack(ServerLevel level, TownspersonMob entity,
                                      Building building, ProductionRecipe recipe) {
        return new ItemStack(recipe.output(), recipe.outputCount());
    }

    protected static boolean hasAllInputs(ServerLevel level, Building b, ProductionRecipe recipe) {
        for (Map.Entry<Item, Integer> e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, b, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** First matching block pos across the amenity types, in preference order
     *  (empty list ⇒ null = no workstation needed). Delegates to the single
     *  amenity-scan home {@link AmenityType#firstPresent}. */
    protected static BlockPos firstAmenityPos(ServerLevel level, Building b,
                                              List<AmenityType> types) {
        return AmenityType.firstPresent(level, b, types);
    }
}
