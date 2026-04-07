package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Merchant.MerchantGoal;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.*;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
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

    private MarketStall stallTarget = null;


    public SellToMarketGoal(TownspersonMob entity, Map<Item, Integer> sellableItems) {
        this.entity = entity;
        this.sellableItems = sellableItems;
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

        // Check own stall first
        MarketStall ownStall = data.getStallByOwner(entity.getUUID()).orElse(null);
        if (ownStall != null && ownStall.isActive()) {
            stallTarget = ownStall;
            market = data.getBuildingById(ownStall.getMarketBuildingId()).orElse(null);
            merchantNpc = null;
            return buildItemsToSell(level, data);
        }

        // No stall — find the merchant
        stallTarget = null;
        market = findMarket(level, data);
        if (market == null) return false;

        merchantNpc = findMerchant(level);
        if (merchantNpc == null) return false;

        return buildItemsToSell(level, data);
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

        VillageSavedData data = VillageSavedData.get(level);
        Building assignedBuilding = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById).orElse(null);
        if (assignedBuilding == null) { goIdle(); return; }

        if (stallTarget != null) {
            // ── Own stall: deposit directly into stall chest ─────────────────
            sellToOwnStall(level, assignedBuilding);
        } else {
            // ── Merchant: existing payment flow ──────────────────────────────
            sellToMerchant(level, data, assignedBuilding);
        }
    }
    private void sellToMerchant(ServerLevel level, VillageSavedData data,
                                Building assignedBuilding) {
        if (merchantNpc == null || !merchantNpc.isAlive()) {
            goIdle();
            return;
        }

        // Face the merchant
        entity.getLookControl().setLookAt(
                merchantNpc.getX(), merchantNpc.getY() + merchantNpc.getEyeHeight(),
                merchantNpc.getZ());

        if (sellTimer < 20) return;

        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();
        boolean soldAnything = false;

        for (Map.Entry<Item, Integer> entry : itemsToSell.entrySet()) {
            Item item = entry.getKey();
            int toSell = entry.getValue();
            if (toSell <= 0) continue;

            MarketPriceData.ItemPrice basePrice = priceData.getPrice(item).orElse(null);
            if (basePrice == null) continue;

            long pricePerItem = village.map(v ->
                    DynamicPriceCalculator.getBuyPrice(
                            level, v, data, item, basePrice.buyPrice())
            ).orElse(basePrice.buyPrice());

            CurrencyValue totalPayment = CurrencyValue.of(pricePerItem * toSell);
            if (!merchantNpc.canAffordWithBuilding(totalPayment, level)) {
                long canAfford = merchantNpc.getWealth().toBronze() / pricePerItem;
                toSell = (int) Math.min(toSell, canAfford);
                if (toSell <= 0) continue;
                totalPayment = CurrencyValue.of(pricePerItem * toSell);
            }

            boolean taken = BuildingStorageAccess.takeItem(
                    level, assignedBuilding, item, toSell);
            if (!taken) continue;

            BuildingStorageAccess.storeItem(
                    level, market, new ItemStack(item, toSell));

            NpcEconomy.npcPay(merchantNpc, entity, totalPayment, level);

// Record revenue to the seller's business treasury as well
            CurrencyValue finalTotalPayment = totalPayment;
            entity.getAssignedBuildingId().ifPresent(buildingId ->
                    NpcEconomy.recordRevenue(entity, buildingId,
                            finalTotalPayment, level, data));

            System.out.println(entity.getNpcName() + " sold " + toSell
                    + "x " + item.getDescriptionId()
                    + " to merchant for " + totalPayment);

            soldAnything = true;
        }

        if (soldAnything) {
            MerchantGoal merchantGoal = merchantNpc.getGoal(MerchantGoal.class);
            if (merchantGoal != null) {
                merchantGoal.regenerateMarketOffers(level);
            }
        }

        goIdle();
    }

    private void sellToOwnStall(ServerLevel level, Building assignedBuilding) {
        if (stallTarget.getChestPos().equals(net.minecraft.core.BlockPos.ZERO)) {
            goIdle(); return;
        }

        BlockEntity be = level.getBlockEntity(stallTarget.getChestPos());
        if (!(be instanceof Container chest)) { goIdle(); return; }

        MarketPriceData priceData = MarketPriceRegistry.INSTANCE.getDefault();

        for (Map.Entry<Item, Integer> entry : itemsToSell.entrySet()) {
            Item item = entry.getKey();
            int toMove = entry.getValue();
            if (toMove <= 0) continue;

            // Check how much is in building storage
            int available = BuildingStorageAccess.countItem(level, assignedBuilding, item);
            toMove = Math.min(toMove, available);
            if (toMove <= 0) continue;

            // Don't overfill chest — check remaining space
            int chestSpace = countChestSpace(chest);
            toMove = Math.min(toMove, chestSpace);
            if (toMove <= 0) continue;

            boolean taken = BuildingStorageAccess.takeItem(
                    level, assignedBuilding, item, toMove);
            if (!taken) continue;

            // Move to chest
            ItemStack toStore = new ItemStack(item, toMove);
            addToChest(chest, toStore);

            System.out.println(entity.getNpcName() + " stocked stall with "
                    + toMove + "x " + item.getDescriptionId());
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
    private static int countChestSpace(Container chest) {
        int space = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack s = chest.getItem(i);
            if (s.isEmpty()) {
                space += 64;
            } else {
                space += s.getMaxStackSize() - s.getCount();
            }
        }
        return space;
    }

    private static void addToChest(Container chest, ItemStack stack) {
        // Merge with existing stacks first
        for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack ex = chest.getItem(i);
            if (ex.is(stack.getItem()) && ex.getCount() < ex.getMaxStackSize()) {
                int add = Math.min(ex.getMaxStackSize() - ex.getCount(), stack.getCount());
                ex.grow(add);
                stack.shrink(add);
            }
        }
        // Then empty slots
        for (int i = 0; i < chest.getContainerSize() && !stack.isEmpty(); i++) {
            if (chest.getItem(i).isEmpty()) {
                chest.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
    }
    /**
     * Checks building storage for sellable items above threshold and
     * populates {@code itemsToSell}. Returns true if anything is ready to sell.
     */
    private boolean buildItemsToSell(ServerLevel level, VillageSavedData data) {
        Building assignedBuilding = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .orElse(null);
        if (assignedBuilding == null) return false;

        itemsToSell.clear();
        for (Map.Entry<Item, Integer> entry : sellableItems.entrySet()) {
            Item item = entry.getKey();
            if (hasSurplus(level, assignedBuilding, item)) {
                itemsToSell.put(item, entry.getValue() - SELL_THRESHOLD);
            }
        }
        return !itemsToSell.isEmpty();
    }
}
