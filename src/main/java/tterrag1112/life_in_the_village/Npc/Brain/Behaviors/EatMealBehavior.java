package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.WorkSchedule;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcTransactionVisual;
import tterrag1112.life_in_the_village.Village.Needs.FoodValueHelper;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6.2.a — migrated from {@code EatMealGoal}. SOCIAL activity,
 * priority 1. Replaces the goal's {@code ateThisWindow} flag with a
 * {@code MEAL_COOLDOWN} TTL memory (TTL covers the meal window).
 *
 * <p>External APIs preserved 1:1 — food source scanning, market price
 * lookup, NPC payment routing, building storage access.
 */
public class EatMealBehavior extends Behavior<TownspersonMob> {

    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    /** Game-tick of the last food-access failure we DEBUG-logged for this NPC.
     *  Per-NPC because a fresh EatMealBehavior is constructed for each NPC's
     *  brain. Guards the diagnostic to once per failure episode (a meal window
     *  that ends with "No food available") rather than once per tick. A plain
     *  long timestamp — deliberately NOT a brain MemoryModuleType, per the
     *  registration trap. */
    private long lastFoodFailLogTick = Long.MIN_VALUE;

    private static final int EAT_DURATION = 80;
    private static final double HOME_CLOSE_SQ = 256.0;
    private static final double INTERACT_SQ = 16.0;
    private static final long MIN_WEALTH_BRONZE = 20L;
    /** TTL for the meal cooldown — long enough to cover the rest of the
     *  current meal window so we don't re-fire after a successful eat. */
    private static final long COOLDOWN_TICKS = 4000L;
    private static final float WALK_SPEED = 0.9f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = 6000;

    private static final Set<BuildingType> FOOD_SOURCE_TYPES = Set.of(
            BuildingType.MARKET, BuildingType.BAKERY);

    private enum Phase { DETOUR, WALKING_HOME, EATING, DONE }

    private Phase phase = Phase.DONE;
    private int eatTimer = 0;
    private Building detourTarget = null;

    public EatMealBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.MEAL_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        // R6d — a monk has no household; it eats from its monastery shared store
        // (getHomeBuilding resolves to the monastery for a monk), so allow it
        // through the no-home gate when a food-home building exists.
        if (!entity.hasHome() && getHomeBuilding(level, entity).isEmpty()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return WorkSchedule.isMealTime(entity);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != Phase.DONE && WorkSchedule.isMealTime(entity);
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.DONE;
        eatTimer = 0;
        detourTarget = null;

        if (hasPersonalFood(entity)) {
            entity.setCurrentActivity("Eating");
            phase = Phase.EATING;
            return;
        }

        Optional<BlockPos> homePos = getHomePosition(level, entity);
        boolean homeHasFood = homePos.isPresent()
                && hasFoodInStorage(level, getHomeBuilding(level, entity).orElse(null));
        boolean closeToHome = homePos.isPresent()
                && entity.distanceToSqr(
                        homePos.get().getX(), homePos.get().getY(), homePos.get().getZ())
                        <= HOME_CLOSE_SQ;

        if (closeToHome && homeHasFood) {
            phase = Phase.WALKING_HOME;
            entity.setCurrentActivity("Heading home to eat");
            navigateToHome(level, entity);
            return;
        }

        Building source = findAnyFoodSource(level, entity);
        if (source != null) {
            detourTarget = source;
            phase = Phase.DETOUR;
            entity.setCurrentActivity("Buying lunch");
            navigateTo(entity, source.getShape().getOrigin());
            return;
        }

        if (homeHasFood) {
            phase = Phase.WALKING_HOME;
            entity.setCurrentActivity("Heading home to eat");
            navigateToHome(level, entity);
            return;
        }

        logFoodAccessFailure(level, entity, gameTime, homeHasFood);
        entity.setCurrentActivity("No food available");
        phase = Phase.DONE;
    }

