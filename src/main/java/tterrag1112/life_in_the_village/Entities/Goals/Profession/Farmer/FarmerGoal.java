// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Farmer/FarmerGoal.java

package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Client.FarmingVisualEffects;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.WorkplaceAssignmentManager;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

/**
 * Head farmer goal - manages farm business operations.
 *
 * Responsibilities:
 * - Harvest crops from assigned plots
 * - Replant harvested areas
 * - Purchase seeds when needed
 * - Sell excess produce at market
 * - Manage farmhouse storage
 * - Coordinate with farmhands via role assignments
 */
public class FarmerGoal extends Goal {

    private static final int TICKS_PER_ACTION = 40;
    private static final int IDLE_COOLDOWN = 200;
    private static final double INTERACT_RANGE_SQ = 4.0;
    private static final CurrencyValue SEED_PRICE = CurrencyValue.of(1L);
    private static final long SELL_COOLDOWN_TICKS = 24000L; // Once per day

    private final TownspersonMob entity;

    private Phase phase;
    private int actionTimer;
    private int idleCooldown;

    private Building farmhouse;
    private Building market;
    private List<FarmPlot> assignedPlots;
    private FarmPlot currentPlot;

    private List<BlockPos> toHarvest;
    private List<BlockPos> toReplant;
    private Map<Item, Integer> harvestedThisCycle;
    private Map<Item, Integer> toSellAtMarket;

    private long lastSellTick;
    private long lastRoleCheckTick;

    private enum Phase {
        IDLE,
        ANALYZING,
        HARVESTING,
        WALKING_TO_FARMHOUSE,
        DEPOSITING,
        REPLANTING,
        BUYING_SEEDS,
        SELLING_AT_MARKET
    }

    public FarmerGoal(TownspersonMob entity) {
        this.entity = entity;
        this.phase = Phase.IDLE;
        this.toHarvest = new ArrayList<>();
        this.toReplant = new ArrayList<>();
        this.harvestedThisCycle = new HashMap<>();
        this.toSellAtMarket = new HashMap<>();
        this.assignedPlots = new ArrayList<>();
        this.lastSellTick = 0;
        this.lastRoleCheckTick = 0;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (idleCooldown > 0) {
            idleCooldown--;
            return false;
        }

        // Head farmers can always work, but check role for farmhands
        FarmRoleManager.FarmRole role = FarmRoleManager.getRole(entity);

        // Market sellers don't do crop work
        if (role == FarmRoleManager.FarmRole.MARKET_SELLER) {
            return false;
        }

        // Animal specialists and tenders don't do crop work
        if (role == FarmRoleManager.FarmRole.ANIMAL_SPECIALIST ||
                role == FarmRoleManager.FarmRole.ANIMAL_TENDER) {
            return false;
        }

        return findFarmhouse(level) != null;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE;
    }

