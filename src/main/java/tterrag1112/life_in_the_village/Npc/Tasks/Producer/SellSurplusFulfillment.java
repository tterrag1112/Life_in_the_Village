package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopVending;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

import java.util.Map;
import java.util.Optional;

/**
 * The generic <b>surplus-sell</b> fulfillment for {@code SellSurplus(item)}.
 * Closes the T1 gap (task-driven producers didn't sell). Reuses the existing
 * vending pipeline verbatim: it fires {@link WorkshopVending#triggerSell},
 * which hands the market trip to the universal {@code SellToMarketBehavior}
 * (memory-gated on {@code CARGO_DESTINATION}) &mdash; nothing about the sell trip
 * is reimplemented here.
 *
 * <p>{@link #canFulfill}: a market is reachable AND
 * {@link WorkshopVending#computeSurplus} is non-empty AND
 * {@link WorkshopVending#isSellTime}. {@link #score}: a flat low score &mdash;
 * the source already issues {@code SellSurplus} at LOW/lowest priority so it
 * sorts below every production / acquisition task on the board, i.e. selling
 * runs only once everything else is satisfied.</p>
 */
public final class SellSurplusFulfillment implements Fulfillment {

    /** Min ticks between sell triggers in the stateless task model. One sell
     *  per day window is the design intent; {@code Long.MIN_VALUE} lastSell +
     *  the day-window predicate already enforce that, this is belt-and-braces. */
    private static final long MIN_SELL_INTERVAL = 0L;

    private final ProductionTaskSpec spec;

    public SellSurplusFulfillment(ProductionTaskSpec spec) {
        this.spec = spec;
    }

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.SellSurplus)) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != spec.profession()) return false;

        ServerLevel level = ctx.level();
        Building building = building(level, npc).orElse(null);
        if (building == null) return false;
        Building market = ProductionHelpers.findMarketInVillage(npc, level).orElse(null);
        if (market == null) return false;

        // Sell window open? (stateless lastSell -> always eligible within window)
        if (!WorkshopVending.isSellTime(level.getGameTime(), Long.MIN_VALUE,
                spec.sellWindowDayTick(), MIN_SELL_INTERVAL)) {
            return false;
        }
        // Any actual surplus to sell?
        Map<net.minecraft.world.item.Item, Integer> surplus = WorkshopVending.computeSurplus(
                level, building, spec.sellableOutputs(), quotasWithBuffer(),
                ContextProductionBehavior.DEFAULT_SURPLUS_THRESHOLD);
        return !surplus.isEmpty();
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        // Lowest of the producer strategies; board Priority does the ordering.
        return 1.0;
    }

    @Override
    public TaskExecutor executor() {
        return new SellExecutor(spec);
    }

    private Optional<Building> building(ServerLevel level, TownspersonMob npc) {
        return ProductionHelpers.findAssignedBuilding(npc, level, spec.buildingType());
    }

    /** Keep-floors = quota + buffer per sellable output, matching the surplus
     *  threshold the source uses to issue SellSurplus tasks. */
    private Map<net.minecraft.world.item.Item, Integer> quotasWithBuffer() {
        java.util.Map<net.minecraft.world.item.Item, Integer> q = new java.util.LinkedHashMap<>();
        for (var item : spec.sellableOutputs()) {
            int quota = spec.quota(item);
            if (quota > 0) q.put(item, quota + Math.max(0, spec.buffer(item)));
        }
        return q;
    }

    /**
     * One-shot trigger executor: fire the existing sell hand-off once, then
     * report DONE. The universal {@code SellToMarketBehavior} runs the actual
     * market trip; this task's job is only to TRIGGER it. The next source
     * refresh re-evaluates surplus and re-issues / removes the SellSurplus task.
     */
    private static final class SellExecutor implements TaskExecutor {
        private final ProductionTaskSpec spec;
        private boolean triggered;

        SellExecutor(ProductionTaskSpec spec) { this.spec = spec; }

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            if (triggered) return Result.DONE;
            TownspersonMob npc = ctx.npc().orElse(null);
            if (npc == null) return Result.FAILED;
            ServerLevel level = ctx.level();
            Building market = ProductionHelpers.findMarketInVillage(npc, level).orElse(null);
            if (market == null) return Result.FAILED;
            WorkshopVending.triggerSell(npc, market, level);
            triggered = true;
            return Result.DONE;
        }
    }
}
