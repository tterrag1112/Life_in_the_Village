package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Items.ModItems;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.MillerProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

/**
 * Phase 6.4.5 — opportunistic homestead-skill behavior, symmetric to
 * {@link HomeBakingBehavior}. Any NPC with non-zero MILLING + a
 * grindstone in their house can mill wheat into flour for the family.
 *
 * <p>Grindstones are rarer in vanilla house templates than smokers
 * or furnaces, so this behavior is naturally less frequent than
 * HomeBakingBehavior — by design. Most flour-needing families buy
 * from MILLER; the homestead path is for the unusual case of a house
 * that happens to have a grindstone AND a family member who knows
 * how to use it.</p>
 *
 * <p>Recipe reused from {@link MillerProductionBehavior#GRIND_WHEAT}
 * (2 wheat → 3 flour, 80t). Single source of truth.</p>
 */
public class HomeMillingBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Flour count below which family-economic motive could fire. */
    private static final int FLOUR_PER_FAMILY_MEMBER = 4;
    private static final long ECONOMIC_WALLET_THRESHOLD = 150L;
    /** Skill XP awarded per completed grind. */
    private static final int MILLING_XP_PER_BATCH = 2;

    private static final double ARRIVAL_DIST_SQ = 4.0;
    private static final float WALK_SPEED = 0.7f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = 24000;

    private enum Phase { WALKING_TO_WORKSTATION, PRODUCING, DEPOSITING, DONE }

    private Phase phase;
    private BlockPos workstationPos;
    private Building house;
    private long startTick;
    private int subTimer;

    public HomeMillingBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (entity.getSkills().getLevel(Skill.MILLING) < 1) return false;

        VillageSavedData data = VillageSavedData.get(level);
        Building h = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
        if (h == null) return false;

        // Grindstone only — no furnace fallback (it's the specific tool).
        BlockPos pos = findFirstAmenityPos(level, h, AmenityType.GRINDSTONE);
        if (pos == null) return false;

        ProductionRecipe recipe = SkillRecipes.GRIND_WHEAT;
        int wheatNeeded = recipe.inputs().values().iterator().next();
        int wheatStock = BuildingStorageAccess.countItem(level, h, Items.WHEAT);
        if (wheatStock < wheatNeeded) return false;

        Item flourItem = ModItems.WHEAT_FLOUR.get();
        HouseholdData household = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding).orElse(null);
        int familySize = household != null
                ? Math.max(1, household.getMemberNpcIds().size()) : 1;
        int flourThreshold = familySize * FLOUR_PER_FAMILY_MEMBER;
        int flourStock = BuildingStorageAccess.countItem(level, h, flourItem);
        if (flourStock >= flourThreshold) return false;

        long wallet = entity.getWallet().toBronze();
        long pool = household != null ? household.getPooledWealth() : 0L;
        boolean economicMotive = (wallet + pool) < ECONOMIC_WALLET_THRESHOLD;

        // No specific "home_milling" hobby exists today. LEISURE-phase
        // grinding could be added later as a content extension; for v1
        // the hobby motive is just the LEISURE phase check, which is a
        // very mild bias (most LEISURE-time NPCs do other hobbies).
        DayPhase curPhase = ScheduleResolver.phaseAt(entity, level.getGameTime());
        boolean leisureMotive = curPhase == DayPhase.LEISURE;

        if (!economicMotive && !leisureMotive) return false;

        this.house = h;
        this.workstationPos = pos;
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != null && phase != Phase.DONE
                && (gameTime - startTick) < MAX_RUN;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        startTick = gameTime;
        subTimer = 0;
        phase = Phase.WALKING_TO_WORKSTATION;
        entity.setCurrentActivity("Milling flour");
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(workstationPos, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case WALKING_TO_WORKSTATION -> tickWalking(entity);
            case PRODUCING              -> tickProducing(entity);
            case DEPOSITING             -> tickDepositing(level, entity, gameTime);
            case DONE                   -> { /* canStillUse short-circuits */ }
        }
    }

    private void tickWalking(TownspersonMob entity) {
        double distSq = entity.distanceToSqr(
                workstationPos.getX() + 0.5, workstationPos.getY(), workstationPos.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.PRODUCING;
            subTimer = 0;
            return;
        }
        if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickProducing(TownspersonMob entity) {
        entity.getLookControl().setLookAt(
                workstationPos.getX() + 0.5, workstationPos.getY() + 1.0,
                workstationPos.getZ() + 0.5);
        if (subTimer >= SkillRecipes.GRIND_WHEAT.ticks()) {
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ProductionRecipe recipe = SkillRecipes.GRIND_WHEAT;
        int wheatNeeded = recipe.inputs().values().iterator().next();
        if (!BuildingStorageAccess.takeItem(level, house, Items.WHEAT, wheatNeeded)) {
            phase = Phase.DONE;
            return;
        }
        Item flourItem = ModItems.WHEAT_FLOUR.get();
        ItemStack flour = new ItemStack(flourItem, recipe.outputCount());
        BuildingStorageAccess.storeWithFallback(level, house, flour,
                entity.getPersonalInventory());
        SkillXp.award(entity, Skill.MILLING, MILLING_XP_PER_BATCH, gameTime);

        LOGGER.info("[HomeMillingBehavior] {} (MILLING {}) ground {} flour; " +
                "house stock now {}, wheat remaining {}",
                entity.getNpcName(),
                entity.getSkills().getLevel(Skill.MILLING),
                recipe.outputCount(),
                BuildingStorageAccess.countItem(level, house, flourItem),
                BuildingStorageAccess.countItem(level, house, Items.WHEAT));
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
    }

    private static BlockPos findFirstAmenityPos(ServerLevel level, Building b, AmenityType type) {
        BlockPos min = b.getShape().getMin();
        BlockPos max = b.getShape().getMax();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            Block block = level.getBlockState(pos).getBlock();
            if (type.matches(block)) return pos.immutable();
        }
        return null;
    }
}
