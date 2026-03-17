package tterrag1112.life_in_the_village.Entities.Goals.Profession.StockpileKeeper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;

import java.util.*;

public class StockpileKeeperGoal extends Goal {

    private static final int CHECK_INTERVAL = 2400;
    private int timer = 0;

    private final TownspersonMob entity;

    public StockpileKeeperGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() { return true; }
    @Override
    public boolean canContinueToUse() { return true; }
    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        timer++;
        if (timer < CHECK_INTERVAL) return;
        timer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return;

        VillageSavedData data = VillageSavedData.get(level);
        Building stockpile = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (stockpile == null) return;

        // Get village and compute targets
        data.getVillageByName(
                        entity.getAssignedVillageName().orElse(""))
                .ifPresent(village -> {
                    Map<Item, Integer> targets =
                            VillageEconomy.computeStockpileTargets(
                                    level, village, data);

                    targets.forEach((item, target) -> {
                        int current = BuildingStorageAccess.countItem(
                                level, stockpile, item);
                        if (current < target / 2) {
                            // Below half target — post a buy request
                            int needed = target - current;
                            UUID villageId = village.getId();

                            VillageEconomy.findCheapestSeller(
                                    level, villageId, item,
                                    entity.getX(), entity.getZ(),
                                    level.getGameTime()
                            ).ifPresent(seller -> {
                                int qty = Math.min(needed,
                                        seller.listing().getQuantity());
                                long cost = seller.listing()
                                        .getPricePerItem() * qty;

                                if (entity.canAffordTotal(
                                        CurrencyValue.of(cost),level)) {
                                    entity.payWithBuilding(seller.seller(),
                                            CurrencyValue.of(cost), level);
                                    BuildingStorageAccess.storeItem(
                                            level, stockpile,
                                            new net.minecraft.world.item.ItemStack(
                                                    item, qty));
                                    System.out.println(
                                            "Stockpile bought " + qty
                                                    + "x " + item.getDescriptionId()
                                                    + " for restocking");
                                }
                            });
                        }
                    });
                });
    }
}