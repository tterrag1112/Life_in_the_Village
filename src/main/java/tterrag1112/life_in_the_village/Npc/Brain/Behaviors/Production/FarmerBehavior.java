
package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Client.FarmingVisualEffects;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.BuildingEconomy;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Economy.Currency.NpcEconomy;
import tterrag1112.life_in_the_village.Village.Economy.EconomicBalance;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Economy.VillageEconomy;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Head farmer goal — manages crop operations on assigned plots.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Harvest mature crops from all assigned plots</li>
 *   <li>Deposit harvested goods in the farmhouse</li>
 *   <li>Replant farmland</li>
 *   <li>Purchase seeds from the market when short</li>
 *   <li>Coordinate farmhand roles via {@link FarmRoleAssigner}</li>
 * </ul>
 *
 * <h3>Selling</h3>
 * Selling surplus crops is NOT handled here — {@code SellToMarketGoal}
 * (registered alongside this goal in {@code ProfessionGoalFactory})
 * owns that flow. It uses dynamic pricing, pays the merchant correctly,
 * and hooks {@code WorkplaceAssignmentManager.onWorkplaceSale()}.
 */
public class FarmerBehavior extends Behavior<TownspersonMob> {

    // =========================================================================
    // Constants
    // =========================================================================

    private static final int    TICKS_PER_ACTION   = 40;
    private static final int    IDLE_COOLDOWN      = 200;
    private static final double INTERACT_RANGE_SQ  = 4.0;

    // =========================================================================
    // State
    // =========================================================================

    private TownspersonMob entity;