    /**
     * DEBUG-gated, once-per-episode diagnostic for the "merchant sells food
     * but the NPC reports no food" complaint (perf-noon-social-food). Walks the
     * same MARKET/BAKERY candidates {@link #findAnyFoodSource} considers and
     * records why each was rejected — wealth gate, no affordable food in the
     * building's scanned containers, etc. Crucially it also reports the stall
     * chests registered to each MARKET via {@code getStallsForMarket}: if a
     * merchant has stocked its STALL chest but that chest sits OUTSIDE the
     * MARKET building's getShape() box, {@link BuildingStorageAccess#countItem}
     * (which only scans the building bounds) sees an empty market — the
     * suspected latent bug. The line names that mismatch so the next test
     * confirms or rules it out.
     *
     * <p>No per-tick spam: only fires from the failure branch of {@code start()}
     * (once per meal window, gated by MEAL_COOLDOWN) and additionally guards on
     * {@link #lastFoodFailLogTick}. Entirely skipped unless DEBUG logging is on.
     */
    private void logFoodAccessFailure(ServerLevel level, TownspersonMob entity,
                                      long gameTime, boolean homeHasFood) {
        if (!LOGGER.isDebugEnabled()) return;
        if (gameTime - lastFoodFailLogTick < 200L) return; // de-dupe within a window
        lastFoodFailLogTick = gameTime;

        long wealth = CoinHelper.getWealth(entity.getPersonalInventory()).toBronze();
        String villageName = entity.getAssignedVillageName().orElse(null);
        VillageSavedData data = VillageSavedData.get(level);
        var village = villageName == null
                ? null : data.getVillageByName(villageName).orElse(null);

        StringBuilder sb = new StringBuilder();
        sb.append("[FoodAccess] npc=").append(entity.getNpcName())
          .append(" village=").append(villageName)
          .append(" wealth=").append(wealth).append('b')
          .append(" minWealthGate=").append(MIN_WEALTH_BRONZE).append('b')
          .append(" personalFood=").append(hasPersonalFood(entity))
          .append(" homeHasFood=").append(homeHasFood);

        if (wealth < MIN_WEALTH_BRONZE) {
            sb.append(" -> REJECTED: below min-wealth gate (findAnyFoodSource "
                    + "returns null before scanning any source)");
        }
        if (village == null) {
            sb.append(" -> REJECTED: no assigned village");
            LOGGER.debug(sb.toString());
            return;
        }

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        int sources = 0;
        for (UUID id : village.getBuildingIds()) {
            Building b = data.getBuildingById(id).orElse(null);
            if (b == null || !FOOD_SOURCE_TYPES.contains(b.getType())) continue;
            sources++;
            boolean affordable = hasFoodAffordable(level, entity, b);
            sb.append(" | src=").append(b.getType())
              .append('@').append(b.getShape().getOrigin())
              .append(" affordableFoodInBuildingBounds=").append(affordable);

            if (b.getType() == BuildingType.MARKET) {
                var stalls = data.getStallsForMarket(b.getId());
                int withFood = 0;
                for (var stall : stalls) {
                    if (!stall.isActive()) continue;
                    var be = level.getBlockEntity(stall.getChestPos());
                    if (!(be instanceof net.minecraft.world.Container chest)) continue;
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        var st = chest.getItem(i);
                        if (!st.isEmpty() && isFood(st.getItem())) { withFood++; break; }
                    }
                }
                sb.append(" stalls=").append(stalls.size())
                  .append(" stallChestsWithFood=").append(withFood);
                if (withFood > 0 && !affordable) {
                    sb.append(" !! STALL-CHEST FOOD NOT SEEN BY BUILDING-BOUNDS "
                            + "SCAN — chestPos likely outside MARKET getShape(); "
                            + "this is the suspected stall-vs-building inventory bug");
                }
            }
        }
        if (sources == 0) sb.append(" | no MARKET/BAKERY buildings in village");
        if (priceData == null) sb.append(" | WARN: MarketPriceRegistry default is null");

        LOGGER.debug(sb.toString());
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        switch (phase) {
            case DETOUR -> tickDetour(level, entity);
            case WALKING_HOME -> tickWalkingHome(level, entity);
            case EATING -> tickEating(entity);
            case DONE -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        // Stamp the cooldown if we actually ate (or tried to and got DONE) —
        // matches the Goal's "ateThisWindow=true when phase reached DONE/EATING".
        if (phase == Phase.DONE || phase == Phase.EATING) {
            entity.getBrain().setMemoryWithExpiry(
                    NpcMemoryTypes.MEAL_COOLDOWN.get(), gameTime, COOLDOWN_TICKS);
        }
        phase = Phase.DONE;
        detourTarget = null;
    }

