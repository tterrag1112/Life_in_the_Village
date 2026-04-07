package tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.DynamicPriceCalculator;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceData;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceRegistry;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

public class MerchantGoal extends Goal {

    private enum Phase {
        IDLE, STOCKING, OPEN_FOR_TRADE
    }

    private static final int IDLE_COOLDOWN = 1200;
    private static final int TRADE_DURATION = 6000;
    private static final int INTERACT_RANGE_SQ = 9;

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int tradeTimer = 0;

    private Building market = null;
    private List<Building> productionBuildings = new ArrayList<>();
    private int currentBuildingIndex = 0;

    public MerchantGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        return phase == Phase.IDLE
                && entity.getRandom().nextInt(40) == 0;
    }

    @Override
    public void start() {
        // Go directly to stocking/opening
        market = entity.getAssignedBuildingId()
                .flatMap(VillageSavedData.get(
                        (ServerLevel) entity.level())::getBuildingById)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .orElse(null);

        if (market == null) { goIdle(); return; }

        phase = Phase.STOCKING;
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        switch (phase) {
            case STOCKING          -> stockMarket(level);
            case OPEN_FOR_TRADE    -> openForTrade(level);
            default -> {}
        }
    }

    private void collect(ServerLevel level) {
        if (currentBuildingIndex >= productionBuildings.size()) {
            phase = Phase.STOCKING;
            return;
        }

        Building target = productionBuildings.get(currentBuildingIndex);
        BlockPos targetPos = target.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                targetPos.getX(), targetPos.getY(), targetPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    targetPos.getX(), targetPos.getY(),
                    targetPos.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));
        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();

        // Build a priority list — items with low market stock come first
        List<Map.Entry<Item, MarketPriceData.ItemPrice>> prioritized =
                new ArrayList<>(priceData.getAllPrices().entrySet());
        prioritized.sort((a, b) -> {
            int stockA = BuildingStorageAccess.countItem(level, market, a.getKey());
            int stockB = BuildingStorageAccess.countItem(level, market, b.getKey());
            return Integer.compare(stockA, stockB); // lowest stock first
        });

        int totalBought = 0;
        for (var entry : prioritized) {
            if (totalBought >= 48) break; // cap total per collection visit

            Item item = entry.getKey();
            int available = BuildingStorageAccess.countItem(level, target, item);
            if (available <= 0) continue;

            // Only buy if market stock is below threshold
            int marketStock = BuildingStorageAccess.countItem(level, market, item);
            if (marketStock >= 32) continue; // already well-stocked

            int toTake = Math.min(available, 16);

            long pricePerItem = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, entry.getValue().buyPrice())
            ).orElse(entry.getValue().buyPrice());

            long totalCost = pricePerItem * toTake;
            CurrencyValue cost = CurrencyValue.of(totalCost);

            if (!entity.canAfford(cost)) continue;

            boolean taken = BuildingStorageAccess.takeItem(
                    level, target, item, toTake);
            if (!taken) continue;

            TownspersonMob assignedNpc = findAssignedNpc(level, target);
            if (assignedNpc != null) {
                entity.pay(assignedNpc, cost);
            } else {
                entity.spend(cost);
            }

            entity.getPersonalInventory().addItem(new ItemStack(item, toTake));
            totalBought += toTake;
        }

        currentBuildingIndex++;
    }

    private TownspersonMob findAssignedNpc(ServerLevel level, Building building) {
        return level.getEntitiesOfClass(
                TownspersonMob.class,
                building.getShape().toAABB().inflate(16),
                mob -> building.getId().equals(mob.getAssignedBuildingId().orElse(null))
        ).stream().findFirst().orElse(null);
    }

    private void stockMarket(ServerLevel level) {
        if (market == null) { goIdle(); return; } // ← add this guard

        BlockPos marketPos = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                marketPos.getX(), marketPos.getY(), marketPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    marketPos.getX(), marketPos.getY(),
                    marketPos.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Deposit personal inventory into market
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            boolean stored = BuildingStorageAccess.storeItem(
                    level, market, stack.copy());
            if (stored) inv.setItem(i, ItemStack.EMPTY);
        }

        // Regenerate trade offers
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                ItemStack.EMPTY
        );

        regenerateMarketOffers(level);
        phase = Phase.OPEN_FOR_TRADE;
        tradeTimer = 0;
    }

    private void openForTrade(ServerLevel level) {
        tradeTimer++;
        if (tradeTimer >= TRADE_DURATION) {
            goIdle();
            return;
        }
        // Hold emerald to signal open for trade
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(net.minecraft.world.item.Items.EMERALD)
        );
    }

    public void regenerateMarketOffers(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));

        if (village.isEmpty()) return;

        // Resolve market locally — don't rely on the instance field which
        // is null when the merchant is idle
        Building activeMarket = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .orElse(null);

        if (activeMarket == null) return;

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        if (priceData == null) return;

        priceData.getAllPrices().forEach((item, basePrice) -> {
            if (!BuildingStorageAccess.hasItem(level, activeMarket, item, 1)) return;

            long sellPrice = DynamicPriceCalculator.getSellPrice(
                    level, village.get(), data, item, basePrice.sellPrice());
            long buyPrice = DynamicPriceCalculator.getBuyPrice(
                    level, village.get(), data, item, basePrice.buyPrice());

            int emeraldSell = (int) Math.max(1, sellPrice / 64);
            int emeraldBuy  = (int) Math.max(1, buyPrice / 64);

            // offers is unused currently — this is a no-op but keeps the
            // logic intact for when the vanilla merchant screen is wired up
        });
    }



    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        entity.getNavigation().stop();
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                ItemStack.EMPTY
        );
        productionBuildings.clear();
        currentBuildingIndex = 0;
    }

    @Override
    public void stop() { goIdle(); }

    public boolean isOpenForTrade() {
        return phase == Phase.OPEN_FOR_TRADE;
    }
}
