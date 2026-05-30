package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.*;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;
import tterrag1112.life_in_the_village.Village.Markets.Complex.MarketWorkPost;
import tterrag1112.life_in_the_village.Village.Markets.Complex.StallGoods;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Stationary MERCHANT behaviour (WORK priority 1; {@code
 * CaravanMerchantBehavior} at priority 0 pre-empts on caravan duty).
 *
 * <p>Phase 3a — the merchant <b>owns and mans a market stall</b>:
 * <ul>
 *   <li>On entering work it acquires a home stall — its existing owned
 *       stall ({@code getStallByOwner}) or, failing that, a freshly
 *       claimed vacant one ({@code claimSlot}, {@code OwnerType.NPC},
 *       rent-free via {@code rentUntil = Long.MAX_VALUE} so {@code
 *       MarketRentManager} skips it as its workplace).</li>
 *   <li>It walks to the stall's {@link MarketWorkPost} (counter + aisle
 *       facing) and holds there for the work period — no more flocking to
 *       the building corner.</li>
 *   <li>It deposits its existing stock into the <b>owned stall</b> via
 *       {@link StallGoods} (stall-chest first, hub overflow), so selling
 *       (player + NPC) serves from the merchant's stall and the 2c
 *       "sell goes to the hub" gap closes automatically.</li>
 * </ul>
 *
 * <p>Autonomous restock from producers (the dead {@link #collect} method,
 * {@code productionBuildings}, a COLLECTING phase) is <b>Phase 3b</b> and
 * is intentionally left untouched here.
 */
public class MerchantBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantBehavior.class);

    private enum Phase {
        IDLE, STOCKING, OPEN_FOR_TRADE
    }

    private static final int IDLE_COOLDOWN = 1200;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final double WALK_SPEED = 0.6;

    private TownspersonMob entity;

    public MerchantBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;

    private Building market = null;
    /** The merchant's home stall (cache of the persisted owner record). */
    private MarketStall ownedStall = null;

    // 3b territory — populated by the producer-restock loop, unused in 3a.
    private List<Building> productionBuildings = new ArrayList<>();
    private int currentBuildingIndex = 0;

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        // Deterministic: during work, man the stall. (Was a nextInt(40)
        // lottery that left the stall unmanned ~39/40 start checks.)
        return phase == Phase.IDLE;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        VillageSavedData data = VillageSavedData.get(level);
        market = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.MARKET)
                .orElse(null);

        if (market == null) { goIdle(); return; }

        ownedStall = acquireStall(level, data);
        phase = Phase.STOCKING;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        switch (phase) {
            case STOCKING       -> stockMarket(level);
            case OPEN_FOR_TRADE -> manStall(level);
            default -> {}
        }
    }

    // =========================================================================
    // 3a — own + man the stall
    // =========================================================================

    /**
     * Acquires (or re-acquires) the merchant's home stall: its existing
     * owned stall if still active at this market, else a freshly claimed
     * vacant one. Rent-free — claimed with {@code rentUntil =
     * Long.MAX_VALUE}, which {@code MarketRentManager} skips (the resident
     * merchant's stall is its workplace, not a leased pitch). Returns
     * {@code null} when no vacant stall exists (graceful: the merchant
     * then mans the building origin and deposits to the hub).
     */
    private MarketStall acquireStall(ServerLevel level, VillageSavedData data) {
        MarketStall existing = data.getStallByOwner(entity.getUUID()).orElse(null);
        if (existing != null && existing.isActive()
                && market.getId().equals(existing.getMarketBuildingId())) {
            return existing;
        }
        MarketStall claimed = MarketStallPlacer.claimSlot(
                level, market, entity.getUUID(),
                MarketStall.OwnerType.NPC, Long.MAX_VALUE, data).orElse(null);
        if (claimed == null) {
            LOGGER.debug("[Merchant] {} found no vacant stall at market {}; "
                    + "manning building origin", entity.getUUID(), market.getId());
        }
        return claimed;
    }

    /** Where the merchant stands: the owned stall's work-post (counter,
     *  aisle-facing), falling back to the market origin when stall-less. */
    private BlockPos workPostStand() {
        if (ownedStall != null && market != null) {
            Optional<MarketWorkPost.WorkPost> wp =
                    MarketWorkPost.forStall(market, ownedStall);
            if (wp.isPresent()) return wp.get().stand();
        }
        return market != null ? market.getShape().getOrigin() : entity.blockPosition();
    }

    /** Walk to the work-post, deposit existing stock into the owned stall,
     *  then open for trade. 3a moves only stock the merchant already has;
     *  buying from producers to restock is 3b. */
    private void stockMarket(ServerLevel level) {
        if (market == null) { goIdle(); return; }

        BlockPos stand = workPostStand();
        if (!withinReach(stand)) {
            walkTo(stand);
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Deposit personal inventory into the owned stall (stall-chest
        // first, hub overflow). A null stall stores straight to the hub.
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack moving = stack.copy();
            StallGoods.store(level, market, ownedStall, moving);
            inv.setItem(i, moving.isEmpty() ? ItemStack.EMPTY : moving);
        }

        if (ownedStall != null) {
            MarketWorkPost.forStall(market, ownedStall)
                    .ifPresent(wp -> faceDirection(wp.facing()));
        }
        regenerateMarketOffers(level);
        phase = Phase.OPEN_FOR_TRADE;
    }

    /** Hold position at the work-post for the work period, emerald in hand
     *  to signal open for trade. {@code canStillUse} ends the behaviour
     *  when work time is over. */
    private void manStall(ServerLevel level) {
        BlockPos stand = workPostStand();
        if (!withinReach(stand)) {
            walkTo(stand);
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (!entity.getMainHandItem().is(Items.EMERALD)) {
            entity.setItemInHand(
                    net.minecraft.world.InteractionHand.MAIN_HAND,
                    new ItemStack(Items.EMERALD));
        }
    }

    private boolean withinReach(BlockPos pos) {
        return entity.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5)
                <= INTERACT_RANGE_SQ;
    }

    private void walkTo(BlockPos pos) {
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, WALK_SPEED));
    }

    private void faceDirection(Direction dir) {
        if (dir == null) return;
        float yaw = dir.toYRot();
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
    }

    // =========================================================================
    // 3b territory — dead until the producer-restock prompt revives it
    // =========================================================================

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
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    targetPos.getX(), targetPos.getY(),
                    targetPos.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        VillageSavedData data = VillageSavedData.get(level);
        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name));

        // Build a priority list — items with low market stock come first.
        // Uses MarketPriceHelper so every item (explicit or default) is
        // considered, not just those with an explicit entry.
        List<Map.Entry<Item, MarketPriceData.ItemPrice>> prioritized =
                new ArrayList<>(MarketPriceHelper.getAllExplicitPrices().entrySet());
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

            // Resolve buy price through the helper — handles village
            // dynamic pricing when available, falls back to base otherwise
            long pricePerItem = village
                    .map(v -> MarketPriceHelper.getDynamicBuyPrice(level, v, item))
                    .orElseGet(() -> MarketPriceHelper.getBaseBuyPrice(item));

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

        MarketPriceHelper.getAllExplicitPrices().forEach((item, basePrice) -> {
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
        ownedStall = null;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                ItemStack.EMPTY
        );
        productionBuildings.clear();
        currentBuildingIndex = 0;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        goIdle();
    }

    public boolean isOpenForTrade() {
        return phase == Phase.OPEN_FOR_TRADE;
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