    // ── DETOUR ──────────────────────────────────────────────────────────────
    private void tickDetour(ServerLevel level, TownspersonMob entity) {
        if (detourTarget == null) {
            fallbackToHome(level, entity);
            return;
        }
        BlockPos target = detourTarget.getShape().getOrigin();
        if (entity.distanceToSqr(target.getX(), entity.getY(), target.getZ()) > INTERACT_SQ) {
            if (!entity.getNavigation().isInProgress()) navigateTo(entity, target);
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        boolean bought = tryBuyFood(level, entity, detourTarget);
        detourTarget = null;
        if (bought) {
            entity.setCurrentActivity("Eating");
            phase = Phase.EATING;
        } else {
            fallbackToHome(level, entity);
        }
    }

    // ── WALKING_HOME ────────────────────────────────────────────────────────
    private void tickWalkingHome(ServerLevel level, TownspersonMob entity) {
        Optional<BlockPos> homePos = getHomePosition(level, entity);
        if (homePos.isEmpty()) {
            entity.setCurrentActivity("No food available");
            phase = Phase.DONE;
            return;
        }
        double distSq = entity.distanceToSqr(
                homePos.get().getX(), homePos.get().getY(), homePos.get().getZ());
        if (distSq <= HOME_CLOSE_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            tryEatFromHome(level, entity);
        } else if (!entity.getNavigation().isInProgress()) {
            navigateToHome(level, entity);
        }
    }

    // ── EATING ──────────────────────────────────────────────────────────────
    private void tickEating(TownspersonMob entity) {
        eatTimer++;
        entity.getLookControl().setLookAt(entity.getX(), entity.getY() - 1, entity.getZ());
        if (eatTimer == 1) {
            entity.playSound(SoundEvents.GENERIC_EAT.value(), 0.5f, 1.0f);
            entity.swing(InteractionHand.MAIN_HAND);
        }
        if (eatTimer == EAT_DURATION / 2) {
            entity.playSound(SoundEvents.GENERIC_EAT.value(), 0.3f, 1.1f);
        }
        if (eatTimer >= EAT_DURATION) {
            entity.setCurrentActivity("");
            entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            phase = Phase.DONE;
        }
    }

    // ── Food sourcing / purchase (copied verbatim from EatMealGoal) ─────────
    private Building findAnyFoodSource(ServerLevel level, TownspersonMob entity) {
        if (CoinHelper.getWealth(entity.getPersonalInventory()).toBronze() < MIN_WEALTH_BRONZE) return null;
        String villageName = entity.getAssignedVillageName().orElse(null);
        if (villageName == null) return null;
        VillageSavedData data = VillageSavedData.get(level);
        var village = data.getVillageByName(villageName).orElse(null);
        if (village == null) return null;

        Building closest = null;
        double closestDist = Double.MAX_VALUE;
        for (UUID id : village.getBuildingIds()) {
            Building b = data.getBuildingById(id).orElse(null);
            if (b == null) continue;
            if (!FOOD_SOURCE_TYPES.contains(b.getType())) continue;
            if (!hasFoodAffordable(level, entity, b)) continue;
            BlockPos origin = b.getShape().getOrigin();
            double dist = entity.distanceToSqr(origin.getX(), origin.getY(), origin.getZ());
            if (dist < closestDist) { closestDist = dist; closest = b; }
        }
        return closest;
    }

    private boolean tryBuyFood(ServerLevel level, TownspersonMob entity, Building source) {
        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        if (priceData == null) return false;
        long wealth = CoinHelper.getWealth(entity.getPersonalInventory()).toBronze();
        Item bestItem = null;
        long bestPrice = Long.MAX_VALUE;
        for (Map.Entry<Item, MarketPriceData.ItemPrice> entry : priceData.getAllPrices().entrySet()) {
            Item item = entry.getKey();
            long price = entry.getValue().sellPrice();
            if (!isFood(item)) continue;
            if (price > wealth) continue;
            if (foodCount(level, source, item) <= 0) continue;
            if (price < bestPrice) { bestPrice = price; bestItem = item; }
        }
        if (bestItem == null) return false;
        CurrencyValue cost = CurrencyValue.of(bestPrice);
        if (!entity.canAfford(cost)) return false;
        if (!foodTake(level, source, bestItem, 1)) return false;

        TownspersonMob vendor = findAssignedNpc(level, source);
        if (vendor != null) {
            NpcEconomy.npcPay(entity, vendor, cost, level);
        } else {
            entity.getWallet().spend(cost);
            NpcTransactionVisual.showPayment(entity, level);
        }
        entity.getPersonalInventory().addItem(new ItemStack(bestItem, 1));
        entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(bestItem, 1));
        return true;
    }

