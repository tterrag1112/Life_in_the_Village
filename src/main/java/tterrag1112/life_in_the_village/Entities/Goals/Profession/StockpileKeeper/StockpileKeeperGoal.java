// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/StockpileKeeper/StockpileKeeperGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.StockpileKeeper;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;


public class StockpileKeeperGoal extends Goal {

    // How often to run the restock check — every 2 in-game minutes
    private static final int CHECK_INTERVAL = 2400;
    // Only post a crafting order if stock is below this fraction of target
    private static final double ORDER_THRESHOLD = 0.5;

    private int timer = 0;
    private final TownspersonMob entity;

    public StockpileKeeperGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override public boolean canUse()            { return true; }
    @Override public boolean canContinueToUse()  { return true; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        timer++;
        if (timer < CHECK_INTERVAL) return;
        timer = 0;

        if (!entity.isWorkTime()) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        VillageSavedData data = VillageSavedData.get(level);

        Building stockpile = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (stockpile == null) return;

        Village village = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) return;

        Map<Item, Integer> targets =
                VillageEconomy.computeStockpileTargets(level, village, data);
        long currentTick = level.getGameTime();

        targets.forEach((item, targetQty) -> {
            int current = BuildingStorageAccess.countItem(level, stockpile, item);
            if (current >= (int)(targetQty * ORDER_THRESHOLD)) return;

            int needed = targetQty - current;

            // ── Step 1: try to buy from an existing market NPC ────────────
            boolean bought = tryBuyFromMarket(level, village, stockpile,
                    item, needed, currentTick);

            if (bought) return;

            // ── Step 2: no seller available — post a crafting order ───────
            String itemId = BuiltInRegistries.ITEM.getKey(item).toString();

            CraftingOrderManager.postOrderIfNeeded(
                    entity.getUUID(),
                    village.getId(),
                    itemId,
                    // Cap the order at a sensible per-order quantity so the
                    // board isn't flooded with huge single requests
                    Math.min(needed, 64),
                    currentTick,
                    data);
        });
    }

    // =========================================================================
    // Buy from market
    // =========================================================================

    /**
     * Tries to purchase the item from the cheapest available market NPC.
     *
     * @return true if a purchase was made, false if no seller was found or
     *         the keeper could not afford it
     */
    private boolean tryBuyFromMarket(ServerLevel level,
                                     Village village,
                                     Building stockpile,
                                     Item item,
                                     int needed,
                                     long currentTick) {
        return VillageEconomy.findCheapestSeller(
                level, village.getId(), item,
                entity.getX(), entity.getZ(), currentTick
        ).map(seller -> {
            int qty   = Math.min(needed, seller.listing().getQuantity());
            long cost = seller.listing().getPricePerItem() * qty;

            if (!entity.canAffordTotal(CurrencyValue.of(cost), level)) {
                return false;
            }

            entity.payWithBuilding(seller.seller(), CurrencyValue.of(cost), level);
            BuildingStorageAccess.storeItem(level, stockpile, new ItemStack(item, qty));

            System.out.println("StockpileKeeperGoal: bought " + qty
                    + "x " + item.getDescriptionId() + " for restocking");
            return true;
        }).orElse(false);
    }
}