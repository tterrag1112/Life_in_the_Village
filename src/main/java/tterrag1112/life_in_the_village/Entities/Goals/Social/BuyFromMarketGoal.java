// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Social/BuyFromMarketGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.DynamicPriceCalculator;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * NPC households buy food and essential goods from the market.
 *
 * <h3>When it runs</h3>
 * During social/idle time (not work, not sleep). The NPC checks if their
 * home has enough food and basic supplies. If not, they walk to the market
 * and purchase what they can afford.
 *
 * <h3>What they buy</h3>
 * <ul>
 *   <li>Food — bread, carrots, potatoes, cooked meats (anything with
 *       FOOD component). Target: 3 days' supply per household member.</li>
 *   <li>Work tools — profession-specific items if their personal stock
 *       is depleted (e.g., hoe for farmers, pickaxe for miners).</li>
 * </ul>
 *
 * <h3>Economic effect</h3>
 * This completes the circular flow: producers → market → consumers.
 * Coins flow from NPC households to merchants, who pay producers,
 * who spend on goods. The market becomes a genuine hub of activity
 * rather than just a player-facing shop.
 */
public class BuyFromMarketGoal extends Goal {

    private enum Phase { IDLE, WALKING, BUYING }

    // ── Configuration ─────────────────────────────────────────────────
    private static final int CHECK_INTERVAL = 3600; // ~3 in-game minutes
    private static final int INTERACT_RANGE_SQ = 9;
    /** Minimum food items per household member before triggering a buy. */
    private static final int FOOD_PER_MEMBER = 6;
    /** Max items to buy in a single trip. */
    private static final int MAX_BUY_PER_TRIP = 16;
    /** Max fraction of personal wealth to spend per trip. */
    private static final double MAX_SPEND_FRACTION = 0.4;

    // Food items NPCs will look for, in preference order
    private static final List<Item> FOOD_PRIORITY = List.of(
            Items.BREAD,
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN,
            Items.BAKED_POTATO,
            Items.CARROT,
            Items.POTATO,
            Items.BEETROOT
    );

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int checkTimer = 0;
    private Building market = null;
    private List<BuyOrder> shoppingList = new ArrayList<>();

    public BuyFromMarketGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (entity.isWorkTime()) return false;
        if (entity.shouldBeHome()) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;

        VillageSavedData data = VillageSavedData.get(level);

        // Find market
        market = findMarket(level, data);
        if (market == null) return false;

