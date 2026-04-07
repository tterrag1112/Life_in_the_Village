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
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * NPC households buy food and essential goods from the village market.
 * Runs during social/idle time. Completes the circular flow:
 * producers → market → consumers → coins back to producers.
 */
public class BuyFromMarketGoal extends Goal {

    private enum Phase { IDLE, WALKING, BUYING }

    private static final int CHECK_INTERVAL = 3600;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final int FOOD_PER_MEMBER = 6;
    private static final int MAX_BUY_PER_TRIP = 16;
    private static final double MAX_SPEND_FRACTION = 0.4;

    private static final List<Item> FOOD_PRIORITY = List.of(
            Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN, Items.BAKED_POTATO,
            Items.CARROT, Items.POTATO, Items.BEETROOT);

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int checkTimer = 0;
    private Building market = null;
    private final List<BuyOrder> shoppingList = new ArrayList<>();

    public BuyFromMarketGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (entity.isWorkTime() || entity.shouldBeHome()) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;
        VillageSavedData data = VillageSavedData.get(level);

        market = findMarket(level, data);
        if (market == null) return false;

        shoppingList.clear();
        buildShoppingList(level, data);
        return !shoppingList.isEmpty();
    }

    @Override public void start() { phase = Phase.WALKING; }
    @Override public boolean canContinueToUse() { return phase != Phase.IDLE; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        switch (phase) {
            case WALKING -> {
                BlockPos pos = market.getShape().getOrigin();
                if (entity.distanceToSqr(pos.getX(), pos.getY(), pos.getZ())
                        > INTERACT_RANGE_SQ) {
                    entity.getNavigation().moveTo(
                            pos.getX(), pos.getY(), pos.getZ(), 1.0);
                } else {
                    entity.getNavigation().stop();
                    phase = Phase.BUYING;
                }
            }
            case BUYING -> {
                entity.setCurrentActivity("Shopping at the market");
                executePurchases(level);
                goIdle();
            }
        }
    }

    @Override public void stop() { goIdle(); }

    // ── Shopping list ─────────────────────────────────────────────────

    private void buildShoppingList(ServerLevel level, VillageSavedData data) {
        int householdSize = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding)
                .map(h -> h.getMemberNpcIds().size())
                .orElse(1);

        // Count food at home + in personal inventory
        int currentFood = 0;
        Building home = entity.getHouseId()
                .flatMap(data::getBuildingById).orElse(null);
        if (home != null) {
            for (var container : BuildingStorageAccess.findInventories(level, home)) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty() && isFood(stack.getItem()))
                        currentFood += stack.getCount();
                }
            }
        }
        for (int i = 0; i < entity.getPersonalInventory().getContainerSize(); i++) {
            ItemStack stack = entity.getPersonalInventory().getItem(i);
            if (!stack.isEmpty() && isFood(stack.getItem()))
                currentFood += stack.getCount();
        }

        int foodNeeded = (householdSize * FOOD_PER_MEMBER) - currentFood;
        if (foodNeeded > 0) {
            int remaining = Math.min(foodNeeded, MAX_BUY_PER_TRIP);
            for (Item food : FOOD_PRIORITY) {
                if (remaining <= 0) break;
                int available = BuildingStorageAccess.countItem(level, market, food);
                if (available > 0) {
                    int qty = Math.min(remaining, available);
                    shoppingList.add(new BuyOrder(food, qty));
                    remaining -= qty;
                }
            }
        }

        // Check work tools
        Item tool = toolForProfession(entity.getProfession());
        if (tool != null && !entity.getEconomy().hasItem(tool)) {
            if (BuildingStorageAccess.countItem(level, market, tool) > 0)
                shoppingList.add(new BuyOrder(tool, 1));
        }
    }

    // ── Purchase execution ────────────────────────────────────────────

    private void executePurchases(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        UUID villageId = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name))
                .map(Village::getId).orElse(null);

        long maxSpend = (long)(entity.getWallet().toBronze() * MAX_SPEND_FRACTION);
        long totalSpent = 0;

        for (BuyOrder order : shoppingList) {
            if (totalSpent >= maxSpend) break;

            long price = villageId != null
                    ? VillageEconomy.getDynamicPrice(level, villageId, order.item)
                    : VillageEconomy.getBasePrice(order.item);

            int affordable = (int)((maxSpend - totalSpent) / Math.max(1, price));
            int qty = Math.min(order.quantity, affordable);
            qty = Math.min(qty,
                    BuildingStorageAccess.countItem(level, market, order.item));
            if (qty <= 0) continue;

            long totalCost = price * qty;
            CurrencyValue cost = CurrencyValue.of(totalCost);
            if (!entity.getWallet().canAfford(cost)) continue;

            if (!BuildingStorageAccess.takeItem(level, market, order.item, qty))
                continue;

            // Find stall source and merchant for routing
            tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall stall =
                    findStallWithItem(data, level, order.item);
            TownspersonMob merchant = findMerchant(level);

            // NpcEconomy.marketPurchase handles routing + visual
            NpcEconomy.marketPurchase(entity, merchant, cost, level, data, stall);

            // 10% market tax to village treasury
            if (villageId != null) {
                long tax = totalCost / 10;
                if (tax > 0) {
                    data.getVillageById(villageId).ifPresent(v -> {
                        v.depositToTreasury(tax);
                        data.setDirty();
                    });
                }
            }

            // Food → house storage; tools → personal inventory
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

    // Add this helper in BuyFromMarketGoal:
    private tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall
    findStallWithItem(VillageSavedData data,
                      ServerLevel level,
                      net.minecraft.world.item.Item item) {
        if (market == null) return null;
        return data.getStallsForMarket(market.getId()).stream()
                .filter(s -> s.isActive()
                        && !s.getChestPos().equals(net.minecraft.core.BlockPos.ZERO))
                .filter(s -> {
                    net.minecraft.world.level.block.entity.BlockEntity be =
                            level.getBlockEntity(s.getChestPos());
                    if (!(be instanceof net.minecraft.world.Container chest))
                        return false;
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        if (chest.getItem(i).is(item)) return true;
                    }
                    return false;
                })
                .findFirst().orElse(null);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /**
     * Checks if the purchased item was sourced from a stall chest and pays
     * the stall owner directly.
     *
     * @return true if payment was routed to a stall owner, false if the item
     *         came from the main market chest (caller pays the merchant instead)
     */
    private boolean payStallOwnerIfApplicable(ServerLevel level,
                                              Building market,
                                              Item item,
                                              CurrencyValue cost,
                                              VillageSavedData data) {
        MarketStall stall = data.getStallsForMarket(market.getId()).stream()
                .filter(s -> s.isActive()
                        && !s.getChestPos().equals(BlockPos.ZERO))
                .filter(s -> {
                    net.minecraft.world.level.block.entity.BlockEntity be =
                            level.getBlockEntity(s.getChestPos());
                    if (!(be instanceof net.minecraft.world.Container chest))
                        return false;
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        if (chest.getItem(i).is(item)) return true;
                    }
                    return false;
                })
                .findFirst().orElse(null);

        if (stall == null) return false;

        if (stall.getOwnerType() == MarketStall.OwnerType.NPC) {
            level.getEntitiesOfClass(
                            TownspersonMob.class,
                            entity.getBoundingBox().inflate(128),
                            mob -> mob.getUUID().equals(stall.getOwnerUUID()))
                    .stream().findFirst()
                    .ifPresent(owner -> owner.receive(cost));
        }
        // Player-owned stalls: revenue accumulates passively in the stall chest
        // (handled when the player next interacts with the merchant)
        return true;
    }

    private boolean isFood(Item item) {
        return item.components().has(DataComponents.FOOD);
    }

    private static Item toolForProfession(Profession prof) {
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
                .flatMap(v -> v.getBuildingIds().stream()
                        .map(data::getBuildingById)
                        .filter(Optional::isPresent).map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.MARKET)
                        .findFirst())
                .orElse(null);
    }

    private TownspersonMob findMerchant(ServerLevel level) {
        if (market == null) return null;
        return level.getEntitiesOfClass(TownspersonMob.class,
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