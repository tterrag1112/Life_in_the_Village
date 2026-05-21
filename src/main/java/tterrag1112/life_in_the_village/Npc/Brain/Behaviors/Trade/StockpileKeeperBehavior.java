package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.CraftingOrderManager;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;


public class StockpileKeeperBehavior extends Behavior<TownspersonMob> {

    // How often to run the restock check — every 2 in-game minutes
    private static final int CHECK_INTERVAL = 2400;
    // Only post a crafting order if stock is below this fraction of target
    private static final double ORDER_THRESHOLD = 0.5;

    private int timer = 0;
    private TownspersonMob entity;

    public StockpileKeeperBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }

    

    @Override protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity)            { this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return true; }
    @Override protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime)  { this.entity = entity;
        return true; }
        @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        timer++;
        if (timer < CHECK_INTERVAL) return;
        timer = 0;

        if (!entity.isWorkTime()) return;

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
        UUID buildingId = entity.getAssignedBuildingId().orElse(null);
        if (buildingId == null) return false;

        VillageSavedData data = VillageSavedData.get(level);
        BuildingEconomy bEconomy = data.getOrCreateBuildingEconomy(buildingId);

        return VillageEconomy.findCheapestSeller(
                level, village.getId(), item,
                entity.getX(), entity.getZ(), currentTick
        ).map(seller -> {
            int  qty  = Math.min(needed, seller.listing().getQuantity());
            long cost = seller.listing().getPricePerItem() * qty;

            if (!bEconomy.canAfford(cost)) return false;

            Building sellerBuilding = data
                    .getBuildingById(seller.listing().getSellerBuildingId())
                    .orElse(null);
            if (sellerBuilding == null) return false;

            if (!BuildingStorageAccess.takeItem(
                    level, sellerBuilding, item, qty)) return false;

            // businessPay: building treasury pays seller wallet + fires visual
            NpcEconomy.businessPay(buildingId, seller.seller(),
                    CurrencyValue.of(cost), level, data);

            BuildingStorageAccess.storeItem(
                    level, stockpile, new ItemStack(item, qty));
            return true;

        }).orElse(false);
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}