    @Override
    public void start() {
        phase = Phase.ANALYZING;
        actionTimer = 0;

        if (entity.level() instanceof ServerLevel level) {
            farmhouse = findFarmhouse(level);

            // Periodically reassign roles based on farmhand count
            long currentTick = level.getGameTime();
            if (currentTick - lastRoleCheckTick > 24000L && farmhouse != null) {
                FarmRoleManager.assignRoles(level, farmhouse);
                lastRoleCheckTick = currentTick;
            }
        }
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case IDLE -> { /* do nothing */ }
            case ANALYZING -> analyze(level);
            case HARVESTING -> harvest(level);
            case WALKING_TO_FARMHOUSE -> walkToFarmhouse();
            case DEPOSITING -> deposit(level);
            case REPLANTING -> replant(level);
            case BUYING_SEEDS -> buySeeds(level);
            case SELLING_AT_MARKET -> sellAtMarket(level);
        }
    }

    // =========================================================================
    // Phase: ANALYZING
    // =========================================================================

    private void analyze(ServerLevel level) {
        entity.setCurrentActivity("Planning farm work...");

        VillageSavedData data = VillageSavedData.get(level);

        if (farmhouse == null) {
            goIdle();
            return;
        }

        // Get all plots assigned to this farmhouse
        assignedPlots = data.getFarmPlotsForFarmhouse(farmhouse.getId());

        // Filter to only crop plots
        assignedPlots = assignedPlots.stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .collect(java.util.stream.Collectors.toList());

        if (assignedPlots.isEmpty()) {
            goIdle();
            return;
        }

        // Check role-based task filtering
        FarmRoleManager.FarmRole role = FarmRoleManager.getRole(entity);

        // Clear task lists
        toHarvest.clear();
        toReplant.clear();
        harvestedThisCycle.clear();

        // Scan all assigned plots for work
        for (FarmPlot plot : assignedPlots) {
            scanPlotForTasks(level, plot);
        }

        // Decide what to do based on role and available tasks
        if (!toHarvest.isEmpty() && canHarvest(role)) {
            phase = Phase.HARVESTING;
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_HOE));
            return;
        }

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
            return;
        }

        if (!toReplant.isEmpty() && canPlant(role)) {
            phase = Phase.REPLANTING;
            return;
        }

        // Check if we need seeds
        if (needsSeeds(level) && canPlant(role)) {
            phase = Phase.BUYING_SEEDS;
            return;
        }

        // Check if we should sell at market (head farmer only or market seller role)
        long currentTick = level.getGameTime();
        if (currentTick - lastSellTick > SELL_COOLDOWN_TICKS) {
            if (shouldSellAtMarket(level) && canSell(role)) {
                market = findMarket(level);
                if (market != null) {
                    prepareGoodsForMarket(level);
                    if (!toSellAtMarket.isEmpty()) {
                        phase = Phase.SELLING_AT_MARKET;
                        return;
                    }
                }
            }
        }

        goIdle();
    }

    private void scanPlotForTasks(ServerLevel level, FarmPlot plot) {
        // Scan for mature crops
        for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);

            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                toHarvest.add(cropPos);
            } else if (state.isAir() && level.getBlockState(farmland).getBlock() instanceof FarmBlock) {
                toReplant.add(cropPos);
            }
        }
    }

    private boolean canHarvest(FarmRoleManager.FarmRole role) {
        return role == FarmRoleManager.FarmRole.GENERALIST ||
                role == FarmRoleManager.FarmRole.CROP_SPECIALIST ||
                role == FarmRoleManager.FarmRole.HARVESTER;
    }

    private boolean canPlant(FarmRoleManager.FarmRole role) {
        return role == FarmRoleManager.FarmRole.GENERALIST ||
                role == FarmRoleManager.FarmRole.CROP_SPECIALIST ||
                role == FarmRoleManager.FarmRole.PLANTER;
    }

    private boolean canSell(FarmRoleManager.FarmRole role) {
        // Only head farmer (generalist by default) or designated market sellers
        return role == FarmRoleManager.FarmRole.GENERALIST ||
                role == FarmRoleManager.FarmRole.MARKET_SELLER;
    }

    // =========================================================================
    // Phase: HARVESTING
    // =========================================================================

    private void harvest(ServerLevel level) {
        entity.setCurrentActivity("Harvesting crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toHarvest.isEmpty()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
            return;
        }

        BlockPos cropPos = toHarvest.get(0);
        double distSq = entity.distanceToSqr(
                cropPos.getX(), cropPos.getY(), cropPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    cropPos.getX(), cropPos.getY(), cropPos.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        BlockState state = level.getBlockState(cropPos);
        if (!(state.getBlock() instanceof CropBlock crop)) {
            toHarvest.remove(0);
            return;
        }

        if (!crop.isMaxAge(state)) {
            toHarvest.remove(0);
            return;
        }
        FarmingVisualEffects.showHarvestEffect(level, cropPos, state);
        FarmingVisualEffects.playHarvestSound(level, cropPos);

        // Get drops with seasonal multiplier
        List<ItemStack> drops = Block.getDrops(state, level, cropPos, null);
        float seasonMult = SeasonTracker.getYieldMultiplier(level);

        for (ItemStack drop : drops) {
            int scaledCount = 0;
            for (int i = 0; i < drop.getCount(); i++) {
                if (entity.getRandom().nextFloat() < seasonMult) scaledCount++;
            }
            scaledCount = Math.max(1, scaledCount);

            ItemStack scaled = drop.copy();
            scaled.setCount(scaledCount);
            entity.getPersonalInventory().addItem(scaled);
            harvestedThisCycle.merge(scaled.getItem(), scaledCount, Integer::sum);
        }

        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
        entity.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cropPos,
                SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);

        toHarvest.remove(0);

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
        }
    }

    // =========================================================================
    // Phase: WALKING TO FARMHOUSE
    // =========================================================================

    private void walkToFarmhouse() {
        if (farmhouse == null) {
            goIdle();
            return;
        }

        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            phase = Phase.DEPOSITING;
        }
    }

    // =========================================================================
    // Phase: DEPOSITING
    // =========================================================================

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing harvest");

        SimpleContainer inv = entity.getPersonalInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            // Don't deposit tools
            if (stack.is(Items.IRON_HOE) || stack.is(Items.DIAMOND_HOE)) continue;

            BuildingStorageAccess.storeItem(level, farmhouse, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }

        // Continue harvesting if more crops remain
        if (!toHarvest.isEmpty()) {
            phase = Phase.HARVESTING;
        } else {
            phase = Phase.ANALYZING;
        }
    }

    // =========================================================================
    // Phase: REPLANTING
    // =========================================================================

    private void replant(ServerLevel level) {
        entity.setCurrentActivity("Replanting crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toReplant.isEmpty()) {
            phase = Phase.ANALYZING;
            return;
        }

        BlockPos targetPos = toReplant.get(0);
        double distSq = entity.distanceToSqr(
                targetPos.getX(), targetPos.getY(), targetPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Verify farmland below
        if (!(level.getBlockState(targetPos.below()).getBlock() instanceof FarmBlock)) {
            toReplant.remove(0);
            return;
        }

        // Determine crop type for this plot
        FarmPlot plot = findPlotContaining(level, targetPos);
        if (plot == null) {
            toReplant.remove(0);
            return;
        }

        Block cropBlock = getCropBlockForPlot(plot);
        Item seedItem = getSeedItemForPlot(plot);

        if (cropBlock == null) {
            toReplant.remove(0);
            return;
        }

        // Get seed from farmhouse storage
        boolean taken = BuildingStorageAccess.takeItem(level, farmhouse, seedItem, 1);

        if (taken) {
            level.setBlock(targetPos, cropBlock.defaultBlockState(), 3);
            entity.getLookControl().setLookAt(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, targetPos,
                    SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0f, 1.0f);
        }

        toReplant.remove(0);
    }

    // =========================================================================
    // Phase: BUYING SEEDS
    // =========================================================================

    // In FarmerGoal.java, replace the buySeeds method's spending logic:

    private void buySeeds(ServerLevel level) {
        entity.setCurrentActivity("Buying seeds");

        Building market = findMarket(level);
        if (market == null) {
            goIdle();
            return;
        }

        BlockPos target = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Purchase seeds for each plot type
        for (FarmPlot plot : assignedPlots) {
            int plotSize = plot.getFarmlandBlocks(level).size();
            if (plotSize == 0) continue;

            Item seedItem = getSeedItemForPlot(plot);
            int currentSeeds = countSeedsInFarmhouse(level, seedItem);
            int needed = Math.max(0, plotSize - currentSeeds);

            if (needed > 0) {
                CurrencyValue cost = CurrencyValue.of((long) needed * SEED_PRICE.toBronze());

                // FIX: Use proper method to spend from building storage
                if (farmhouse != null) {
                    var containers = BuildingStorageAccess.findInventories(level, farmhouse);
                    if (!containers.isEmpty()) {
                        if (CoinHelper.payWithBuilding(entity.getPersonalInventory(), cost, level, entity.getAssignedBuilding(level).get())) {
                            // Add seeds to farmhouse
                            ItemStack seeds = new ItemStack(seedItem, needed);
                            BuildingStorageAccess.storeItem(level, farmhouse, seeds);

                            System.out.println("Farmer purchased " + needed + " "
                                    + seedItem + " for " + cost);
                        }
                    }
                }
            }
        }

        phase = Phase.ANALYZING;
    }

    // =========================================================================
    // Phase: SELLING AT MARKET
    // =========================================================================

    private void sellAtMarket(ServerLevel level) {
        entity.setCurrentActivity("Selling at market");

        if (market == null) {
            goIdle();
            return;
        }

        BlockPos target = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        // Sell goods and receive coins
        long totalRevenue = 0;
        SimpleContainer personalInv = entity.getPersonalInventory();

        for (Map.Entry<Item, Integer> entry : toSellAtMarket.entrySet()) {
            Item item = entry.getKey();
            int count = entry.getValue();

            long pricePerItem = getMarketPrice(item);
            long revenue = pricePerItem * count;
            totalRevenue += revenue;

            // FIX: Remove items properly
            for (int i = 0; i < personalInv.getContainerSize(); i++) {
                ItemStack stack = personalInv.getItem(i);
                if (stack.is(item)) {
                    int toRemove = Math.min(stack.getCount(), count);
                    stack.shrink(toRemove);
                    count -= toRemove;
                    if (count <= 0) break;
                }
            }

            // Add to market storage
            BuildingStorageAccess.storeItem(level, market, new ItemStack(item, entry.getValue()));
        }

        // Add coins to farmhouse
        if (totalRevenue > 0) {
            CurrencyValue earnings = CurrencyValue.of(totalRevenue);
            var containers = BuildingStorageAccess.findInventories(level, farmhouse);

            // FIX: Cast to SimpleContainer
            if (!containers.isEmpty() && containers.get(0) instanceof SimpleContainer simpleContainer) {
                CoinHelper.giveCoins(simpleContainer, earnings);
            }

            System.out.println("Farmer sold goods for " + earnings + " at market");

            // Update business level and notify players
            WorkplaceAssignmentManager.onWorkplaceSale(level, farmhouse.getId(), (int) totalRevenue);
        }

        lastSellTick = level.getGameTime();
        toSellAtMarket.clear();
        goIdle();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private Building findFarmhouse(ServerLevel level) {
        return entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);
    }

    private Building findMarket(ServerLevel level) {
        return entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .flatMap(village -> village.getBuildingIds().stream()
                        .map(id -> VillageSavedData.get(level).getBuildingById(id))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(b -> b.getType() == BuildingType.MARKET)
                        .findFirst())
                .orElse(null);
    }

    private FarmPlot findPlotContaining(ServerLevel level, BlockPos pos) {
        for (FarmPlot plot : assignedPlots) {
            for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
                if (farmland.above().equals(pos)) {
                    return plot;
                }
            }
        }
        return null;
    }

    private Block getCropBlockForPlot(FarmPlot plot) {
        return switch (plot.getCropType()) {
            case WHEAT, GRAIN, MIXED -> Blocks.WHEAT;
            case CARROTS, VEGETABLE -> Blocks.CARROTS;
            case POTATOES -> Blocks.POTATOES;
            case BEETROOT -> Blocks.BEETROOTS;
            case ORCHARD -> Blocks.WHEAT; // Placeholder
            case PASTURE -> null; // No crops for pasture
        };
    }

    private Item getSeedItemForPlot(FarmPlot plot) {
        return switch (plot.getCropType()) {
            case WHEAT, GRAIN, MIXED -> Items.WHEAT_SEEDS;
            case CARROTS, VEGETABLE -> Items.CARROT;
            case POTATOES -> Items.POTATO;
            case BEETROOT -> Items.BEETROOT_SEEDS;
            case ORCHARD -> Items.WHEAT_SEEDS; // Placeholder
            case PASTURE -> Items.WHEAT_SEEDS; // Unused
        };
    }

    private boolean shouldSellAtMarket(ServerLevel level) {
        if (farmhouse == null) return false;

        int excessWheat = countItemInFarmhouse(level, Items.WHEAT) - 64;
        int excessCarrots = countItemInFarmhouse(level, Items.CARROT) - 32;
        int excessPotatoes = countItemInFarmhouse(level, Items.POTATO) - 32;

        return excessWheat > 0 || excessCarrots > 0 || excessPotatoes > 0;
    }

    private void prepareGoodsForMarket(ServerLevel level) {
        toSellAtMarket.clear();

        int excessWheat = countItemInFarmhouse(level, Items.WHEAT) - 64;
        if (excessWheat > 0) {
            int toTake = Math.min(excessWheat, 32);
            if (BuildingStorageAccess.takeItem(level, farmhouse, Items.WHEAT, toTake)) {
                entity.getPersonalInventory().addItem(new ItemStack(Items.WHEAT, toTake));
                toSellAtMarket.put(Items.WHEAT, toTake);
            }
        }

        int excessCarrots = countItemInFarmhouse(level, Items.CARROT) - 32;
        if (excessCarrots > 0) {
            int toTake = Math.min(excessCarrots, 16);
            if (BuildingStorageAccess.takeItem(level, farmhouse, Items.CARROT, toTake)) {
                entity.getPersonalInventory().addItem(new ItemStack(Items.CARROT, toTake));
                toSellAtMarket.put(Items.CARROT, toTake);
            }
        }

        int excessPotatoes = countItemInFarmhouse(level, Items.POTATO) - 32;
        if (excessPotatoes > 0) {
            int toTake = Math.min(excessPotatoes, 16);
            if (BuildingStorageAccess.takeItem(level, farmhouse, Items.POTATO, toTake)) {
                entity.getPersonalInventory().addItem(new ItemStack(Items.POTATO, toTake));
                toSellAtMarket.put(Items.POTATO, toTake);
            }
        }
    }

    private long getMarketPrice(Item item) {
        if (item == Items.WHEAT) return 2L;
        if (item == Items.CARROT) return 3L;
        if (item == Items.POTATO) return 3L;
        if (item == Items.BEETROOT) return 4L;
        if (item == Items.LEATHER) return 8L;
        if (item == Items.BEEF || item == Items.PORKCHOP || item == Items.MUTTON) return 6L;
        if (item == Items.CHICKEN) return 4L;
        if (item == Items.EGG) return 1L;
        if (item == Items.MILK_BUCKET) return 5L;
        if (item == Items.WHITE_WOOL) return 4L;
        return 1L;
    }

    private boolean needsSeeds(ServerLevel level) {
        if (farmhouse == null) return false;

        for (FarmPlot plot : assignedPlots) {
            Item seedItem = getSeedItemForPlot(plot);
            int available = countSeedsInFarmhouse(level, seedItem);
            int needed = plot.getFarmlandBlocks(level).size();

            if (available < needed) {
                CurrencyValue cost = CurrencyValue.of(
                        (long) (needed - available) * SEED_PRICE.toBronze());
                return entity.canAffordWithBuilding(cost, level);
            }
        }
        return false;
    }

    private int countSeedsInFarmhouse(ServerLevel level, Item seedItem) {
        int total = 0;
        for (var container : BuildingStorageAccess.findInventories(level, farmhouse)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.is(seedItem)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private int countItemInFarmhouse(ServerLevel level, Item item) {
        int total = 0;
        for (var container : BuildingStorageAccess.findInventories(level, farmhouse)) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private boolean isPersonalInventoryNearlyFull() {
        SimpleContainer inv = entity.getPersonalInventory();
        int emptySlots = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) emptySlots++;
        }
        return emptySlots < 3;
    }

    private void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        toHarvest.clear();
        toReplant.clear();
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
    }

    @Override
    public void stop() {
        goIdle();
    }
}