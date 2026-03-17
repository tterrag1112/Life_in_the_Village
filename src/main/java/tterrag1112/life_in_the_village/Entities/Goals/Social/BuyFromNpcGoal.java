package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;

import java.util.*;
import java.util.function.Supplier;

public class BuyFromNpcGoal extends Goal {

    private enum Phase {
        IDLE, SEARCHING, WALKING_TO_SELLER, BUYING
    }

    private static final int INTERACT_RANGE_SQ = 9;
    private static final int WALK_TIMEOUT = 400;

    private final TownspersonMob entity;
    private final Supplier<Boolean> needsItem; // when to trigger
    private final Supplier<Item> itemToBuy;    // what to buy
    private final Supplier<Integer> quantity;  // how many
    private final int checkInterval;

    private Phase phase = Phase.IDLE;
    private int checkTimer = 0;
    private int walkTimer = 0;
    private VillageEconomy.TradeListResult target = null;

    public BuyFromNpcGoal(TownspersonMob entity,
                          Supplier<Boolean> needsItem,
                          Supplier<Item> itemToBuy,
                          Supplier<Integer> quantity,
                          int checkInterval) {
        this.entity        = entity;
        this.needsItem     = needsItem;
        this.itemToBuy     = itemToBuy;
        this.quantity      = quantity;
        this.checkInterval = checkInterval;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        checkTimer++;
        if (checkTimer < checkInterval) return false;
        checkTimer = 0;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (!needsItem.get()) return false;

        UUID villageId = getVillageId(level);
        if (villageId == null) return false;

        target = VillageEconomy.findCheapestSeller(
                level, villageId, itemToBuy.get(),
                entity.getX(), entity.getZ(),
                level.getGameTime()
        ).orElse(null);

        System.out.println(entity.getNpcName() + " searching for "
                + itemToBuy.get().getDescriptionId()
                + " — found seller: " + (target != null
                ? target.seller().getNpcName()
                + " at " + target.listing().getPricePerItem() + "b"
                : "none"));

        if (target == null) return false;

        return entity.canAffordTotal(
                CurrencyValue.of(target.listing().getPricePerItem()), level);
    }

    @Override
    public void start() {
        phase = Phase.WALKING_TO_SELLER;
        walkTimer = 0;
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
            case WALKING_TO_SELLER -> walkToSeller(level);
            case BUYING            -> buy(level);
            default -> {}
        }
    }

    private void walkToSeller(ServerLevel level) {
        if (target == null) { goIdle(); return; }
        walkTimer++;

        Building building = VillageSavedData.get(level)
                .getBuildingById(target.listing().getSellerBuildingId())
                .orElse(null);
        if (building == null) { goIdle(); return; }

        BlockPos dest = building.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                dest.getX(), dest.getY(), dest.getZ());

        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getNavigation().stop();
            phase = Phase.BUYING;
            walkTimer = 0;
        } else if (walkTimer > WALK_TIMEOUT) {
            goIdle();
        } else if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    dest.getX(), dest.getY(), dest.getZ(), 1.0);
        }
    }

    private void buy(ServerLevel level) {
        if (target == null) { goIdle(); return; }

        TownspersonMob seller = target.seller();
        if (seller == null || !seller.isAlive()) {
            goIdle();
            return;
        }
        entity.setCurrentActivity("Buying " + itemToBuy.get().getName() + "from " + seller.getNpcName());


        Item item = itemToBuy.get();
        int qty = Math.min(quantity.get(),
                target.listing().getQuantity());
        long totalCost = target.listing().getPricePerItem() * qty;
        CurrencyValue cost = CurrencyValue.of(totalCost);

        if (!entity.canAfford(cost)) { goIdle(); return; }

        // Take item from seller's building storage
        Building sellerBuilding = VillageSavedData.get(level)
                .getBuildingById(target.listing().getSellerBuildingId())
                .orElse(null);

        boolean taken = sellerBuilding != null
                && BuildingStorageAccess.takeItem(
                level, sellerBuilding, item, qty);

        if (!taken) {
            // Try seller's personal inventory
            taken = takeFromInventory(seller, item, qty);
        }

        if (!taken) { goIdle(); return; }

        // Pay seller
        if (entity.level() instanceof ServerLevel sl) {
            entity.payWithBuilding(seller, cost, sl);
        }
        // Add to buyer's inventory or building
        addToInventoryOrBuilding(level, item, qty);

        System.out.println(entity.getNpcName() + " bought " + qty
                + "x " + item.getDescriptionId()
                + " from " + seller.getNpcName()
                + " for " + cost);

        goIdle();
    }

    private boolean takeFromInventory(TownspersonMob npc,
                                      Item item, int qty) {
        var inv = npc.getPersonalInventory();
        int remaining = qty;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return remaining == 0;
    }

    private void addToInventoryOrBuilding(ServerLevel level,
                                          Item item, int qty) {
        // Try building storage first
        Building building = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);

        if (building != null) {
            boolean stored = BuildingStorageAccess.storeItem(
                    level, building, new ItemStack(item, qty));
            if (stored) return;
        }

        // Fall back to personal inventory
        entity.getPersonalInventory().addItem(new ItemStack(item, qty));
    }

    private void goIdle() {
        phase = Phase.IDLE;
        entity.clearCurrentActivity();
        target = null;
        walkTimer = 0;
        entity.getNavigation().stop();
    }

    private UUID getVillageId(ServerLevel level) {
        return entity.getAssignedVillageName()
                .flatMap(name ->
                        VillageSavedData.get(level).getVillageByName(name))
                .map(v -> v.getId())
                .orElse(null);
    }

    @Override
    public void stop() { goIdle(); }
}