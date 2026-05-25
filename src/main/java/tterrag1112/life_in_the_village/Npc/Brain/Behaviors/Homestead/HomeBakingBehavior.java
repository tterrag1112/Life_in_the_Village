package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BakerProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

/**
 * Phase 6.4.5 — opportunistic homestead-skill behavior. Any NPC (not
 * just BAKER) with non-zero BAKING + a smoker (or furnace) in their
 * house can bake bread when family stock runs low.
 *
 * <h3>Conditions</h3>
 * <ul>
 *   <li>Not a child, nav available.</li>
 *   <li>BAKING ≥ 1 — seeded by {@link tterrag1112.life_in_the_village
 *       .Npc.Aging.FamilySkillSeeder} for ~8% of adults at coming-of-age,
 *       additionally accumulated via the {@code home_cooking} hobby
 *       drift (BAKING +1 per session) from 6.4.4.2.</li>
 *   <li>House has SMOKER amenity (preferred) or FURNACE (fallback).</li>
 *   <li>Family bread stock &lt; {@code BREAD_PER_FAMILY_MEMBER} × member count.</li>
 *   <li>Family wheat stock ≥ recipe input (3 wheat per loaf).</li>
 *   <li>At least one motive active:
 *     <ul>
 *       <li><b>Economic</b>: combined wallet + household pool &lt;
 *           {@link #ECONOMIC_WALLET_THRESHOLD}.</li>
 *       <li><b>Hobby</b>: current schedule phase is LEISURE AND the
 *           NPC's active hobby is {@code home_cooking}.</li>
 *     </ul></li>
 * </ul>
 *
 * <h3>Recipe</h3>
 * Reuses {@link BakerProductionBehavior#WHEAT_TO_BREAD} (3 wheat → 1
 * bread, 200t) verbatim. Single source of truth for the homestead
 * recipe; future recipe tuning lands once, applies everywhere.
 */
public class HomeBakingBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String HOBBY_HOME_COOKING = "home_cooking";
    private static final int BREAD_PER_FAMILY_MEMBER = 4;
    /** Combined personal+household wealth below which economic motive fires. */
    private static final long ECONOMIC_WALLET_THRESHOLD = 150L;
    /** Skill XP awarded per completed loaf — matches modest hobby-drift magnitude. */
    private static final int BAKING_XP_PER_BATCH = 2;

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

    public HomeBakingBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (entity.getSkills().getLevel(Skill.BAKING) < 1) return false;

        VillageSavedData data = VillageSavedData.get(level);
        Building h = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
        if (h == null) return false;

        BlockPos pos = findFirstAmenityPos(level, h, AmenityType.SMOKER);
        if (pos == null) pos = findFirstAmenityPos(level, h, AmenityType.FURNACE);
        if (pos == null) return false;

        ProductionRecipe recipe = BakerProductionBehavior.WHEAT_TO_BREAD;
        int wheatNeeded = recipe.inputs().values().iterator().next();
        int wheatStock = BuildingStorageAccess.countItem(level, h, Items.WHEAT);
        if (wheatStock < wheatNeeded) return false;

        HouseholdData household = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding).orElse(null);
        int familySize = household != null
                ? Math.max(1, household.getMemberNpcIds().size()) : 1;
        int breadThreshold = familySize * BREAD_PER_FAMILY_MEMBER;
        int breadStock = BuildingStorageAccess.countItem(level, h, Items.BREAD);
        if (breadStock >= breadThreshold) return false;

        long wallet = entity.getWallet().toBronze();
        long pool = household != null ? household.getPooledWealth() : 0L;
        boolean economicMotive = (wallet + pool) < ECONOMIC_WALLET_THRESHOLD;

        NpcHobbyPreference pref = entity.getHobbyPreference();
        DayPhase curPhase = ScheduleResolver.phaseAt(entity, level.getGameTime());
        boolean hobbyMotive = curPhase == DayPhase.LEISURE
                && pref.hasCurrent()
                && HOBBY_HOME_COOKING.equals(pref.currentHobby());

        if (!economicMotive && !hobbyMotive) return false;

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
        entity.setCurrentActivity("Baking bread");
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
        // Safety abort: nav idled for 600 ticks without arriving.
        if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickProducing(TownspersonMob entity) {
        entity.getLookControl().setLookAt(
                workstationPos.getX() + 0.5, workstationPos.getY() + 1.0,
                workstationPos.getZ() + 0.5);
        if (subTimer >= BakerProductionBehavior.WHEAT_TO_BREAD.ticks()) {
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ProductionRecipe recipe = BakerProductionBehavior.WHEAT_TO_BREAD;
        int wheatNeeded = recipe.inputs().values().iterator().next();
        if (!BuildingStorageAccess.takeItem(level, house, Items.WHEAT, wheatNeeded)) {
            // Wheat vanished mid-bake (consumed elsewhere). Abort cleanly.
            phase = Phase.DONE;
            return;
        }
        ItemStack bread = new ItemStack(Items.BREAD, recipe.outputCount());
        BuildingStorageAccess.storeWithFallback(level, house, bread,
                entity.getPersonalInventory());
        SkillXp.award(entity, Skill.BAKING, BAKING_XP_PER_BATCH, gameTime);

        LOGGER.info("[HomeBakingBehavior] {} (BAKING {}) baked {} bread; " +
                "house stock now {}, wheat remaining {}",
                entity.getNpcName(),
                entity.getSkills().getLevel(Skill.BAKING),
                recipe.outputCount(),
                BuildingStorageAccess.countItem(level, house, Items.BREAD),
                BuildingStorageAccess.countItem(level, house, Items.WHEAT));
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        // No persistent cooldown — eligibility gates (bread stock at quota,
        // motive lapsed once wallet refills, recipe cycle finished)
        // naturally prevent immediate re-fire.
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
