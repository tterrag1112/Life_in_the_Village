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
        if (!entity.hasHome()) return false;
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

        entity.setCurrentActivity("No food available");
        phase = Phase.DONE;
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
            if (BuildingStorageAccess.countItem(level, source, item) <= 0) continue;
            if (price < bestPrice) { bestPrice = price; bestItem = item; }
        }
        if (bestItem == null) return false;
        CurrencyValue cost = CurrencyValue.of(bestPrice);
        if (!entity.canAfford(cost)) return false;
        if (!BuildingStorageAccess.takeItem(level, source, bestItem, 1)) return false;

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
            if (BuildingStorageAccess.countItem(level, building, entry.getKey()) > 0) return true;
        }
        return false;
    }

    private static TownspersonMob findAssignedNpc(ServerLevel level, Building building) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                building.getShape().toAABB().inflate(16),
                mob -> building.getId().equals(mob.getAssignedBuildingId().orElse(null))
        ).stream().findFirst().orElse(null);
    }

    private static Optional<Building> getHomeBuilding(ServerLevel level, TownspersonMob entity) {
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
