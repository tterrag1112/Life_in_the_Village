package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WorkshopVending;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;

/**
 * G1b — surplus-sell fulfillment for FARMER NPCs.
 *
 * <p>Handles {@link Objective.SellSurplus} tasks emitted by
 * {@link FarmTaskSource} when farmhouse stock exceeds a per-crop keep-floor.
 * Reuses the existing sell pipeline verbatim: fires
 * {@link WorkshopVending#triggerSell}, which writes
 * {@code CARGO_DESTINATION + WORK_PHASE=SELL}; the universal
 * {@code SellToMarketBehavior} then walks to the market and sells everything
 * in the farmhouse above a flat floor of 8.
 *
 * <p>This is intentionally spec-free — the farmer has no
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskSpec}
 * and doesn't need one. The legacy {@code FarmerBehavior.tryHandOffToSell}
 * used exactly the same {@link WorkshopVending#triggerSell} one-shot pattern
 * (it wrote the same memories), so the behavior is faithful.</p>
 *
 * <p>Coexists with
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.Producer.SellSurplusFulfillment}
 * under {@link Objective.Type#SELL_SURPLUS}: {@link #canFulfill} gates on
 * {@code profession == FARMER} so the two never cross-intercept.</p>
 */
public final class FarmSellFulfillment implements Fulfillment {

    /**
     * Sell time: no time-of-day gate (mirrors legacy tryHandOffToSell).
     * Pass Long.MIN_VALUE so isSellTime's "today" check always passes.
     * SellToMarketBehavior.stop() writes LAST_SELL_TICK with a 600-tick
     * expiry to prevent rapid re-triggering independently.
     */
    private static final int  SELL_WINDOW_DAY_TICK = 0;
    private static final long MIN_SELL_INTERVAL    = 0L;

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.SellSurplus)) return false;

        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null || npc.getProfession() != Profession.FARMER) return false;

        ServerLevel level = ctx.level();

        // Must have an assigned farmhouse
        boolean hasFarmhouse = npc.getAssignedBuildingId()
                .flatMap(id -> tterrag1112.life_in_the_village.Networking.VillageSavedData
                        .get(level).getBuildingById(id))
                .map(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(false);
        if (!hasFarmhouse) return false;

        // A market must be reachable
        if (ProductionHelpers.findMarketInVillage(npc, level).isEmpty()) return false;

        // Sell window open (no time-of-day gate; always true with these constants)
        return WorkshopVending.isSellTime(
                level.getGameTime(), Long.MIN_VALUE,
                SELL_WINDOW_DAY_TICK, MIN_SELL_INTERVAL);
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 1.0; // Matches SellSurplusFulfillment; board Priority handles ordering
    }

    @Override
    public TaskExecutor executor() {
        return new SellExecutor();
    }

    /**
     * One-shot trigger: fire triggerSell once then return DONE.
     * The universal SellToMarketBehavior owns the market-trip lifecycle.
     */
    private static final class SellExecutor implements TaskExecutor {
        private boolean triggered;

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
