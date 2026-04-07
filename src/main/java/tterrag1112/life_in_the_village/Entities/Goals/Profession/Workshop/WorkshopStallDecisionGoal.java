package tterrag1112.life_in_the_village.Entities.Goals.Profession.Workshop;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;

import java.util.EnumSet;

/**
 * Runs once per in-game day during the NPC's social window.
 * Decides whether the NPC should rent a stall, renew an existing one,
 * or let it lapse and fall back to selling via the market merchant.
 *
 * <h3>Decision logic</h3>
 * <pre>
 * hasStall + canAffordRenewal → extend rent, do nothing else
 * hasStall + cannotAfford     → let it expire naturally (MarketRentManager handles reclaim)
 * noStall  + canAffordBuffer  → attempt to claim the next free slot
 * noStall  + cannotAfford     → no-op, sells via SellToMarketGoal instead
 * </pre>
 *
 * <h3>Wealth buffer</h3>
 * The NPC must hold at least one week's rent as a buffer before renting.
 * This prevents an NPC from renting a stall they'll immediately lose the
 * next day because they spent their coins on something else.
 *
 * <h3>Registration</h3>
 * Added at P_SOCIAL_LOW for all production professions in
 * {@code ProfessionGoalFactory}. The goal runs non-exclusively
 * (no MOVE/LOOK flags) so it doesn't interrupt other social goals.
 */
public class WorkshopStallDecisionGoal extends Goal {

    /** How often to evaluate the stall decision (once per day). */
    private static final long CHECK_INTERVAL = 24000L;
    /** Minimum wealth buffer: one week's rent before renting. */
    private static final long WEALTH_BUFFER_DAYS = 7L;

    private final TownspersonMob entity;
    private long lastCheckTick = -CHECK_INTERVAL; // fire on first eligible day

    public WorkshopStallDecisionGoal(TownspersonMob entity) {
        this.entity = entity;
        // Non-exclusive — never blocks movement or combat goals
        setFlags(EnumSet.noneOf(Flag.class));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        // Only during social time — don't interrupt work
        if (!entity.isSocialTime()) return false;
        // Dedicated market seller role means stall is handled by WorkshopRoleAssigner
        // — no autonomous decision needed
        if (ProfessionRoleManager.isMarketSeller(entity)) return false;
        long tick = level.getGameTime();
        return tick - lastCheckTick >= CHECK_INTERVAL;
    }

    @Override
    public boolean canContinueToUse() { return false; } // one-shot per trigger

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        lastCheckTick = level.getGameTime();
        evaluate(level);
    }

    // =========================================================================
    // Decision
    // =========================================================================

    private void evaluate(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);

        // Find the market for this NPC's village
        Building market = findMarket(level, data);
        if (market == null) return;

        boolean hasStall = data.getStallByOwner(entity.getUUID()).isPresent();
        long wealth      = entity.getWealth().toBronze();
        long buffer      = MarketStallPlacer.RENT_PER_DAY * WEALTH_BUFFER_DAYS;

        if (hasStall) {
            // Stall rent is handled automatically by MarketRentManager.
            // Nothing to do here unless we want to voluntarily release it.
            // Future: if the NPC has been losing money, release the stall.
            return;
        }

        // No stall — decide whether to claim one
        if (wealth < buffer) return; // can't afford buffer, skip

        long rentUntil = level.getGameTime() + 24000L; // rent for one day initially

        MarketStallPlacer.claimSlot(
                        level, market, entity.getUUID(),
                        MarketStall.OwnerType.NPC, rentUntil, data)
                .ifPresent(stall -> {
                    data.addMarketStall(stall);
                    MarketStallPlacer.assignGoalIfNpc(level, stall);
                    System.out.println("[WorkshopStallDecisionGoal] "
                            + entity.getNpcName() + " claimed stall "
                            + stall.getSlotIndex() + " in "
                            + market.getName());
                });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Building findMarket(ServerLevel level, VillageSavedData data) {
        return entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(java.util.Optional::isPresent)
                        .map(java.util.Optional::get)
                        .filter(b -> b.getType() == BuildingType.MARKET)
                        .findFirst())
                .orElse(null);
    }
}