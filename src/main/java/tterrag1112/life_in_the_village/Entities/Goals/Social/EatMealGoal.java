package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.WorkSchedule;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.*;
import tterrag1112.life_in_the_village.Village.Needs.FoodValueHelper;

import java.util.*;

/**
 * NPCs eat a meal during their scheduled meal window.
 *
 * <h3>Decision tree (evaluated once in start())</h3>
 * <ol>
 *   <li>Personal inventory has food → eat in place immediately</li>
 *   <li>NPC is within {@link #HOME_CLOSE_SQ} of home AND home has food
 *       → walk home, eat from storage</li>
 *   <li>A food-selling building has stock AND NPC can afford it
 *       → DETOUR to buy, then eat in place</li>
 *   <li>Home has food (even if far away) → walk home</li>
 *   <li>Nothing available → activity "No food..." then done</li>
 * </ol>
 *
 * <h3>Food sources</h3>
 * Any building whose type appears in {@link #FOOD_SOURCE_TYPES} is a
 * potential food stop. Add new types here as new food buildings are built.
 */
public class EatMealGoal extends Goal {

    // Ticks to stand still during eating animation
    private static final int    EAT_DURATION        = 80;
    // Only walk home proactively if within this distance (16 blocks)
    private static final double HOME_CLOSE_SQ        = 256.0;
    // Close enough to interact with a building
    private static final double INTERACT_SQ          = 16.0;
    // Minimum personal wealth before the NPC will spend coins on food
    private static final long   MIN_WEALTH_BRONZE    = 20L;

    /**
     * Building types that sell food. Extend this set to support future
     * food buildings (inn kitchen, tavern, butcher, etc.).
     */
    private static final Set<BuildingType> FOOD_SOURCE_TYPES = Set.of(
            BuildingType.MARKET,
            BuildingType.BAKERY
    );

    private final TownspersonMob entity;

    private enum Phase {
        DETOUR,       // walking to a food-selling building to buy
        WALKING_HOME, // walking home to eat from house storage
        EATING,       // eating animation (always in place)
        DONE
    }

    private Phase    phase         = Phase.DONE;
    private int      eatTimer      = 0;
    private boolean  ateThisWindow = false;
    private Building detourTarget  = null;