        // Build shopping list
        shoppingList = buildShoppingList(level, data);
        return !shoppingList.isEmpty();
    }

    @Override
    public void start() {
        phase = Phase.WALKING;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case WALKING -> {
                BlockPos pos = market.getShape().getOrigin();
                double distSq = entity.distanceToSqr(
                        pos.getX(), pos.getY(), pos.getZ());
                if (distSq > INTERACT_RANGE_SQ) {
                    entity.getNavigation().moveTo(
                            pos.getX(), pos.getY(), pos.getZ(), 1.0);
                } else {
                    entity.getNavigation().stop();
                    phase = Phase.BUYING;
                }
            }
            case BUYING -> {
                executePurchases(level);
                goIdle();
            }
        }
    }

    // ── Shopping list construction ─────────────────────────────────────

    private List<BuyOrder> buildShoppingList(ServerLevel level,
                                             VillageSavedData data) {
        List<BuyOrder> orders = new ArrayList<>();

        // Count household members
        int householdSize = Math.max(1, countHousehold(level, data));

        // Check home food supply
        Building home = entity.getHouseId()
                .flatMap(data::getBuildingById)
                .orElse(null);

        int currentFood = 0;
        if (home != null) {
            currentFood = countFoodInBuilding(level, home);
        }
        currentFood += countFoodInInventory();

        int foodNeeded = (householdSize * FOOD_PER_MEMBER) - currentFood;
        if (foodNeeded > 0) {
            // Add food orders — try each food type from the market
            int remaining = Math.min(foodNeeded, MAX_BUY_PER_TRIP);
            for (Item food : FOOD_PRIORITY) {
                if (remaining <= 0) break;
                int available = BuildingStorageAccess.countItem(
                        level, market, food);
                if (available > 0) {
                    int qty = Math.min(remaining, available);
                    orders.add(new BuyOrder(food, qty));
                    remaining -= qty;
                }
            }
        }

        // Check work tools
        Item neededTool = getToolForProfession(entity.getProfession());
        if (neededTool != null && !entity.getEconomy().hasItem(neededTool)) {
            int available = BuildingStorageAccess.countItem(
                    level, market, neededTool);
            if (available > 0) {
                orders.add(new BuyOrder(neededTool, 1));
            }
        }

        return orders;
    }

    // ── Purchase execution ─────────────────────────────────────────────

    private void executePurchases(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        UUID villageId = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name))
                .map(Village::getId).orElse(null);

        long maxSpend = (long)(entity.getWealth().toBronze() * MAX_SPEND_FRACTION);
        long totalSpent = 0;

        for (BuyOrder order : shoppingList) {
            if (totalSpent >= maxSpend) break;

            long pricePerItem = villageId != null
                    ? VillageEconomy.getDynamicPrice(level, villageId, order.item)
                    : VillageEconomy.getBasePrice(order.item);

            int affordable = (int)((maxSpend - totalSpent) / Math.max(1, pricePerItem));
            int qty = Math.min(order.quantity, affordable);
            if (qty <= 0) continue;

            // Verify stock is still there
            int available = BuildingStorageAccess.countItem(level, market, order.item);
            qty = Math.min(qty, available);
            if (qty <= 0) continue;

            long totalCost = pricePerItem * qty;
            CurrencyValue cost = CurrencyValue.of(totalCost);

            if (!entity.canAfford(cost)) continue;

            // Take from market
            boolean taken = BuildingStorageAccess.takeItem(
                    level, market, order.item, qty);
            if (!taken) continue;

            // Pay — find merchant to receive coins
            TownspersonMob merchant = findMerchant(level);
            if (merchant != null) {
                entity.pay(merchant, cost);
            } else {
                entity.spend(cost);
            }

            // Market tax
            if (villageId != null) {
                data.getTreasury(villageId).ifPresent(treasury -> {
                    treasury.collectMarketTax(totalCost);
                    data.putTreasury(treasury);
                });
            }

            // Add items — food goes to home storage, tools to personal inv
            if (isFood(order.item)) {
                Building home = entity.getHouseId()
                        .flatMap(data::getBuildingById).orElse(null);
                if (home != null) {
                    BuildingStorageAccess.storeItem(level, home,
                            new ItemStack(order.item, qty));
                } else {
                    entity.getPersonalInventory().addItem(
                            new ItemStack(order.item, qty));
                }
            } else {
                entity.getPersonalInventory().addItem(
                        new ItemStack(order.item, qty));
            }

            totalSpent += totalCost;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private int countHousehold(ServerLevel level, VillageSavedData data) {
        return entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding)
                .map(h -> h.getMemberNpcIds().size())
                .orElse(1);
    }

    private int countFoodInBuilding(ServerLevel level, Building building) {
        int count = 0;
        for (var container : BuildingStorageAccess.findInventories(level, building)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty() && isFood(stack.getItem())) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private int countFoodInInventory() {
        int count = 0;
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isFood(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isFood(Item item) {
        return item.components().has(DataComponents.FOOD);
    }

    private static Item getToolForProfession(Profession prof) {
        return switch (prof) {
            case FARMER, FARMHAND -> Items.IRON_HOE;
            case MINER            -> Items.IRON_PICKAXE;
            case CARPENTER        -> Items.IRON_AXE;
            case GUARD            -> Items.IRON_SWORD;
            default               -> null;
        };
    }

    private Building findMarket(ServerLevel level, VillageSavedData data) {
        return entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name))
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.MARKET)
                        .findFirst())
                .orElse(null);
    }

    private TownspersonMob findMerchant(ServerLevel level) {
        if (market == null) return null;
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                market.getShape().toAABB().inflate(16),
                mob -> mob.getProfession() == Profession.MERCHANT
        ).stream().findFirst().orElse(null);
    }

    private void goIdle() {
        phase = Phase.IDLE;
        entity.getNavigation().stop();
        market = null;
        shoppingList.clear();
    }

    private record BuyOrder(Item item, int quantity) {}
}