    public FarmerBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }

    private Phase phase;
    private int   actionTimer;
    private int   idleCooldown;

    private Building            farmhouse;
    private List<FarmPlot>      assignedPlots;
    private final List<BlockPos> toHarvest;
    private final List<BlockPos> toReplant;
    private final Map<Item, Integer> harvestedThisCycle;

    private enum Phase {
        IDLE,
        ANALYZING,
        HARVESTING,
        WALKING_TO_FARMHOUSE,
        DEPOSITING,
        REPLANTING,
        BUYING_SEEDS
    }

    

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!(entity.level() instanceof ServerLevel)) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!entity.isWorkTime()) return false;
        return entity.getAssignedBuildingId().isPresent();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return phase != Phase.IDLE;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.ANALYZING;
        actionTimer = 0;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;

        switch (phase) {
            case IDLE               -> { /* no-op */ }
            case ANALYZING          -> analyze(level);
            case HARVESTING         -> harvest(level);
            case WALKING_TO_FARMHOUSE -> walkToFarmhouse();
            case DEPOSITING         -> deposit(level);
            case REPLANTING         -> replant(level);
            case BUYING_SEEDS       -> buySeeds(level);
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) { this.entity = entity;
        goIdle(); }

    // =========================================================================
    // Phase: ANALYZING
    // =========================================================================

    private void analyze(ServerLevel level) {
        entity.setCurrentActivity("Planning farm work...");

        VillageSavedData data = VillageSavedData.get(level);

        // FIX #10: resolve farmhouse BEFORE the null check
        farmhouse = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) { goIdle(); return; }

        // Gather crop plots assigned to this farmhouse
        assignedPlots = data.getFarmPlotsForFarmhouse(farmhouse.getId()).stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .collect(Collectors.toList());

        if (assignedPlots.isEmpty()) { goIdle(); return; }

        // Role-based task filtering
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);

        toHarvest.clear();
        toReplant.clear();
        harvestedThisCycle.clear();

        for (FarmPlot plot : assignedPlots) {
            scanPlotForTasks(level, plot);
        }

        // Decide next phase
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

        if (needsSeeds(level) && canPlant(role)) {
            phase = Phase.BUYING_SEEDS;
            return;
        }

        goIdle();
    }

    private void scanPlotForTasks(ServerLevel level, FarmPlot plot) {
        for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);

            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                toHarvest.add(cropPos);
            } else if (state.isAir()
                    && level.getBlockState(farmland).getBlock() instanceof FarmBlock) {
                toReplant.add(cropPos);
            }
        }
    }

    private boolean canHarvest(FarmRole role) {
        return role == FarmRole.GENERALIST
                || role == FarmRole.CROP_SPECIALIST
                || role == FarmRole.HARVESTER;
    }

    private boolean canPlant(FarmRole role) {
        return role == FarmRole.GENERALIST
                || role == FarmRole.CROP_SPECIALIST
                || role == FarmRole.PLANTER;
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
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    cropPos.getX(), cropPos.getY(), cropPos.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

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
        level.playSound(null, cropPos, SoundEvents.CROP_BREAK,
                SoundSource.BLOCKS, 1.0f, 1.0f);

        toHarvest.remove(0);

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
        }
    }

    // =========================================================================
    // Phase: WALKING TO FARMHOUSE
    // =========================================================================

    private void walkToFarmhouse() {
        if (farmhouse == null) { goIdle(); return; }

        // Carry-pose: arms-forward holding the freshest crop while
        // ferrying the harvest back to the farmhouse.
        ItemStack carried = firstHarvestStack();
        if (!carried.isEmpty()) {
            entity.getBrain().setMemory(
                    NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), carried.copy());
        }

        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.DEPOSITING;
        }
    }

    private ItemStack firstHarvestStack() {
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            if (s.is(Items.IRON_HOE) || s.is(Items.DIAMOND_HOE)) continue;
            return s;
        }
        return ItemStack.EMPTY;
    }

    // =========================================================================
    // Phase: DEPOSITING
    // =========================================================================

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing harvest");

        if (farmhouse == null) { goIdle(); return; }

        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            // Don't deposit tools
            if (stack.is(Items.IRON_HOE) || stack.is(Items.DIAMOND_HOE)) continue;

            BuildingStorageAccess.storeItem(level, farmhouse, stack);
            inv.setItem(i, ItemStack.EMPTY);
        }

        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        phase = toHarvest.isEmpty() ? Phase.ANALYZING : Phase.HARVESTING;
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
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        if (!(level.getBlockState(targetPos.below()).getBlock() instanceof FarmBlock)) {
            toReplant.remove(0);
            return;
        }

        FarmPlot plot = findPlotContaining(level, targetPos);
        if (plot == null) { toReplant.remove(0); return; }

        Block cropBlock = plot.getCropType().resolveCropBlock();
        Item  seedItem  = plot.getCropType().resolveSeedItem();
        if (cropBlock == null) { toReplant.remove(0); return; }

        boolean taken = BuildingStorageAccess.takeItem(level, farmhouse, seedItem, 1);
        if (taken) {
            level.setBlock(targetPos, cropBlock.defaultBlockState(), 3);
            entity.getLookControl().setLookAt(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, targetPos, SoundEvents.CROP_PLANTED,
                    SoundSource.BLOCKS, 1.0f, 1.0f);
        }

        toReplant.remove(0);
    }

    // =========================================================================
    // Phase: BUYING SEEDS
    // =========================================================================

    private void buySeeds(ServerLevel level) {
        entity.setCurrentActivity("Buying seeds");

        Building market = ProductionHelpers.findMarketInVillage(entity, level).orElse(null);

        if (market == null) { goIdle(); return; }

        BlockPos target = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    target.getX(), target.getY(), target.getZ(), 1.0));
            return;
        }

        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        VillageSavedData data = VillageSavedData.get(level);
        UUID villageId = entity.getAssignedVillageName()
                .flatMap(name -> data.getVillageByName(name))
                .map(v -> v.getId())
                .orElse(null);

        // Find merchant to pay (if any)
        TownspersonMob merchant = level.getEntitiesOfClass(
                TownspersonMob.class,
                market.getShape().toAABB().inflate(16),
                mob -> mob.getProfession() == Profession.MERCHANT
        ).stream().findFirst().orElse(null);

        // FIX #3: use entity.payWithBuilding and dynamic pricing, no broken .get() calls
        for (FarmPlot plot : assignedPlots) {
            int plotSize = plot.getFarmlandBlocks(level).size();
            if (plotSize == 0) continue;

            Item seedItem = plot.getCropType().resolveSeedItem();
            int currentSeeds = countSeedsInFarmhouse(level, seedItem);
            int needed = Math.max(0, plotSize - currentSeeds);
            if (needed <= 0) continue;

            long pricePerSeed = EconomicBalance.SEED_PRICES.getOrDefault(seedItem, 1L);
            CurrencyValue cost = CurrencyValue.of((long) needed * pricePerSeed);
            UUID buildingId = entity.getAssignedBuildingId().orElse(null);
            if (buildingId == null) continue;

            // Business purchase: farmhouse treasury pays, not personal wallet
            BuildingEconomy economy = data.getOrCreateBuildingEconomy(buildingId);
            if (!economy.canAfford(cost.toBronze())) continue;

            if (villageId != null) {
                var seller = VillageEconomy.findCheapestSeller(
                        level, villageId, seedItem,
                        entity.getX(), entity.getZ(),
                        level.getGameTime()).orElse(null);

                if (seller != null) {
                    long actualCost = seller.listing().getPricePerItem() * needed;
                    if (economy.canAfford(actualCost)) {
                        boolean taken = BuildingStorageAccess.takeItem(
                                level,
                                data.getBuildingById(
                                                seller.listing().getSellerBuildingId())
                                        .orElse(null),
                                seedItem, needed);
                        if (taken) {
                            // businessPay: building treasury → seller wallet + visual
                            NpcEconomy.businessPay(
                                    buildingId, seller.seller(),
                                    CurrencyValue.of(actualCost), level, data);
                            BuildingStorageAccess.storeItem(level, farmhouse,
                                    new ItemStack(seedItem, needed));
                        }
                    }
                    continue;
                }
            }

            // Fallback: spend from building treasury silently (no seller NPC found)
            economy.withdraw(cost.toBronze());
            BuildingStorageAccess.storeItem(level, farmhouse,
                    new ItemStack(seedItem, needed));
            data.setDirty();
        }

        phase = Phase.ANALYZING;
    }

    // =========================================================================
    // Helpers
    // =========================================================================



    private FarmPlot findPlotContaining(ServerLevel level, BlockPos pos) {
        for (FarmPlot plot : assignedPlots) {
            for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
                if (farmland.above().equals(pos)) return plot;
            }
        }
        return null;
    }



    private boolean needsSeeds(ServerLevel level) {
        if (farmhouse == null) return false;
        for (FarmPlot plot : assignedPlots) {
            Item seedItem = plot.getCropType().resolveSeedItem();
            int available = countSeedsInFarmhouse(level, seedItem);
            int needed = plot.getFarmlandBlocks(level).size();
            if (available < needed) {
                long pricePerSeed = VillageEconomy.getBasePrice(seedItem);
                CurrencyValue cost = CurrencyValue.of(
                        (long)(needed - available) * pricePerSeed);
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
                if (stack.is(seedItem)) total += stack.getCount();
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
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}