    public EatMealGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!entity.hasHome()) return false;
        if (!(entity.level() instanceof ServerLevel)) return false;
        if (!WorkSchedule.isMealTime(entity)) {
            ateThisWindow = false;
            return false;
        }
        return !ateThisWindow;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.DONE && WorkSchedule.isMealTime(entity);
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void start() {
        phase    = Phase.DONE;
        eatTimer = 0;
        detourTarget = null;

        if (!(entity.level() instanceof ServerLevel level)) return;

        // ── 1. Already have food on person → eat right here ───────────────────
        if (hasPersonalFood()) {
            entity.setCurrentActivity("Eating");
            phase = Phase.EATING;
            return;
        }

        Optional<BlockPos> homePos = getHomePosition(level);
        boolean homeHasFood = homePos.isPresent()
                && hasFoodInStorage(level,
                getHomeBuilding(level).orElse(null));
        boolean closeToHome = homePos.isPresent()
                && entity.distanceToSqr(
                homePos.get().getX(),
                homePos.get().getY(),
                homePos.get().getZ()) <= HOME_CLOSE_SQ;

        // ── 2. Close to home and home has food → walk home ───────────────────
        if (closeToHome && homeHasFood) {
            phase = Phase.WALKING_HOME;
            entity.setCurrentActivity("Heading home to eat");
            navigateToHome(level);
            return;
        }

        // ── 3. Food source available and NPC can pay → detour to buy ─────────
        Building source = findAnyFoodSource(level);
        if (source != null) {
            detourTarget = source;
            phase = Phase.DETOUR;
            entity.setCurrentActivity("Buying lunch");
            navigateTo(source.getShape().getOrigin());
            return;
        }

        // ── 4. Home has food but NPC is far away → walk home anyway ──────────
        if (homeHasFood) {
            phase = Phase.WALKING_HOME;
            entity.setCurrentActivity("Heading home to eat");
            navigateToHome(level);
            return;
        }

        // ── 5. Nothing available ─────────────────────────────────────────────
        entity.setCurrentActivity("No food available");
        phase = Phase.DONE;
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                ItemStack.EMPTY);
        if (phase == Phase.DONE || phase == Phase.EATING) {
            ateThisWindow = true;
        }
        phase        = Phase.DONE;
        detourTarget = null;
    }

    // =========================================================================
    // Phase: DETOUR
    // =========================================================================

    private void tickDetour(ServerLevel level) {
        if (detourTarget == null) {
            fallbackToHome(level);
            return;
        }

        BlockPos target = detourTarget.getShape().getOrigin();
        if (entity.distanceToSqr(target.getX(), entity.getY(), target.getZ())
                > INTERACT_SQ) {
            if (!entity.getNavigation().isInProgress()) {
                navigateTo(target);
            }
            return;
        }

        // Arrived — attempt purchase
        entity.getNavigation().stop();
        boolean bought = tryBuyFood(level, detourTarget);
        detourTarget = null;

        if (bought) {
            // Eat in place with the newly purchased food
            entity.setCurrentActivity("Eating");
            phase = Phase.EATING;
        } else {
            // Buying failed (out of stock, broke) — fall back to home
            fallbackToHome(level);
        }
    }

    // =========================================================================
    // Phase: WALKING_HOME
    // =========================================================================

    private void tickWalkingHome(ServerLevel level) {
        Optional<BlockPos> homePos = getHomePosition(level);
        if (homePos.isEmpty()) {
            entity.setCurrentActivity("No food available");
            phase = Phase.DONE;
            return;
        }

        double distSq = entity.distanceToSqr(
                homePos.get().getX(),
                homePos.get().getY(),
                homePos.get().getZ());

        if (distSq <= HOME_CLOSE_SQ) {
            entity.getNavigation().stop();
            tryEatFromHome(level);
        } else if (!entity.getNavigation().isInProgress()) {
            navigateToHome(level);
        }
    }

    // =========================================================================
    // Phase: EATING
    // =========================================================================

    private void tickEating() {
        eatTimer++;

        entity.getLookControl().setLookAt(
                entity.getX(), entity.getY() - 1, entity.getZ());

        if (eatTimer == 1) {
            entity.playSound(SoundEvents.GENERIC_EAT.value(), 0.5f, 1.0f);
            entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        if (eatTimer == EAT_DURATION / 2) {
            entity.playSound(SoundEvents.GENERIC_EAT.value(), 0.3f, 1.1f);
        }
        if (eatTimer >= EAT_DURATION) {
            entity.setCurrentActivity("");
            entity.setItemInHand(
                    net.minecraft.world.InteractionHand.MAIN_HAND,
                    ItemStack.EMPTY);
            phase = Phase.DONE;
        }
    }

    // =========================================================================
    // Tick dispatcher
    // =========================================================================

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        switch (phase) {
            case DETOUR       -> tickDetour(level);
            case WALKING_HOME -> tickWalkingHome(level);
            case EATING       -> tickEating();
            case DONE         -> {}
        }
    }

    // =========================================================================
    // Food buying
    // =========================================================================

    /**
     * Finds the closest food-selling building that has stock and the NPC
     * can afford. Does NOT require the source to be closer than home —
     * it is used as a fallback when no other food is available.
     */
    private Building findAnyFoodSource(ServerLevel level) {
        if (CoinHelper.getWealth(entity.getPersonalInventory()).toBronze()
                < MIN_WEALTH_BRONZE) return null;

        String villageName = entity.getAssignedVillageName().orElse(null);
        if (villageName == null) return null;

        VillageSavedData data = VillageSavedData.get(level);
        var village = data.getVillageByName(villageName).orElse(null);
        if (village == null) return null;

        Building closest     = null;
        double   closestDist = Double.MAX_VALUE;

        for (UUID id : village.getBuildingIds()) {
            Building b = data.getBuildingById(id).orElse(null);
            if (b == null) continue;
            if (!FOOD_SOURCE_TYPES.contains(b.getType())) continue;
            if (!hasFoodAffordable(level, b)) continue;

            BlockPos origin = b.getShape().getOrigin();
            double dist = entity.distanceToSqr(
                    origin.getX(), origin.getY(), origin.getZ());

            if (dist < closestDist) {
                closestDist = dist;
                closest     = b;
            }
        }

        return closest;
    }

    /**
     * Attempts to buy the cheapest affordable food item from {@code source}.
     * Pays the assigned NPC if present; otherwise spends coins directly.
     *
     * @return true if a food item was successfully purchased and added to
     *         the NPC's personal inventory
     */
    private boolean tryBuyFood(ServerLevel level, Building source) {
        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        if (priceData == null) return false;

        long wealth = CoinHelper.getWealth(entity.getPersonalInventory()).toBronze();

        // Find the cheapest available food the NPC can afford
        Item  bestItem  = null;
        long  bestPrice = Long.MAX_VALUE;

        for (Map.Entry<Item, MarketPriceData.ItemPrice> entry
                : priceData.getAllPrices().entrySet()) {
            Item item = entry.getKey();
            long price = entry.getValue().sellPrice();
            if (!isFood(item)) continue;
            if (price > wealth) continue;
            if (BuildingStorageAccess.countItem(level, source, item) <= 0) continue;
            if (price < bestPrice) {
                bestPrice = price;
                bestItem  = item;
            }
        }

        if (bestItem == null) return false;

        CurrencyValue cost = CurrencyValue.of(bestPrice);
        if (!entity.canAfford(cost)) return false;
        if (!BuildingStorageAccess.takeItem(level, source, bestItem, 1)) return false;

        // Route payment to the vendor NPC if present
        TownspersonMob vendor = findAssignedNpc(level, source);
        if (vendor != null) {
            // NpcEconomy.npcPay: wallet → wallet + visual on both
            NpcEconomy.npcPay(entity, vendor, cost, level);
        } else {
            // No vendor NPC — spend from wallet with minimal visual
            entity.getWallet().spend(cost);
            NpcTransactionVisual.showPayment(entity, level);
        }

        entity.getPersonalInventory().addItem(new ItemStack(bestItem, 1));
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(bestItem, 1));
        return true;
    }

    // =========================================================================
    // Eating from home storage
    // =========================================================================

    private void tryEatFromHome(ServerLevel level) {
        // Check personal inventory first (may have grabbed something en route)
        if (hasPersonalFood()) {
            consumePersonalFood();
            phase    = Phase.EATING;
            eatTimer = 0;
            entity.setCurrentActivity("Eating");
            return;
        }

        // Check house storage
        Optional<Building> house = getHomeBuilding(level);
        if (house.isPresent()) {
            for (var container
                    : BuildingStorageAccess.findInventories(level, house.get())) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.isEmpty() || !FoodValueHelper.isFood(stack.getItem()))
                        continue;
                    stack.shrink(1);
                    if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                    phase    = Phase.EATING;
                    eatTimer = 0;
                    entity.setCurrentActivity("Eating");
                    return;
                }
            }
        }

        entity.setCurrentActivity("No food available");
        phase = Phase.DONE;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Called when buying fails and the NPC needs to fall back to eating at
     * home. If home has food, walks there; otherwise gives up.
     */
    private void fallbackToHome(ServerLevel level) {
        if (!(entity.level() instanceof ServerLevel lv)) { phase = Phase.DONE; return; }
        Optional<Building> house = getHomeBuilding(lv);
        if (house.isPresent() && hasFoodInStorage(lv, house.get())) {
            phase = Phase.WALKING_HOME;
            entity.setCurrentActivity("Heading home to eat");
            navigateToHome(lv);
        } else {
            entity.setCurrentActivity("No food available");
            phase = Phase.DONE;
        }
    }

    private boolean hasPersonalFood() {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && isFood(s.getItem())) return true;
        }
        return false;
    }

    private void consumePersonalFood() {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !isFood(s.getItem())) continue;
            s.shrink(1);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            return;
        }
    }

    private boolean hasFoodInStorage(ServerLevel level, Building building) {
        if (building == null) return false;
        for (var container
                : BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (!s.isEmpty() && FoodValueHelper.isFood(s.getItem())) return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the building has at least one food item the NPC can
     * afford. Used to decide whether to bother walking there.
     */
    private boolean hasFoodAffordable(ServerLevel level, Building building) {
        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        if (priceData == null) return hasFoodInStorage(level, building);

        long wealth = CoinHelper.getWealth(entity.getPersonalInventory()).toBronze();

        for (var entry : priceData.getAllPrices().entrySet()) {
            if (!isFood(entry.getKey())) continue;
            if (entry.getValue().sellPrice() > wealth) continue;
            if (BuildingStorageAccess.countItem(level, building, entry.getKey()) > 0)
                return true;
        }
        return false;
    }

    private TownspersonMob findAssignedNpc(ServerLevel level, Building building) {
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                building.getShape().toAABB().inflate(16),
                mob -> building.getId().equals(
                        mob.getAssignedBuildingId().orElse(null))
        ).stream().findFirst().orElse(null);
    }

    private Optional<Building> getHomeBuilding(ServerLevel level) {
        return entity.getHouseId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }

    private Optional<BlockPos> getHomePosition(ServerLevel level) {
        return getHomeBuilding(level)
                .map(b -> b.getShape().getOrigin());
    }

    private void navigateToHome(ServerLevel level) {
        getHomePosition(level).ifPresent(pos ->
                entity.getNavigation().moveTo(
                        pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.9));
    }

    private void navigateTo(BlockPos pos) {
        entity.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
    }

    private static boolean isFood(Item item) {
        return item.getDefaultInstance().has(DataComponents.FOOD);
    }
}