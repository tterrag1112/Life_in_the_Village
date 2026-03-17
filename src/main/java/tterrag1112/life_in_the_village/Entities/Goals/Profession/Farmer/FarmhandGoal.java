package tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
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
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.JobPosting;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class FarmhandGoal extends Goal {

    private enum Phase {
        IDLE, HARVESTING, DEPOSITING, REPLANTING
    }

    private static final int IDLE_COOLDOWN = 400;
    private static final int TICKS_PER_ACTION = 10;
    private static final int INTERACT_RANGE_SQ = 9;

    private final TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private int idleCooldown = 0;
    private int actionTimer = 0;

    private FarmPlot assignedPlot = null;
    private Building farmhouse = null;
    private List<BlockPos> toHarvest = new ArrayList<>();
    private List<BlockPos> toReplant = new ArrayList<>();

    public FarmhandGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;

        if (idleCooldown > 0) { idleCooldown--; return false; }
        return phase == Phase.IDLE && entity.getRandom().nextInt(40) == 0;
    }

    @Override
    public void start() { analyze(); }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;

        return phase != Phase.IDLE; }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        switch (phase) {
            case IDLE      -> analyze();
            case HARVESTING -> harvest(level);
            case DEPOSITING -> deposit(level);
            case REPLANTING -> replant(level);
        }
    }

    private void analyze() {
        entity.setCurrentActivity("Planning...");
        if (!(entity.level() instanceof ServerLevel level)) return;
        VillageSavedData data = VillageSavedData.get(level);

        // Find assigned plot
        assignedPlot = entity.getAssignedPlotId()
                .flatMap(data::getFarmPlotById)
                .orElse(null);

        if (assignedPlot == null) { goIdle(); return; }

        // Find farmhouse for depositing
        farmhouse = entity.getAssignedBuildingId()
                .flatMap(data::getBuildingById)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);

        if (farmhouse == null) { goIdle(); return; }

        // Scan assigned plot for mature crops
        toHarvest.clear();
        for (BlockPos farmland : assignedPlot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                toHarvest.add(cropPos);
            }
        }

        if (!toHarvest.isEmpty()) {
            entity.setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(Items.IRON_HOE));
            phase = Phase.HARVESTING;
            return;
        }

        // Check for empty farmland needing replanting
        toReplant.clear();
        for (BlockPos farmland : assignedPlot.getFarmlandBlocks(level)) {
            if (level.getBlockState(farmland.above()).isAir()) {
                toReplant.add(farmland.above());
            }
        }

        if (!toReplant.isEmpty()) {
            phase = Phase.REPLANTING;
            return;
        }

        goIdle();
    }

    private void harvest(ServerLevel level) {
        entity.setCurrentActivity("Harvesting crops");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toHarvest.isEmpty()) {
            phase = Phase.DEPOSITING;
            return;
        }

        BlockPos cropPos = toHarvest.get(0);
        double distSq = entity.distanceToSqr(
                cropPos.getX(), cropPos.getY(), cropPos.getZ()
        );

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    cropPos.getX(), cropPos.getY(), cropPos.getZ(), 1.0
            );
            return;
        }

        BlockState state = level.getBlockState(cropPos);
        if (!(state.getBlock() instanceof CropBlock crop) || !crop.isMaxAge(state)) {
            toHarvest.remove(0);
            return;
        }

        entity.getLookControl().setLookAt(
                cropPos.getX(), cropPos.getY(), cropPos.getZ()
        );

        List<ItemStack> drops = state.getDrops(
                new LootParams.Builder(level)
                        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(cropPos))
                        .withParameter(LootContextParams.BLOCK_STATE, state)
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
        );

        for (ItemStack drop : drops) {
            entity.getPersonalInventory().addItem(drop.copy());
        }

        level.setBlock(cropPos, Blocks.AIR.defaultBlockState(), 3);
        entity.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cropPos,
                SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f
        );

        toHarvest.remove(0);
    }

    private void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing harvest");

        if (farmhouse == null) { goIdle(); return; }

        BlockPos target = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ()
        );

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(), 1.0
            );
            return;
        }

        entity.getNavigation().stop();
        SimpleContainer inv = entity.getPersonalInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            boolean stored = BuildingStorageAccess.storeItem(level, farmhouse, stack.copy());
            if (stored) inv.setItem(i, ItemStack.EMPTY);
        }

        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        // Collect daily wage from farmhouse storage after depositing
        collectWage(level);

        // Move to replanting
        toReplant.clear();
        for (BlockPos farmland : assignedPlot.getFarmlandBlocks(level)) {
            if (level.getBlockState(farmland.above()).isAir()) {
                toReplant.add(farmland.above());
            }
        }
        phase = toReplant.isEmpty() ? Phase.IDLE : Phase.REPLANTING;
        if (phase == Phase.IDLE) idleCooldown = IDLE_COOLDOWN;
    }

    private void collectWage(ServerLevel level) {
        // Find the farmer who hired this farmhand and get wage from their inventory
        level.getEntitiesOfClass(TownspersonMob.class,
                farmhouse.getShape().toAABB().inflate(64),
                mob -> mob.getProfession() == Profession.FARMER
                        && farmhouse.getId().equals(mob.getAssignedBuildingId().orElse(null))
        ).stream().findFirst().ifPresent(farmer -> {
            CurrencyValue wage = CurrencyValue.of(JobPosting.DAILY_WAGE_BRONZE_DEFAULT);
            if (farmer.canAfford(wage)) {
                farmer.pay(entity, wage);
                System.out.println("Farmhand collected wage: " + wage);
            } else {
                System.out.println("Farmer can't afford wage for farmhand");
            }
        });
    }

    private void replant(ServerLevel level) {
        entity.setCurrentActivity("Replanting seeds");

        actionTimer++;
        if (actionTimer < TICKS_PER_ACTION) return;
        actionTimer = 0;

        if (toReplant.isEmpty()) { goIdle(); return; }

        BlockPos targetPos = toReplant.get(0);
        double distSq = entity.distanceToSqr(
                targetPos.getX(), targetPos.getY(), targetPos.getZ()
        );

        if (distSq > INTERACT_RANGE_SQ) {
            entity.getNavigation().moveTo(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0
            );
            return;
        }

        if (!(level.getBlockState(targetPos.below()).getBlock() instanceof FarmBlock)) {
            toReplant.remove(0);
            return;
        }

        // Get seed from farmhouse storage
        Block cropBlock = getCropBlockForPlot();
        var seedItem = getSeedItemForPlot();

        boolean taken = BuildingStorageAccess.takeItem(
                level, farmhouse, seedItem, 1
        );

        if (taken) {
            level.setBlock(targetPos, cropBlock.defaultBlockState(), 3);
            entity.getLookControl().setLookAt(
                    targetPos.getX(), targetPos.getY(), targetPos.getZ()
            );
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, targetPos,
                    SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0f, 1.0f
            );
        }

        toReplant.remove(0);
    }

    private Block getCropBlockForPlot() {
        if (assignedPlot == null) return Blocks.WHEAT;
        return switch (assignedPlot.getCropType()) {
            case WHEAT    -> Blocks.WHEAT;
            case CARROTS  -> Blocks.CARROTS;
            case POTATOES -> Blocks.POTATOES;
            case BEETROOT -> Blocks.BEETROOTS;
            case MIXED    -> Blocks.WHEAT;
        };
    }

    private net.minecraft.world.item.Item getSeedItemForPlot() {
        if (assignedPlot == null) return Items.WHEAT_SEEDS;
        return switch (assignedPlot.getCropType()) {
            case WHEAT    -> Items.WHEAT_SEEDS;
            case CARROTS  -> Items.CARROT;
            case POTATOES -> Items.POTATO;
            case BEETROOT -> Items.BEETROOT_SEEDS;
            case MIXED    -> Items.WHEAT_SEEDS;
        };
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
    public void stop() { goIdle(); }
}
