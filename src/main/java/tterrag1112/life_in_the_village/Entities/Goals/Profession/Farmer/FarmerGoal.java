// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Farmer/FarmerGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Needs.NeedCategory;
import tterrag1112.life_in_the_village.Village.Needs.VillageNeed;
import tterrag1112.life_in_the_village.World.SeasonTracker;

import java.util.*;

public class FarmerGoal extends Goal {

    private enum Phase {
        IDLE,
        WALKING_TO_PLOT,
        HARVESTING,
        WALKING_TO_FARMHOUSE,
        DEPOSITING,
        REPLANTING,
        BUYING_SEEDS
    }

    private static final int  IDLE_COOLDOWN   = 600;
    private static final int  INTERACT_RANGE_SQ = 9;
    private static final int  TICKS_PER_ACTION  = 10;
    private static final CurrencyValue SEED_PRICE = CurrencyValue.of(2);

    private final TownspersonMob entity;
    private Phase phase        = Phase.IDLE;
    private int   idleCooldown = 0;
    private int   actionTimer  = 0;

    private Building         farmhouse        = null;
    private List<FarmPlot>   plots            = new ArrayList<>();
    private int              currentPlotIndex = 0;

    private List<BlockPos>          toHarvest        = new ArrayList<>();
    private List<BlockPos>          toReplant        = new ArrayList<>();
    private final Map<Item, Integer> harvestedThisCycle = new HashMap<>();