    private void tryEatFromHome(ServerLevel level, TownspersonMob entity) {
        if (hasPersonalFood(entity)) {
            consumePersonalFood(entity);
            phase = Phase.EATING;
            eatTimer = 0;
            entity.setCurrentActivity("Eating");
            return;
        }
        Optional<Building> house = getHomeBuilding(level, entity);
        if (house.isPresent()) {
            for (var container : BuildingStorageAccess.findInventories(level, house.get())) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty() || !FoodValueHelper.isFood(stack.getItem())) continue;
                    stack.shrink(1);
                    if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                    phase = Phase.EATING;
                    eatTimer = 0;
                    entity.setCurrentActivity("Eating");
                    return;
                }
            }
        }
        entity.setCurrentActivity("No food available");
        phase = Phase.DONE;
    }

    // ── Helpers (copied from EatMealGoal) ───────────────────────────────────
    private void fallbackToHome(ServerLevel level, TownspersonMob entity) {
        Optional<Building> house = getHomeBuilding(level, entity);
        if (house.isPresent() && hasFoodInStorage(level, house.get())) {
            phase = Phase.WALKING_HOME;
            entity.setCurrentActivity("Heading home to eat");
            navigateToHome(level, entity);
        } else {
            entity.setCurrentActivity("No food available");
            phase = Phase.DONE;
        }
    }

    private static boolean hasPersonalFood(TownspersonMob entity) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && isFood(s.getItem())) return true;
        }
        return false;
    }

    private static void consumePersonalFood(TownspersonMob entity) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !isFood(s.getItem())) continue;
            s.shrink(1);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            return;
        }
    }

    private static boolean hasFoodInStorage(ServerLevel level, Building building) {
        if (building == null) return false;
        for (var container : BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (!s.isEmpty() && FoodValueHelper.isFood(s.getItem())) return true;
            }
        }
        return false;
    }

    private static boolean hasFoodAffordable(ServerLevel level, TownspersonMob entity, Building building) {
        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        if (priceData == null) return hasFoodInStorage(level, building);
        long wealth = CoinHelper.getWealth(entity.getPersonalInventory()).toBronze();
        for (var entry : priceData.getAllPrices().entrySet()) {
            if (!isFood(entry.getKey())) continue;
            if (entry.getValue().sellPrice() > wealth) continue;
            if (foodCount(level, building, entry.getKey()) > 0) return true;
        }
        return false;
    }

    /**
     * Food-source count that respects market-stall-unification: a MARKET's
     * tradeable goods live only in its stall chests, never the building's own
     * storage. Production buildings (BAKERY, INN) keep building-bounds scans.
     */
    private static int foodCount(ServerLevel level, Building source, Item item) {
        if (source.getType() == BuildingType.MARKET) {
            return tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.countItem(level, source, item);
        }
        return BuildingStorageAccess.countItem(level, source, item);
    }

    /** Market-aware take — stall chests for a MARKET, building bounds otherwise. */
    private static boolean foodTake(ServerLevel level, Building source, Item item, int count) {
        if (source.getType() == BuildingType.MARKET) {
            return tterrag1112.life_in_the_village.Village.Markets.Complex.MarketInventory.takeItem(level, source, item, count);
        }
        return BuildingStorageAccess.takeItem(level, source, item, count);
    }

    private static TownspersonMob findAssignedNpc(ServerLevel level, Building building) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                building.getShape().toAABB().inflate(16),
                mob -> building.getId().equals(mob.getAssignedBuildingId().orElse(null))
        ).stream().findFirst().orElse(null);
    }

    private static Optional<Building> getHomeBuilding(ServerLevel level, TownspersonMob entity) {
        // R6d — a monk's "home" for eating is its monastery shared store (it has
        // no household). All the food-source logic (homeHasFood, walk-home,
        // tryEatFromHome) then sources from the monastery building, redirecting
        // mealtime consumption to the shared store with no forked eating system.
        if (entity.getProfession() == Profession.MONK) {
            Optional<Building> monastery = entity.getAssignedBuildingId()
                    .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                    .filter(b -> b.getType() == BuildingType.MONASTERY
                            || b.getType() == BuildingType.ABBEY);
            if (monastery.isPresent()) return monastery;
        }
        return entity.getFamily().getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }

    private static Optional<BlockPos> getHomePosition(ServerLevel level, TownspersonMob entity) {
        return getHomeBuilding(level, entity).map(b -> b.getShape().getOrigin());
    }

    private void navigateToHome(ServerLevel level, TownspersonMob entity) {
        getHomePosition(level, entity).ifPresent(pos -> navigateTo(entity, pos));
    }

    private void navigateTo(TownspersonMob entity, BlockPos pos) {
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(pos, WALK_SPEED, CLOSE_ENOUGH));
    }

    private static boolean isFood(Item item) {
        return item.getDefaultInstance().has(DataComponents.FOOD);
    }
}
