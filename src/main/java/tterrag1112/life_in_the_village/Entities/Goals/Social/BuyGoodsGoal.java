package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.NpcDailyOffset;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelType;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * NPC households buy food and essential goods through the
 * {@link ChannelRouter}. Replaces {@code BuyFromMarketGoal} per spec
 * line 200 — the rename signals the goal is no longer market-locked.
 *
 * <p>Behaviour change vs. the legacy goal: when the village has no
 * market, the router picks {@link ChannelType#DIRECT_BUSINESS} and the
 * NPC walks to a producing workshop instead. Stockpile / caravan paths
 * also become available without further changes here.</p>
 *
 * <p>Travel destination uses the chosen quote's
 * {@link ChannelQuote#location}; falls back to the village centre when
 * the channel didn't supply a location (rare — only the two stub
 * channels would ever do that).</p>
 */
public class BuyGoodsGoal extends Goal {

    private enum Phase { IDLE, WALKING, BUYING }

    private static final int CHECK_INTERVAL = 3600;
    private static final int INTERACT_RANGE_SQ = 9;
    private static final int FOOD_PER_MEMBER = 6;
    private static final int MAX_BUY_PER_TRIP = 16;
    private static final double MAX_SPEND_FRACTION = 0.4;

    /**
     * Minimum ticks between shopping trips. Half an in-game day plus the
     * per-NPC offset so a population staggers trips deterministically and
     * doesn't re-converge after a wave.
     */
    private static final long TRIP_COOLDOWN_TICKS = 12000L;

    /**
     * Household food stock must dip below this fraction of target before
     * a shopping trip is justified. Combined with the cooldown this
     * makes the goal genuinely window-gated rather than near-always-true.
     */
    private static final double LOW_STOCK_FRACTION = 0.5;

    private static final List<Item> FOOD_PRIORITY = List.of(
            Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN, Items.BAKED_POTATO,
            Items.CARROT, Items.POTATO, Items.BEETROOT);

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int checkTimer = 0;
    private final List<PendingPurchase> pending = new ArrayList<>();
    private BlockPos travelTarget = null;
    /** Tick of the last completed shopping trip. */
    private long lastShoppingTick = Long.MIN_VALUE;

    public BuyGoodsGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (entity.isWorkTime() || entity.shouldBeHome()) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;

        // Cooldown gate: one shopping trip per ~half day, staggered by
        // the per-NPC daily offset so a household population doesn't
        // re-converge on the same daytick after a wave.
        long now = level.getGameTime();
        long cooldown = TRIP_COOLDOWN_TICKS + NpcDailyOffset.offset(entity.getUUID());
        if (lastShoppingTick != Long.MIN_VALUE
                && now - lastShoppingTick < cooldown) {
            return false;
        }

        VillageSavedData data = VillageSavedData.get(level);
        Village village = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName).orElse(null);
        if (village == null) return false;

        pending.clear();
        buildShoppingPlan(level, data, village);
        return !pending.isEmpty();
    }

    @Override public void start() {
        if (pending.isEmpty()) { goIdle(); return; }
        travelTarget = pending.get(0).quote.location();
        phase = travelTarget == null ? Phase.BUYING : Phase.WALKING;
    }
    @Override public boolean canContinueToUse() { return phase != Phase.IDLE; }
    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        switch (phase) {
            case WALKING -> {
                if (travelTarget == null) { phase = Phase.BUYING; return; }
                if (entity.distanceToSqr(travelTarget.getX(), travelTarget.getY(), travelTarget.getZ())
                        > INTERACT_RANGE_SQ) {
                    entity.getNavigation().moveTo(
                            travelTarget.getX(), travelTarget.getY(), travelTarget.getZ(), 1.0);
                } else {
                    entity.getNavigation().stop();
                    phase = Phase.BUYING;
                }
            }
            case BUYING -> {
                entity.setCurrentActivity("Shopping");
                executePending(level);
                // Stamp the trip regardless of whether every line
                // succeeded — the cooldown is about "we took a
                // shopping trip", not "we filled the basket".
                lastShoppingTick = level.getGameTime();
                goIdle();
            }
            case IDLE -> {}
        }
    }

    @Override public void stop() { goIdle(); }

    // ── Plan ─────────────────────────────────────────────────────────────────

    private void buildShoppingPlan(ServerLevel level, VillageSavedData data, Village village) {
        int householdSize = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding)
                .map(h -> h.getMemberNpcIds().size())
                .orElse(1);

        int currentFood = countFoodInHouseAndInventory(level, data);
        int foodTarget  = householdSize * FOOD_PER_MEMBER;
        int foodNeeded  = foodTarget - currentFood;
        // Genuine "stock low" gate — don't shop for a +1 top-up; wait
        // until stock drops below LOW_STOCK_FRACTION of target. Pairs
        // with TRIP_COOLDOWN_TICKS in canUse for a true window gate.
        boolean stockLow = currentFood < foodTarget * LOW_STOCK_FRACTION;
        long maxSpend = (long) (entity.getWallet().toBronze() * MAX_SPEND_FRACTION);
        long budget = maxSpend;

        if (foodNeeded > 0 && stockLow) {
            int remaining = Math.min(foodNeeded, MAX_BUY_PER_TRIP);
            for (Item food : FOOD_PRIORITY) {
                if (remaining <= 0 || budget <= 0) break;
                int qty = remaining;
                Optional<ChannelQuote> q = quoteFor(food, qty, village, data, level, budget);
                if (q.isEmpty()) continue;
                int gotten = Math.min(qty, q.get().availableQuantity());
                long cost = q.get().pricePerUnit() * gotten;
                if (cost > budget) {
                    gotten = (int) (budget / Math.max(1, q.get().pricePerUnit()));
                    if (gotten <= 0) continue;
                    cost = q.get().pricePerUnit() * gotten;
                }
                pending.add(new PendingPurchase(food, gotten, q.get()));
                remaining -= gotten;
                budget    -= cost;
            }
        }

        Item tool = toolForProfession(entity.getProfession());
        if (tool != null && !entity.getEconomy().hasItem(tool) && budget > 0) {
            quoteFor(tool, 1, village, data, level, budget).ifPresent(q -> {
                if (q.pricePerUnit() <= q.intent().maxPrice()) {
                    pending.add(new PendingPurchase(tool, 1, q));
                }
            });
        }
    }

    private Optional<ChannelQuote> quoteFor(Item item, int qty, Village village,
                                            VillageSavedData data, ServerLevel level,
                                            long maxSpend) {
        long perUnitCeiling = qty > 0 ? maxSpend / qty : maxSpend;
        TradeIntent intent = TradeIntent.buy(item, qty, entity.getUUID(),
                entity.getAssignedBuildingId().orElse(null),
                village.getId(), perUnitCeiling, Urgency.NORMAL, Set.of());
        return ChannelRouter.findBestChannel(intent, village, data, level);
    }

    private int countFoodInHouseAndInventory(ServerLevel level, VillageSavedData data) {
        int count = 0;
        Building home = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
        if (home != null) {
            for (var c : BuildingStorageAccess.findInventories(level, home)) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack stack = c.getItem(i);
                    if (!stack.isEmpty() && isFood(stack.getItem())) count += stack.getCount();
                }
            }
        }
        for (int i = 0; i < entity.getPersonalInventory().getContainerSize(); i++) {
            ItemStack stack = entity.getPersonalInventory().getItem(i);
            if (!stack.isEmpty() && isFood(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    // ── Execute ──────────────────────────────────────────────────────────────

    private void executePending(ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        UUID villageId = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .map(Village::getId).orElse(null);
        if (villageId == null) return;

        for (PendingPurchase p : pending) {
            EconomicChannel channel = ChannelRouter.registeredChannels().stream()
                    .filter(c -> c.type() == p.quote.channel())
                    .findFirst().orElse(null);
            if (channel == null) continue;

            // Re-check stale quote — the channel may have run out since plan time.
            if (p.quote.isExpired(level.getGameTime())) continue;

            TradeResult result = channel.execute(p.quote, p.quote.intent(), level);
            if (!result.success()) continue;

            int qty = result.quantityTraded();
            if (qty <= 0) continue;
            // Food → house storage; tools → personal inventory.
            if (isFood(p.item)) {
                Building home = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
                if (home != null) {
                    BuildingStorageAccess.storeItem(level, home, new ItemStack(p.item, qty));
                } else {
                    entity.getPersonalInventory().addItem(new ItemStack(p.item, qty));
                }
            } else {
                entity.getPersonalInventory().addItem(new ItemStack(p.item, qty));
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isFood(Item item) {
        return item.components().has(DataComponents.FOOD);
    }

    private static Item toolForProfession(Profession prof) {
        return switch (prof) {
            case FARMER, FARMHAND -> Items.IRON_HOE;
            case MINER            -> Items.IRON_PICKAXE;
            case CARPENTER        -> Items.IRON_AXE;
            case GUARD            -> Items.IRON_SWORD;
            default               -> null;
        };
    }

    private void goIdle() {
        phase = Phase.IDLE;
        entity.getNavigation().stop();
        pending.clear();
        travelTarget = null;
    }

    private record PendingPurchase(Item item, int quantity, ChannelQuote quote) {}
}
