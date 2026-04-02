package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.MerchantGoal;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.DynamicPriceCalculator;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class SellToMarketGoal extends Goal {

    private enum Phase {
        IDLE, WALKING_TO_MARKET, SELLING
    }

    // How many items must be in storage before selling
    private static final int SELL_THRESHOLD = 32;
    private static final int CHECK_INTERVAL = 2400;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final int SELL_DURATION = 200;

    private final TownspersonMob entity;
    private final Map<Item, Integer> sellableItems;

    private Phase phase = Phase.IDLE;
    private int checkTimer = 0;
    private int sellTimer = 0;
    private Building market = null;
    private TownspersonMob merchantNpc = null;
    private Map<Item, Integer> itemsToSell = new HashMap<>();

    public SellToMarketGoal(TownspersonMob entity, Map<Item, Integer> sellableItems) {
        this.entity = entity;
        this.sellableItems = sellableItems;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Only during social/idle time — don't interrupt work
        if (entity.isWorkTime()) return false;
        if (entity.shouldBeHome()) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;

        VillageSavedData data = VillageSavedData.get(level);

        // Find market in village
        market = findMarket(level, data);
        if (market == null) return false;

        // Find merchant NPC at market
        merchantNpc = findMerchant(level);
        if (merchantNpc == null) return false;

        // Check building storage threshold
        Building assignedBuilding = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (assignedBuilding == null) return false;

        itemsToSell.clear();
        for (Map.Entry<Item, Integer> entry : sellableItems.entrySet()) {
            Item item = entry.getKey();
            Integer count = entry.getValue();
            if (hasSurplus(level, assignedBuilding, item)) {
                itemsToSell.put(item, count - SELL_THRESHOLD);
            }
        }

        return !itemsToSell.isEmpty();
    }

    @Override
    public void start() {
        phase = Phase.WALKING_TO_MARKET;
        sellTimer = 0;
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
            case WALKING_TO_MARKET -> walkToMarket();
            case SELLING           -> sell(level);
            default -> {}
        }
    }

    private void walkToMarket() {
        if (market == null) { goIdle(); return; }

        BlockPos target = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            phase = Phase.SELLING;
        }
    }

    private void sell(ServerLevel level) {
        sellTimer++;

        if (merchantNpc == null || !merchantNpc.isAlive()) {
            goIdle();
            return;
        }

        // Face the merchant
        entity.getLookControl().setLookAt(
                merchantNpc.getX(), merchantNpc.getY() + merchantNpc.getEyeHeight(),
                merchantNpc.getZ());

        if (sellTimer < 20) return; // brief pause before transaction

        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));

        Building assignedBuilding = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (assignedBuilding == null) { goIdle(); return; }

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        boolean soldAnything = false;

        for (Map.Entry<Item, Integer> entry : itemsToSell.entrySet()) {
            Item item = entry.getKey();
            int toSell = entry.getValue();
            if (toSell <= 0) continue;

            // Get price merchant will pay
            MarketPriceData.ItemPrice basePrice = priceData.getPrice(item)
                    .orElse(null);
            if (basePrice == null) continue;

            long pricePerItem = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            // Check merchant can afford it
            CurrencyValue totalPayment = CurrencyValue.of(pricePerItem * toSell);
            if (!merchantNpc.canAffordWithBuilding(totalPayment, level)) {
                // Sell as much as merchant can afford
                long canAfford = merchantNpc.getWealth().toBronze() / pricePerItem;
                toSell = (int) Math.min(toSell, canAfford);
                if (toSell <= 0) continue;
                totalPayment = CurrencyValue.of(pricePerItem * toSell);
            }

            // Take items from building storage
            boolean taken = BuildingStorageAccess.takeItem(
                    level, assignedBuilding, item, toSell);
            if (!taken) continue;

            // Deposit into market storage
            BuildingStorageAccess.storeItem(
                    level, market, new ItemStack(item, toSell));

            // Merchant pays seller
            merchantNpc.pay(entity, totalPayment);

            System.out.println(entity.getNpcName() + " sold " + toSell
                    + "x " + item.getDescriptionId()
                    + " to merchant for " + totalPayment);

            soldAnything = true;
        }

        if (soldAnything) {
            // Regenerate merchant offers with new stock
            MerchantGoal merchantGoal = merchantNpc.getGoal(MerchantGoal.class);
            if (merchantGoal != null) {
                merchantGoal.regenerateMarketOffers(level);
            }
        }

        goIdle();
    }

    @Override
    public void stop() { goIdle(); }

    private void goIdle() {
        phase = Phase.IDLE;
        entity.getNavigation().stop();
        market = null;
        merchantNpc = null;
        itemsToSell.clear();
        sellTimer = 0;
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
                        && mob.getAssignedBuildingId()
                        .map(id -> id.equals(market.getId()))
                        .orElse(false)
        ).stream().findFirst().orElse(null);
    }

    private boolean hasSurplus(ServerLevel level, Building building, Item item) {
        int count = BuildingStorageAccess.countItem(level, building, item);
        int reserved = getReservedAmount(level, item);
        return count > SELL_THRESHOLD + reserved;
    }

    private int getReservedAmount(ServerLevel level, Item item) {
        UUID villageId = entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level)
                        .getVillageByName(name))
                .map(v -> v.getId())
                .orElse(null);
        if (villageId == null) return 0;

        return VillageEconomy.getListingsForItem(
                level, villageId, item, level.getGameTime()
        ).isEmpty() ? 0 : 16;
    }
}
