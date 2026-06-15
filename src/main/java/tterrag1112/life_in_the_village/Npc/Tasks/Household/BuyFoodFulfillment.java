package tterrag1112.life_in_the_village.Npc.Tasks.Household;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.AcquireObjectives;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * T2 — the BUY strategy for a household {@code MaintainStock(BREAD)} food task
 * (the "buy" half of bake-vs-buy). The household analogue of the blacksmith's
 * buy-ingots strategy: when the family cannot bake (no baker / no wheat) but has
 * coin, a member fetches food from a MARKET/BAKERY into the HOUSE storage,
 * paying from the household pooled wealth (with a personal-wallet fallback).
 *
 * <h3>Why not {@code WorkshopProcurement.buy}</h3>
 * The shared workshop procurement pipeline keys entirely on the buyer's
 * <em>workplace</em> {@code assignedBuildingId} — it routes through that
 * building's {@code BuildingEconomy} treasury and deposits the goods into the
 * workplace. A household member's food run pays from the <em>household pool</em>
 * and must deposit into the <em>house</em>, neither of which that pipeline can
 * express. So this fulfillment reuses {@code EatMealBehavior}'s self-contained
 * market-find / vendor-pay logic (the only existing market food-buy path)
 * instead — but deposits into the house storage rather than personal inventory.
 *
 * <h3>Score</h3>
 * Flat {@code 2.0} &mdash; below {@link BakeFulfillment} (10.0), so a household
 * that can bake bakes; buying is the fallback when it cannot. Mirrors the
 * smith's buy(2)-under-smelt(10) preference.
 */
public final class BuyFoodFulfillment implements Fulfillment {

    private static final double INTERACT_SQ = 16.0;
    private static final float WALK_SPEED = 0.9f;
    private static final int CLOSE_ENOUGH = 1;

