package tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

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

        priceData.getAllPrices().forEach((item, basePrice) -> {
            int available = BuildingStorageAccess.countItem(level, target, item);
            if (available <= 0) return;

            int toTake = Math.min(available, 16);

            // Calculate how much to pay using dynamic buy price
            long pricePerItem = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            long totalCost = pricePerItem * toTake;
            CurrencyValue cost = CurrencyValue.of(totalCost);

            // Only take if merchant can afford it
            if (!entity.canAfford(cost)) {
                System.out.println("Merchant can't afford " + toTake + "x "
                        + item.getDescriptionId() + " for " + cost);
                return;
            }

            boolean taken = BuildingStorageAccess.takeItem(
                    level, target, item, toTake);
            if (!taken) return;

            // Pay the NPC assigned to this building
            TownspersonMob assignedNpc = findAssignedNpc(level, target);
            if (assignedNpc != null) {
                entity.pay(assignedNpc, cost);
                System.out.println("Merchant paid " + cost + " to "
                        + assignedNpc.getNpcName() + " for " + toTake
                        + "x " + item.getDescriptionId());
            } else {
                // No assigned NPC — pay into building storage as coins
                entity.spend(cost);
                System.out.println("Merchant spent " + cost
                        + " (no assigned NPC) for " + toTake
                        + "x " + item.getDescriptionId());
            }

            entity.getPersonalInventory().addItem(new ItemStack(item, toTake));
        });

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

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        var offers = new net.minecraft.world.item.trading.MerchantOffers();

        priceData.getAllPrices().forEach((item, basePrice) -> {
            // Only list items that are in the market
            if (!BuildingStorageAccess.hasItem(level, market, item, 1)) return;

            // Compute dynamic sell price
            long sellPrice = DynamicPriceCalculator.getSellPrice(
                    level, village.get(), data, item, basePrice.sellPrice());
            long buyPrice = DynamicPriceCalculator.getBuyPrice(
                    level, village.get(), data, item, basePrice.buyPrice());

            // Convert bronze to coin items for MerchantOffer
            // For now use emeralds as proxy — 1 emerald = 64 bronze (1 silver)
            int emeraldSell = (int) Math.max(1, sellPrice / 64);
            int emeraldBuy  = (int) Math.max(1, buyPrice / 64);

            // Sell offer — player pays emeralds, gets item
            offers.add(new net.minecraft.world.item.trading.MerchantOffer(
                    new net.minecraft.world.item.trading.ItemCost(
                            ModItems.DENIER_ARGENT, emeraldSell),
                    new ItemStack(item, 1),
                    64, 0, 0.05f
            ));
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