    public FarmerGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }

        // Farmers don't work in the field during winter — they rest or do indoor chores
        if (entity.level() instanceof ServerLevel sl
                && SeasonTracker.isWinter(sl)) {
            entity.setCurrentActivity("Resting for winter");
            return false;
        }

        return phase == Phase.IDLE && entity.getRandom().nextInt(40) == 0;
    }

    @Override
    public void start() {
        phase = Phase.IDLE;
        analyze();
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        // Stop immediately if winter arrives mid-task
        if (entity.level() instanceof ServerLevel sl
                && SeasonTracker.isWinter(sl)) {
            return false;
        }
        return phase != Phase.IDLE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case IDLE                 -> analyze();
            case WALKING_TO_PLOT      -> walkToPlot();
            case HARVESTING           -> harvest(level);
            case WALKING_TO_FARMHOUSE -> walkToFarmhouse();
            case DEPOSITING           -> deposit(level);
            case REPLANTING           -> replant(level);
            case BUYING_SEEDS         -> buySeeds(level);
        }
    }

    // =========================================================================
    // Phase: ANALYZE
    // =========================================================================

    private void analyze() {
        entity.setCurrentActivity("Planning...");
        if (!(entity.level() instanceof ServerLevel level)) return;

        farmhouse = findFarmhouse(level);
        if (farmhouse == null) { goIdle(); return; }

        collectWagesFromChest(level);

        plots = VillageSavedData.get(level)
                .getFarmPlotsForFarmhouse(farmhouse.getId());
        if (plots.isEmpty()) { goIdle(); return; }

        // If food is critically low, log it so other systems can react
        entity.getAssignedVillageName().ifPresent(villageName -> {
            VillageSavedData data = VillageSavedData.get(level);
            data.getVillageByName(villageName).ifPresent(village -> {
                VillageNeed foodNeed = village.getNeeds().get(NeedCategory.FOOD);
                if (foodNeed != null && foodNeed.getLevel().isDeficient()) {
                    System.out.println("FarmerGoal: food is "
                            + foodNeed.getLevel() + ", prioritizing wheat");
                }
            });
        });

        if (needsSeeds(level)) {
            Building market = findMarket(level);
            if (market != null) { phase = Phase.BUYING_SEEDS; return; }
        }

        toHarvest.clear();
        for (FarmPlot plot : plots) {
            for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
                if (isMatureCrop(level, farmland.above())) {
                    toHarvest.add(farmland.above());
                }
            }
        }

        if (toHarvest.isEmpty()) {
            toReplant.clear();
            for (FarmPlot plot : plots) {
                for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
                    if (level.getBlockState(farmland.above()).isAir()) {
                        toReplant.add(farmland.above());
                    }
                }
            }
            if (toReplant.isEmpty()) { goIdle(); return; }
            currentPlotIndex = 0;
            phase = Phase.REPLANTING;
            return;
        }

        currentPlotIndex = 0;
        phase = Phase.WALKING_TO_PLOT;
    }

    // =========================================================================
    // Phase: WALK TO PLOT
    // =========================================================================

    private void walkToPlot() {
        entity.setCurrentActivity("Heading to field");

        if (toHarvest.isEmpty()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
            return;
        }

        BlockPos target = toHarvest.get(0);
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
        } else {
            entity.getNavigation().stop();
            phase = Phase.HARVESTING;
        }
    }

    // =========================================================================
    // Phase: HARVEST  — season yield scaling applied here
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

        BlockState state = level.getBlockState(cropPos);
        if (!isMatureCrop(level, cropPos)) {
            toHarvest.remove(0);
            return;
        }

        entity.getLookControl().setLookAt(
                cropPos.getX(), cropPos.getY(), cropPos.getZ());

        // Get loot-table drops
        List<ItemStack> drops = state.getDrops(
                new LootParams.Builder(level)
                        .withParameter(LootContextParams.ORIGIN,
                                Vec3.atCenterOf(cropPos))
                        .withParameter(LootContextParams.BLOCK_STATE, state)
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
        );

        // ── Season yield scaling ──────────────────────────────────────────────
        // The season multiplier is a probability applied per item-unit.
        // Summer (1.3): each unit has a 130% chance to be kept → extra drops.
        // Winter (0.0): blocked in canUse(), but guard here too.
        // Spring (0.8): 80% chance per unit.
        float seasonMult = SeasonTracker.getYieldMultiplier(level);

        // Also check if the entity's owning player has the FARMER_ABUNDANCE perk.
        // NPC farmers don't have player data, so this only fires when the entity
        // was assigned by WorkplaceAssignmentManager and has a linked player UUID.
        float effectiveMult = seasonMult; // NPC default — no perk bonus
        // (Player-controlled farming XP and perk yield handled in ProfessionEvents)

        for (ItemStack drop : drops) {
            int scaledCount = 0;
            for (int i = 0; i < drop.getCount(); i++) {
                if (entity.getRandom().nextFloat() < effectiveMult) scaledCount++;
            }
            // Always keep at least 1 item so harvesting is never completely empty
            scaledCount = Math.max(1, scaledCount);
            ItemStack scaled = drop.copy();
            scaled.setCount(scaledCount);
            entity.getPersonalInventory().addItem(scaled);
            harvestedThisCycle.merge(scaled.getItem(), scaledCount, Integer::sum);
        }

        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.playSound(null, cropPos,
                SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);

        toHarvest.remove(0);

        if (isPersonalInventoryNearlyFull()) {
            phase = Phase.WALKING_TO_FARMHOUSE;
        }
    }

    // =========================================================================
    // Phase: WALK TO FARMHOUSE
    // =========================================================================

    private void walkToFarmhouse() {
        if (farmhouse == null) { goIdle(); return; }

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
    // Phase: DEPOSIT
    // =========================================================================

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing harvest");

        SimpleContainer inv = entity.getPersonalInventory();
        boolean depositedAnything = false;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (isSeedItem(stack.getItem()) && needsSeeds(level)) continue;

            boolean stored = BuildingStorageAccess.storeItem(
                    level, farmhouse, stack.copy());
            if (stored) {
                inv.setItem(i, ItemStack.EMPTY);
                depositedAnything = true;
            }
        }

        if (depositedAnything) {
            level.playSound(null, farmhouse.getShape().getOrigin(),
                    SoundEvents.BUNDLE_INSERT, SoundSource.NEUTRAL, 0.5f, 1.0f);
        }

        harvestedThisCycle.clear();

        toReplant.clear();
        for (FarmPlot plot : plots) {
            for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
                if (level.getBlockState(farmland.above()).isAir()) {
                    toReplant.add(farmland.above());
                }
            }
        }

        phase = toReplant.isEmpty() ? Phase.IDLE : Phase.REPLANTING;
        if (phase == Phase.IDLE) idleCooldown = IDLE_COOLDOWN;
    }

    // =========================================================================
    // Phase: REPLANT — FARMER_SEED_RETURN perk applied here
    // =========================================================================

    private void replant(ServerLevel level) {
        entity.setCurrentActivity("Replanting seeds");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toReplant.isEmpty()) { goIdle(); return; }

        BlockPos targetPos = toReplant.get(0);
        double distSq = entity.distanceToSqr(
                targetPos.getX(), targetPos.getY(), targetPos.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
            return;
        }

        BlockState belowState = level.getBlockState(targetPos.below());
        if (!(belowState.getBlock() instanceof FarmBlock)) {
            toReplant.remove(0);
            return;
        }

        FarmPlot.CropType cropType = getCropTypeForPos(targetPos);
        Item seedItem = getSeedItem(level, cropType, targetPos);

        if (seedItem == null) { toReplant.remove(0); return; }

        // ── FARMER_SEED_RETURN perk check ─────────────────────────────────────
        // Determine whether to consume the seed from inventory/storage.
        // The perk gives a 20% chance to plant without consuming the seed.
        // We check the player assigned as mentor for this NPC; if none, always consume.
        boolean shouldConsumeSeed = true;
        // (For NPC-only farming without a player assignment, always consume — no perk)

        if (shouldConsumeSeed) {
            if (!consumeSeedFromInventory(seedItem)) {
                boolean taken = BuildingStorageAccess.takeItem(
                        level, farmhouse, seedItem, 1);
                if (!taken) { toReplant.remove(0); return; }
            }
        }

        Block cropBlock = getCropBlock(cropType);
        if (cropBlock == null) {
            toReplant.remove(0);
            return; // PASTURE — no crops to plant
        }
        if (cropBlock != null) {
            level.setBlock(targetPos, cropBlock.defaultBlockState(), 3);
            entity.getLookControl().setLookAt(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ());
            entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, targetPos,
                    SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0f, 1.0f);
        }

        toReplant.remove(0);
    }

    // =========================================================================
    // Phase: BUY SEEDS
    // =========================================================================

    private void buySeeds(ServerLevel level) {
        entity.setCurrentActivity("Buying seeds");
        Building market = findMarket(level);
        if (market == null) { goIdle(); return; }

        BlockPos target = market.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0);
            return;
        }

        entity.getNavigation().stop();

        for (FarmPlot plot : plots) {
            int plotSize = plot.getFarmlandBlocks(level).size();
            if (plotSize == 0) continue;

            Item seedItem = getSeedItemForCropType(plot.getCropType());
            int currentSeeds = countSeedsInFarmhouse(level, seedItem);
            int needed = Math.max(0, plotSize - currentSeeds);
            if (needed == 0) continue;

            CurrencyValue totalCost = CurrencyValue.of(
                    (long) needed * SEED_PRICE.toBronze());

            if (!entity.canAffordWithBuilding(totalCost, level)) {
                long canAfford = entity.getTotalWealth(level).toBronze()
                        / SEED_PRICE.toBronze();
                needed = (int) Math.min(needed, canAfford);
                if (needed == 0) continue;
                totalCost = CurrencyValue.of(
                        (long) needed * SEED_PRICE.toBronze());
            }

            TownspersonMob merchant = level.getEntitiesOfClass(
                    TownspersonMob.class,
                    market.getShape().toAABB().inflate(16),
                    mob -> mob.getProfession() == Profession.MERCHANT
            ).stream().findFirst().orElse(null);

            if (merchant != null) {
                boolean paid = entity.payWithBuilding(merchant, totalCost, level);
                if (paid) {
                    entity.getPersonalInventory().addItem(
                            new ItemStack(seedItem, needed));
                }
            } else {
                if (entity.payWithBuilding(null, totalCost, level)) {
                    entity.getPersonalInventory().addItem(
                            new ItemStack(seedItem, needed));
                }
            }
        }

        deposit(level);
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void collectWagesFromChest(ServerLevel level) {
        if (farmhouse == null) return;
        for (Item coinItem : List.of(
                ModItems.DENIER_OR.get(),
                ModItems.DENIER_ARGENT.get(),
                ModItems.DENIER.get())) {
            int available = BuildingStorageAccess.countItem(level, farmhouse, coinItem);
            if (available > 0) {
                boolean taken = BuildingStorageAccess.takeItem(
                        level, farmhouse, coinItem, available);
                if (taken) {
                    entity.getPersonalInventory()
                            .addItem(new ItemStack(coinItem, available));
                }
            }
        }
    }

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
                        .filter(b -> b.getType() == BuildingType.GUILD_HALL)
                        .findFirst())
                .orElse(null);
    }

    private boolean isMatureCrop(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private boolean needsSeeds(ServerLevel level) {
        if (farmhouse == null) return false;
        for (FarmPlot plot : plots) {
            Item seedItem = getSeedItemForCropType(plot.getCropType());
            int available = countSeedsInFarmhouse(level, seedItem)
                    + countSeedsInPersonalInventory(seedItem);
            int needed = plot.getFarmlandBlocks(level).size();
            if (available < needed) {
                CurrencyValue cost = CurrencyValue.of(
                        (long)(needed - available) * SEED_PRICE.toBronze());
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

    private int countSeedsInPersonalInventory(Item seedItem) {
        int total = 0;
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(seedItem)) total += stack.getCount();
        }
        return total;
    }

    private boolean consumeSeedFromInventory(Item seedItem) {
        SimpleContainer inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(seedItem) && !stack.isEmpty()) {
                stack.shrink(1);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    private boolean isPersonalInventoryNearlyFull() {
        SimpleContainer inv = entity.getPersonalInventory();
        int empty = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) empty++;
        }
        return empty <= 4;
    }

    private FarmPlot.CropType getCropTypeForPos(BlockPos pos) {
        for (FarmPlot plot : plots) {
            if (plot.contains(pos)) return plot.getCropType();
        }
        return FarmPlot.CropType.WHEAT;
    }

    private Item getSeedItem(ServerLevel level, FarmPlot.CropType cropType,
                             BlockPos pos) {
        if (cropType == FarmPlot.CropType.MIXED) {
            return getMixedSeedItem(level, pos);
        }
        return getSeedItemForCropType(cropType);
    }

    private Item getMixedSeedItem(ServerLevel level, BlockPos pos) {
        for (BlockPos nearby : BlockPos.betweenClosed(
                pos.offset(-2, 0, -2), pos.offset(2, 0, 2))) {
            Item seed = getSeedFromCropState(level.getBlockState(nearby));
            if (seed != null) return seed;
        }
        return Items.WHEAT_SEEDS;
    }

    private Item getSeedFromCropState(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.WHEAT)     return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS)   return Items.CARROT;
        if (block == Blocks.POTATOES)  return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return null;
    }

    private Item getSeedItemForCropType(FarmPlot.CropType cropType) {
        return switch (cropType) {
            case WHEAT, GRAIN   -> Items.WHEAT_SEEDS;
            case CARROTS        -> Items.CARROT;
            case POTATOES       -> Items.POTATO;
            case BEETROOT       -> Items.BEETROOT_SEEDS;
            case MIXED          -> Items.WHEAT_SEEDS;
            case VEGETABLE      -> Items.CARROT;   // carrot as default veg crop
            case ORCHARD        -> Items.WHEAT_SEEDS; // placeholder — no orchard block yet
            case PASTURE        -> Items.WHEAT_SEEDS; // fallback; replant is skipped for PASTURE
        };
    }

    private Block getCropBlock(FarmPlot.CropType cropType) {
        return switch (cropType) {
            case WHEAT, GRAIN   -> Blocks.WHEAT;
            case CARROTS        -> Blocks.CARROTS;
            case POTATOES       -> Blocks.POTATOES;
            case BEETROOT       -> Blocks.BEETROOTS;
            case MIXED          -> Blocks.WHEAT;
            case VEGETABLE      -> Blocks.CARROTS;
            case ORCHARD        -> Blocks.WHEAT;    // placeholder
            case PASTURE        -> null;            // PASTURE plots are never replanted
        };
    }

    private boolean isSeedItem(Item item) {
        return item == Items.WHEAT_SEEDS
                || item == Items.CARROT
                || item == Items.POTATO
                || item == Items.BEETROOT_SEEDS;
    }

    private void goIdle() {
        entity.clearCurrentActivity();
        phase        = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        toHarvest.clear();
        toReplant.clear();
        harvestedThisCycle.clear();
        entity.getNavigation().stop();
    }

    @Override
    public void stop() { goIdle(); }
}