    private static final Set<BuildingType> FOOD_SOURCE_TYPES =
            Set.of(BuildingType.MARKET, BuildingType.BAKERY);

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!isFoodTask(task.objective())) return false;
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        ServerLevel level = ctx.level();
        if (house(level, npc).isEmpty()) return false;
        // Need household funds (pool + wallet) AND a reachable affordable source.
        long funds = householdFunds(level, npc);
        if (funds <= 0) return false;
        return findFoodSource(level, npc, funds) != null;
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return 2.0;
    }

    @Override
    public TaskExecutor executor() {
        return new BuyExecutor();
    }

    // ── Shared resolution helpers ─────────────────────────────────────────────

    private static Optional<Building> house(ServerLevel level, TownspersonMob npc) {
        return npc.getHouseId().flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }

    private static Optional<HouseholdData> household(ServerLevel level, TownspersonMob npc) {
        return npc.getHouseId().flatMap(id -> VillageSavedData.get(level).getHouseholdForBuilding(id));
    }

    /** Spendable household funds for {@code npc}: the pool amount its family role
     *  may withdraw, plus its personal wallet. */
    private static long householdFunds(ServerLevel level, TownspersonMob npc) {
        long wallet = npc.getWallet().toBronze();
        long pool = household(level, npc).map(HouseholdData::getPooledWealth).orElse(0L);
        FamilyRole role = npc.getFamilyRole();
        long poolAllowance = poolAllowance(pool, role);
        return wallet + poolAllowance;
    }

    /** The maximum a {@code role} may draw from a {@code pool} (mirrors
     *  {@link HouseholdData#withdrawFromPool} role limits). */
    private static long poolAllowance(long pool, FamilyRole role) {
        return switch (role) {
            case HEAD    -> pool;
            case SPOUSE  -> (long) (pool * 0.6);
            case ELDERLY -> (long) (pool * 0.4);
            case CHILD   -> Math.min(30L, (long) (pool * 0.15));
            default      -> 0L;
        };
    }

    /** Closest MARKET/BAKERY in the NPC's village with affordable food, or null. */
    private static Building findFoodSource(ServerLevel level, TownspersonMob npc, long funds) {
        String villageName = npc.getAssignedVillageName().orElse(null);
        if (villageName == null) return null;
        VillageSavedData data = VillageSavedData.get(level);
        var village = data.getVillageByName(villageName).orElse(null);
        if (village == null) return null;
        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        if (priceData == null) return null;

        Building closest = null;
        double closestDist = Double.MAX_VALUE;
        for (UUID id : village.getBuildingIds()) {
            Building b = data.getBuildingById(id).orElse(null);
            if (b == null || !FOOD_SOURCE_TYPES.contains(b.getType())) continue;
            if (cheapestFood(level, b, priceData, funds) == null) continue;
            BlockPos origin = b.getShape().getOrigin();
            double dist = npc.distanceToSqr(origin.getX(), origin.getY(), origin.getZ());
            if (dist < closestDist) { closestDist = dist; closest = b; }
        }
        return closest;
    }

    /** The cheapest in-stock affordable food item at {@code source}, or null. */
    private static Item cheapestFood(ServerLevel level, Building source,
                                     MarketPriceData priceData, long funds) {
        Item best = null;
        long bestPrice = Long.MAX_VALUE;
        for (Map.Entry<Item, MarketPriceData.ItemPrice> e : priceData.getAllPrices().entrySet()) {
            Item item = e.getKey();
            long price = e.getValue().sellPrice();
            if (!isFood(item)) continue;
            if (price > funds) continue;
            if (foodCount(level, source, item) <= 0) continue;
            if (price < bestPrice) { bestPrice = price; best = item; }
        }
        return best;
    }

    // ── Market-aware count / take (mirrors EatMealBehavior) ──────────────────

    private static int foodCount(ServerLevel level, Building source, Item item) {
        if (source.getType() == BuildingType.MARKET) {
            return MarketInventory.countItem(level, source, item);
        }
        return BuildingStorageAccess.countItem(level, source, item);
    }

    private static boolean foodTake(ServerLevel level, Building source, Item item, int count) {
        if (source.getType() == BuildingType.MARKET) {
            return MarketInventory.takeItem(level, source, item, count);
        }
        return BuildingStorageAccess.takeItem(level, source, item, count);
    }

    private static TownspersonMob findAssignedNpc(ServerLevel level, Building building) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                building.getShape().toAABB().inflate(16),
                mob -> building.getId().equals(mob.getAssignedBuildingId().orElse(null))
        ).stream().findFirst().orElse(null);
    }

    private static boolean isFood(Item item) {
        return item.getDefaultInstance().has(DataComponents.FOOD);
    }

    private static boolean isFoodTask(Objective obj) {
        Item item = AcquireObjectives.itemOf(obj).orElse(null);
        return item == HouseholdFood.FOOD_ITEM && obj instanceof Objective.MaintainStock;
    }

    // ── Executor: walk to the chosen source, then buy into the house ─────────

    /**
     * Walks the acting member to a MARKET/BAKERY and buys one food item into the
     * HOUSE storage, paying from the household pool (wallet fallback). One unit
     * per run keeps the per-trip cost low and lets the source re-emit the task
     * on the next refresh if the household is still short, exactly like the
     * producer's one-shot buy orders.
     */
    private static final class BuyExecutor implements TaskExecutor {
        private Building source;
        private boolean resolved;

        @Override
        public Result tick(Task task, TaskActor actor, TaskContext ctx) {
            TownspersonMob npc = ctx.npc().orElse(null);
            if (npc == null) return Result.FAILED;
            ServerLevel level = ctx.level();

            if (!resolved) {
                // Don't fight another behavior for navigation control.
                if (!BrainNavGuard.canSteerNavigation(npc)) return Result.FAILED;
                long funds = householdFunds(level, npc);
                source = findFoodSource(level, npc, funds);
                resolved = true;
                if (source == null) return Result.FAILED;
                navigateTo(npc, source.getShape().getOrigin());
                return Result.RUNNING;
            }
            if (source == null) return Result.FAILED;

            BlockPos target = source.getShape().getOrigin();
            double distSq = npc.distanceToSqr(target.getX(), npc.getY(), target.getZ());
            if (distSq > INTERACT_SQ) {
                if (!npc.getNavigation().isInProgress()) navigateTo(npc, target);
                return Result.RUNNING;
            }
            npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            boolean bought = buyOneIntoHouse(level, npc, source);
            return bought ? Result.DONE : Result.FAILED;
        }

        private static void navigateTo(TownspersonMob npc, BlockPos pos) {
            npc.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(pos, WALK_SPEED, CLOSE_ENOUGH));
        }

        /**
         * Buy the cheapest affordable food and deposit it into the house. Pays
         * from the household pool first (debited to the member's wallet so the
         * vendor payment / wallet-spend uses one money path), falling back to
         * the member's own wallet. Mirrors {@code EatMealBehavior.tryBuyFood}
         * but deposits into house storage, not personal inventory.
         */
        private static boolean buyOneIntoHouse(ServerLevel level, TownspersonMob npc, Building source) {
            Building house = house(level, npc).orElse(null);
            if (house == null) return false;
            MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
            if (priceData == null) return false;

            long funds = householdFunds(level, npc);
            Item item = cheapestFood(level, source, priceData, funds);
            if (item == null) return false;
            long price = priceData.getAllPrices().get(item).sellPrice();
            CurrencyValue cost = CurrencyValue.of(price);

            // Fund the wallet from the household pool if the wallet is short.
            ensureWalletFunded(level, npc, price);
            if (!npc.getWallet().canAfford(cost)) return false;

            // Take the goods from the source (market stall or building bounds).
            if (!foodTake(level, source, item, 1)) return false;

            // Pay: prefer the assigned vendor (NPC-to-NPC), else spend to the void
            // (no vendor present) — same fork as EatMealBehavior.
            TownspersonMob vendor = findAssignedNpc(level, source);
            if (vendor != null) {
                NpcEconomy.npcPay(npc, vendor, cost, level);
            } else {
                npc.getWallet().spend(cost);
            }

            // Deposit into the HOUSE storage (the household food store).
            BuildingStorageAccess.storeWithFallback(level, house,
                    new ItemStack(item, 1), npc.getPersonalInventory());
            return true;
        }

        /** Top the member's wallet up from the household pool (within its role
         *  allowance) so a single pay path covers {@code price}. */
        private static void ensureWalletFunded(ServerLevel level, TownspersonMob npc, long price) {
            if (npc.getWallet().canAfford(price)) return;
            HouseholdData household = household(level, npc).orElse(null);
            if (household == null) return;
            long need = price - npc.getWallet().toBronze();
            if (need <= 0) return;
            if (household.withdrawFromPool(need, npc.getFamilyRole())) {
                npc.getWallet().receive(need);
                VillageSavedData.get(level).markDirty();
            }
        }
    }
}
