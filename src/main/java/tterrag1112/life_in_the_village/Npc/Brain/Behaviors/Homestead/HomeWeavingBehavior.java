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
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.WeaverProductionBehavior;
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
 * Phase 6.6.6 — opportunistic homestead-skill behavior, sibling to
 * HomeBakingBehavior. Any NPC (not just WEAVER) with non-zero WEAVING
 * + a loom in their house can spin string into white wool when family
 * stock runs low.
 *
 * <h3>Conditions</h3>
 * <ul>
 *   <li>Not a child, nav available.</li>
 *   <li>WEAVING ≥ 1.</li>
 *   <li>House has LOOM amenity (no fallback — loom is the loom).</li>
 *   <li>Family wool stock &lt; {@code WOOL_PER_FAMILY_MEMBER} × member count.</li>
 *   <li>Family string stock ≥ recipe input (4 string per wool unit).</li>
 *   <li>Economic OR hobby motive active.</li>
 * </ul>
 *
 * <h3>Recipe</h3>
 * Reuses {@link WeaverProductionBehavior#SPIN_STRING} (4 string → 1
 * white wool, 60t) verbatim. Carpet weaving stays profession-exclusive
 * — homestead-tier handles the spinning step only.
 */
public class HomeWeavingBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String HOBBY_HOME_WEAVING = "home_weaving";
    /** White wool count below which family-economic motive can fire.
     *  Lower than bread (4) since wool is a stocking material, not food. */
    private static final int WOOL_PER_FAMILY_MEMBER = 2;
    private static final long ECONOMIC_WALLET_THRESHOLD = 150L;
    private static final int WEAVING_XP_PER_BATCH = 2;

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

    public HomeWeavingBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (entity.getSkills().getLevel(Skill.WEAVING) < 1) return false;

        VillageSavedData data = VillageSavedData.get(level);
        Building h = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
        if (h == null) return false;

        // LOOM-only — no fallback workstation. Looms are specialty
        // (most houses don't have one); behavior is dormant for those.
        BlockPos pos = findFirstAmenityPos(level, h, AmenityType.LOOM);
        if (pos == null) return false;

        ProductionRecipe recipe = WeaverProductionBehavior.SPIN_STRING;
        int stringNeeded = recipe.inputs().values().iterator().next();
        int stringStock = BuildingStorageAccess.countItem(level, h, Items.STRING);
        if (stringStock < stringNeeded) return false;

        HouseholdData household = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding).orElse(null);
        int familySize = household != null
                ? Math.max(1, household.getMemberNpcIds().size()) : 1;
        int woolThreshold = familySize * WOOL_PER_FAMILY_MEMBER;
        int woolStock = BuildingStorageAccess.countItem(level, h, Items.WHITE_WOOL);
        if (woolStock >= woolThreshold) return false;

        long wallet = entity.getWallet().toBronze();
        long pool = household != null ? household.getPooledWealth() : 0L;
        boolean economicMotive = (wallet + pool) < ECONOMIC_WALLET_THRESHOLD;

        NpcHobbyPreference pref = entity.getHobbyPreference();
        DayPhase curPhase = ScheduleResolver.phaseAt(entity, level.getGameTime());
        boolean hobbyMotive = curPhase == DayPhase.LEISURE
                && pref.hasCurrent()
                && HOBBY_HOME_WEAVING.equals(pref.currentHobby());

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
        entity.setCurrentActivity("Spinning wool");
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
        if (subTimer >= WeaverProductionBehavior.SPIN_STRING.ticks()) {
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ProductionRecipe recipe = WeaverProductionBehavior.SPIN_STRING;
        int stringNeeded = recipe.inputs().values().iterator().next();
        if (!BuildingStorageAccess.takeItem(level, house, Items.STRING, stringNeeded)) {
            phase = Phase.DONE;
            return;
        }
        ItemStack wool = new ItemStack(Items.WHITE_WOOL, recipe.outputCount());
        BuildingStorageAccess.storeWithFallback(level, house, wool,
                entity.getPersonalInventory());
        SkillXp.award(entity, Skill.WEAVING, WEAVING_XP_PER_BATCH, gameTime);

        LOGGER.info("[HomeWeavingBehavior] {} (WEAVING {}) spun {} wool; " +
                "house stock now {}, string remaining {}",
                entity.getNpcName(),
                entity.getSkills().getLevel(Skill.WEAVING),
                recipe.outputCount(),
                BuildingStorageAccess.countItem(level, house, Items.WHITE_WOOL),
                BuildingStorageAccess.countItem(level, house, Items.STRING));
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
