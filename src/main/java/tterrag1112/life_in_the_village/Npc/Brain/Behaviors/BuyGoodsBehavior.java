package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelQuote;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.ChannelRouter;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.EconomicChannel;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeIntent;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.TradeResult;
import tterrag1112.life_in_the_village.Npc.Economy.Channels.Urgency;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6.2.a — migrated from {@code BuyGoodsGoal}. SOCIAL activity,
 * priority 5. Builds a shopping plan via {@link ChannelRouter}, walks
 * to the chosen channel's location, executes purchases, returns to
 * idle. Plan-building is gated by a {@link NpcMemoryTypes#LAST_SHOPPING_TICK}
 * TTL memory (replaces the goal's {@code checkTimer} field).
 *
 * <p>External APIs (ChannelRouter / ChannelQuote / EconomicChannel /
 * BuildingStorageAccess) are preserved 1:1 — only the lifecycle shell
 * and the cooldown mechanism changed.
 */
public class BuyGoodsBehavior extends Behavior<TownspersonMob> {

    private enum Phase { WALKING, BUYING, DONE }

    private static final int INTERACT_RANGE_SQ = 9;
    private static final int FOOD_PER_MEMBER = 6;
    private static final int MAX_BUY_PER_TRIP = 16;
    private static final double MAX_SPEND_FRACTION = 0.4;
    /** Cooldown after a plan check (success or empty) — replaces the
     *  goal's 3600-tick checkTimer. Spec mandates ~12000 (10 min). */
    private static final long COOLDOWN_TICKS = 12000L;
    private static final float WALK_SPEED = 1.0f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = 6000;

    private static final List<Item> FOOD_PRIORITY = List.of(
            Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN, Items.BAKED_POTATO,
            Items.CARROT, Items.POTATO, Items.BEETROOT);

    private Phase phase = Phase.DONE;
    private final List<PendingPurchase> pending = new ArrayList<>();
    private BlockPos travelTarget = null;

    public BuyGoodsBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.LAST_SHOPPING_TICK.get(), MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (entity.isWorkTime() || entity.shouldBeHome()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        // The plan check is expensive — we only run it here, gated by the
        // LAST_SHOPPING_TICK TTL absence (entry condition above).
        VillageSavedData data = VillageSavedData.get(level);
        Village village = entity.getAssignedVillageName()
                .flatMap(data::getVillageByName).orElse(null);
        if (village == null) return false;
        pending.clear();
        buildShoppingPlan(level, entity, data, village);
        return !pending.isEmpty();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != Phase.DONE;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (pending.isEmpty()) { phase = Phase.DONE; return; }
        travelTarget = pending.get(0).quote.location();
        if (travelTarget == null) {
            phase = Phase.BUYING;
        } else {
            phase = Phase.WALKING;
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(travelTarget, WALK_SPEED, CLOSE_ENOUGH));
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        switch (phase) {
            case WALKING -> {
                if (travelTarget == null) { phase = Phase.BUYING; return; }
                if (entity.distanceToSqr(travelTarget.getX(), travelTarget.getY(), travelTarget.getZ())
                        <= INTERACT_RANGE_SQ) {
                    entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    phase = Phase.BUYING;
                }
            }
            case BUYING -> {
                entity.setCurrentActivity("Shopping");
                executePending(level, entity);
                phase = Phase.DONE;
            }
            case DONE -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        // Always stamp the cooldown — success or empty basket — matches the
        // Track 2 tail behavior that explicitly throttled retries.
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.LAST_SHOPPING_TICK.get(), gameTime, COOLDOWN_TICKS);
        pending.clear();
        travelTarget = null;
        phase = Phase.DONE;
    }

    // ── Plan (copied verbatim from BuyGoodsGoal) ────────────────────────────
    private void buildShoppingPlan(ServerLevel level, TownspersonMob entity,
                                   VillageSavedData data, Village village) {
        int householdSize = entity.getFamily().getHouseId()
                .flatMap(data::getHouseholdForBuilding)
                .map(h -> h.getMemberNpcIds().size())
                .orElse(1);
        int currentFood = countFoodInHouseAndInventory(level, entity, data);
        int foodNeeded = (householdSize * FOOD_PER_MEMBER) - currentFood;
        long maxSpend = (long) (entity.getWallet().toBronze() * MAX_SPEND_FRACTION);
        long budget = maxSpend;

        if (foodNeeded > 0) {
            int remaining = Math.min(foodNeeded, MAX_BUY_PER_TRIP);
            for (Item food : FOOD_PRIORITY) {
                if (remaining <= 0 || budget <= 0) break;
                int qty = remaining;
                Optional<ChannelQuote> q = quoteFor(entity, food, qty, village, data, level, budget);
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
                budget -= cost;
            }
        }

        Item tool = toolForProfession(entity.getProfession());
        if (tool != null && !entity.getEconomy().hasItem(tool) && budget > 0) {
            quoteFor(entity, tool, 1, village, data, level, budget).ifPresent(q -> {
                if (q.pricePerUnit() <= q.intent().maxPrice()) {
                    pending.add(new PendingPurchase(tool, 1, q));
                }
            });
        }
    }

    private static Optional<ChannelQuote> quoteFor(TownspersonMob entity, Item item, int qty,
                                                    Village village, VillageSavedData data,
                                                    ServerLevel level, long maxSpend) {
        long perUnitCeiling = qty > 0 ? maxSpend / qty : maxSpend;
        TradeIntent intent = TradeIntent.buy(item, qty, entity.getUUID(),
                entity.getAssignedBuildingId().orElse(null),
                village.getId(), perUnitCeiling, Urgency.NORMAL, Set.of());
        return ChannelRouter.findBestChannel(intent, village, data, level);
    }

    private static int countFoodInHouseAndInventory(ServerLevel level, TownspersonMob entity,
                                                     VillageSavedData data) {
        int count = 0;
        Building home = entity.getFamily().getHouseId()
                .flatMap(data::getBuildingById).orElse(null);
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

    private void executePending(ServerLevel level, TownspersonMob entity) {
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
            if (p.quote.isExpired(level.getGameTime())) continue;

            TradeResult result = channel.execute(p.quote, p.quote.intent(), level);
            if (!result.success()) continue;
            int qty = result.quantityTraded();
            if (qty <= 0) continue;
            if (isFood(p.item)) {
                Building home = entity.getFamily().getHouseId()
                        .flatMap(data::getBuildingById).orElse(null);
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

    private static boolean isFood(Item item) {
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

    private record PendingPurchase(Item item, int quantity, ChannelQuote quote) {}
}
