package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.MarketPriceHelper;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStall;
import tterrag1112.life_in_the_village.Village.Economy.Market.MarketStallPlacer;
import tterrag1112.life_in_the_village.Village.Markets.Complex.MarketWorkPost;
import tterrag1112.life_in_the_village.Village.Markets.Complex.StallGoods;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Stationary MERCHANT behaviour (WORK priority 1; {@code
 * CaravanMerchantBehavior} at priority 0 pre-empts on caravan duty).
 *
 * <p>Phase 3a — the merchant <b>owns and mans a market stall</b>: it
 * acquires a home stall ({@code getStallByOwner} else {@code claimSlot},
 * {@code OwnerType.NPC}, rent-free via {@code Long.MAX_VALUE}), walks to
 * its {@link MarketWorkPost} (counter + aisle facing) and holds there for
 * the work period, depositing its existing stock into the stall via
 * {@link StallGoods}. Selling (player + NPC) serves from that stall, so
 * the 2c "sell goes to the hub" gap closes automatically.
 *
 * <p>Phase 3b — the merchant <b>autonomously restocks</b> its stall: while
 * manning, on a restock cooldown, it checks whether any sellable
 * (explicit-price) item is below {@link #STALL_TARGET_PER_ITEM} in the
 * stall (+ hub backstock); if so it enters {@link Phase#COLLECTING} for
 * the most-depleted item, procures it through the canonical channel path
 * ({@code TradeIntent.buy} → {@code ChannelRouter.findBestChannel} → walk
 * to {@code quote.location()} → {@code channel.execute}, which settles via
 * {@code NpcEconomy.settlePurchase} and applies tax), deposits the goods
 * into its owned stall, and returns to manning. Manning is the default;
 * restock is occasional (cooldown-gated) so the counter isn't abandoned.
 */
public class MerchantBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantBehavior.class);

    private enum Phase {
        IDLE, STOCKING, OPEN_FOR_TRADE, COLLECTING
    }

    private static final int IDLE_COOLDOWN = 1200;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final double WALK_SPEED = 0.6;
    private static final double COLLECT_WALK_SPEED = 0.55;
    private static final double COLLECT_REACH_SQ = 6.25;

    // 3b restock tuning.
    /** Desired stock of each sellable item in the stall (+ hub backstock). */
    private static final int STALL_TARGET_PER_ITEM = 32;
    /** Max units procured for one item per restock trip. */
    private static final int RESTOCK_BATCH = 16;
    /** Ticks between restock trips — keeps manning the default. */
    private static final int RESTOCK_COOLDOWN = 2400;
    /** Shorter recheck when a scan found nothing to restock. */
    private static final int RESTOCK_RECHECK = 600;

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

    // 3b transient restock state (not persisted).
    private int restockCooldown = 0;
    private Item restockItem = null;

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
        restockCooldown = RESTOCK_COOLDOWN; // settle in before first restock
        restockItem = null;
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
            case COLLECTING     -> collect(level);
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
     *  buying from producers to restock is the COLLECTING phase (3b). */
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
        phase = Phase.OPEN_FOR_TRADE;
    }

    /** Hold position at the work-post for the work period, emerald in hand
     *  to signal open for trade. While manning, on a cooldown, check
     *  whether the stall needs restocking and, if so, enter COLLECTING.
     *  {@code canStillUse} ends the behaviour when work time is over. */
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

        // 3b — occasional restock. Manning is the default; only leave the
        // counter when the cooldown has elapsed AND something is low.
        if (restockCooldown > 0) { restockCooldown--; return; }
        if (ownedStall == null) { restockCooldown = RESTOCK_RECHECK; return; }

        Item depleted = mostDepletedSellable(level);
        if (depleted == null) {
            restockCooldown = RESTOCK_RECHECK; // nothing low — recheck later
            return;
        }
        restockItem = depleted;
        phase = Phase.COLLECTING;
    }

    /** The sellable (explicit-price) item furthest below target in the
     *  stall (+ hub backstock), or null if all are stocked. */
    private Item mostDepletedSellable(ServerLevel level) {
        Item worst = null;
        int worstDeficit = 0;
        for (Item item : MarketPriceHelper.getAllExplicitPrices().keySet()) {
            int have = StallGoods.available(level, market, ownedStall, item);
            int deficit = STALL_TARGET_PER_ITEM - have;
            if (deficit > worstDeficit) {
                worstDeficit = deficit;
                worst = item;
            }
        }
        return worst;
    }

    // =========================================================================
    // 3b — autonomous restock through the channel + settlement path
    // =========================================================================

    /**
     * Procures {@link #restockItem} through the canonical channel path and
     * deposits it into the owned stall. Replaces the old dead direct-pay
     * body: pricing + settlement (incl. market tax) are the channel's job
     * via {@code NpcEconomy.settlePurchase}; no {@code entity.pay} here.
     * Any abort path returns to manning with the cooldown reset so the
     * merchant doesn't thrash man↔collect.
     */
    private void collect(ServerLevel level) {
        if (restockItem == null || market == null || ownedStall == null) {
            endRestock();
            return;
        }

        VillageSavedData data = VillageSavedData.get(level);
        Village village = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) { endRestock(); return; }

        int have = StallGoods.available(level, market, ownedStall, restockItem);
        int want = Math.min(RESTOCK_BATCH, STALL_TARGET_PER_ITEM - have);
        if (want <= 0) { endRestock(); return; } // filled while we walked

        long ceiling = MarketPriceHelper.getDynamicBuyPrice(level, village, restockItem);
        if (ceiling <= 0) ceiling = MarketPriceHelper.getBaseBuyPrice(restockItem);

        TradeIntent intent = TradeIntent.buy(
                restockItem, want, entity.getUUID(),
                market.getId(), village.getId(),
                ceiling, TradeIntent.Urgency.NORMAL, Set.of());

        Optional<ChannelQuote> quoteOpt =
                ChannelRouter.findBestChannel(intent, village, data, level);
        if (quoteOpt.isEmpty()) { endRestock(); return; } // no source — man

        ChannelQuote quote = quoteOpt.get();

        // Walk to the source if we're not close enough yet (stay COLLECTING).
        BlockPos loc = quote.location();
        if (loc != null && entity.distanceToSqr(
                loc.getX() + 0.5, loc.getY(), loc.getZ() + 0.5) > COLLECT_REACH_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(loc, (float) COLLECT_WALK_SPEED, 1));
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Execute — channel settles payment (settlePurchase) + applies tax.
        TradeResult result = quote.channel().execute(intent, quote, level, data);
        if (result.success() && result.quantityFilled() > 0) {
            StallGoods.store(level, market, ownedStall,
                    new ItemStack(restockItem, result.quantityFilled()));
            LOGGER.debug("[Merchant] {} restocked {}x{} into stall {}",
                    entity.getUUID(), result.quantityFilled(),
                    restockItem, ownedStall.getSlotIndex());
        }
        endRestock();
    }

    /** Finish a restock trip (or aborted attempt) and return to manning. */
    private void endRestock() {
        restockItem = null;
        restockCooldown = RESTOCK_COOLDOWN;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        phase = Phase.OPEN_FOR_TRADE;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        ownedStall = null;
        restockItem = null;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.setItemInHand(
                net.minecraft.world.InteractionHand.MAIN_HAND,
                ItemStack.EMPTY
        );
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
