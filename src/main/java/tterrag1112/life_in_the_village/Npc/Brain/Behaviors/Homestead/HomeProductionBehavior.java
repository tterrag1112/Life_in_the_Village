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
import net.minecraft.world.level.block.Block;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Production Architecture M2 — the single general homestead-production behavior.
 * Collapses the four hand-written {@code Home{Baking,Milling,Weaving,
 * Candlemaking}Behavior} classes into one behavior driven by the {@link #TABLE}
 * of {@link HomeCraft} rows. Adding a home craft is now a table entry, not a new
 * behavior class — which is what makes the monastic crafts (R6) cheap.
 *
 * <p><b>Canonical principle (M1/M2):</b> a SKILL defines the act; the home
 * context decides which skills are exercised. For the NPC, this behavior
 * considers each row where the NPC has the skill ≥ the row's min level, the
 * house has the row's amenity (or the row needs none), there is a family need
 * (the recipe's output below the per-member threshold), inputs are available,
 * and a motive is active (economic OR the row's hobby during LEISURE); it then
 * walks to the amenity (if any), runs the row's {@link SkillRecipes} recipe, and
 * deposits to the household, awarding the skill XP.</p>
 *
 * <p><b>Selection rule (behavior-preserving):</b> the table is iterated in the
 * original brain-registration order (baking, milling, weaving, candlemaking) and
 * the first qualifying row is chosen — reproducing exactly the prior sequential
 * resolution of the four contiguous IDLE-priority-0 behaviors (first-to-pass
 * wins). The four crafts are therefore indistinguishable from M1.</p>
 */
public class HomeProductionBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Shared skeleton constants (identical across the former four) ─────────
    private static final double ARRIVAL_DIST_SQ = 4.0;
    private static final float  WALK_SPEED      = 0.7f;
    private static final int    CLOSE_ENOUGH    = 1;
    private static final int    MAX_RUN         = 24000;

    /**
     * One home craft. Inputs / output / ticks come from {@link #recipe}; the
     * family-need good is {@code recipe.output()}. {@code amenities} is the
     * preference-ordered workstation list (empty = no workstation, produce at
     * the house). {@code hobbyId} null = LEISURE alone is the non-economic
     * motive (milling has no dedicated hobby).
     */
    public record HomeCraft(Skill skill, int minLevel, List<AmenityType> amenities,
                            ProductionRecipe recipe, int perMemberThreshold,
                            long economicThreshold, String hobbyId,
                            int xpPerBatch, String activityLabel) {}

    /** The four initial rows — the exact parameters of the former behaviors. */
    private static final List<HomeCraft> TABLE = List.of(
            new HomeCraft(Skill.BAKING, 1, List.of(AmenityType.SMOKER, AmenityType.FURNACE),
                    SkillRecipes.WHEAT_TO_BREAD, 4, 150L, "home_cooking", 2, "Baking bread"),
            new HomeCraft(Skill.MILLING, 1, List.of(AmenityType.GRINDSTONE),
                    SkillRecipes.GRIND_WHEAT, 4, 150L, null, 2, "Milling flour"),
            new HomeCraft(Skill.WEAVING, 1, List.of(AmenityType.LOOM),
                    SkillRecipes.SPIN_STRING, 2, 150L, "home_weaving", 2, "Spinning wool"),
            new HomeCraft(Skill.CANDLEMAKING, 1, List.of(),
                    SkillRecipes.MAKE_TORCH, 4, 150L, "home_chandlery", 2, "Making torches"));

    private enum Phase { WALKING_TO_WORKSTATION, PRODUCING, DEPOSITING, DONE }

    private Phase phase;
    private HomeCraft craft;
    private BlockPos workstationPos;   // null = no workstation (produce at house)
    private Building house;
    private long startTick;
    private int subTimer;

    public HomeProductionBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;

        VillageSavedData data = VillageSavedData.get(level);
        Building h = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
        if (h == null) return false;
        HouseholdData household = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding).orElse(null);
        int familySize = household != null
                ? Math.max(1, household.getMemberNpcIds().size()) : 1;
        long wallet = entity.getWallet().toBronze();
        long pool = household != null ? household.getPooledWealth() : 0L;
        long combinedWealth = wallet + pool;
        DayPhase curPhase = ScheduleResolver.phaseAt(entity, level.getGameTime());
        NpcHobbyPreference pref = entity.getHobbyPreference();

        for (HomeCraft c : TABLE) {
            if (entity.getSkills().getLevel(c.skill()) < c.minLevel()) continue;

            // Workstation amenity (preference-ordered; empty list = none needed).
            BlockPos pos = null;
            if (!c.amenities().isEmpty()) {
                pos = firstAmenityPos(level, h, c.amenities());
                if (pos == null) continue;
            }

            // Inputs available (generic multi-input).
            if (!hasAllInputs(level, h, c.recipe())) continue;

            // Family need — the recipe's output below the per-member threshold.
            int threshold = familySize * c.perMemberThreshold();
            if (BuildingStorageAccess.countItem(level, h, c.recipe().output()) >= threshold) {
                continue;
            }

            // Motive — economic OR the row's hobby (or bare LEISURE when null).
            boolean economicMotive = combinedWealth < c.economicThreshold();
            boolean hobbyMotive = curPhase == DayPhase.LEISURE && (c.hobbyId() == null
                    || (pref.hasCurrent() && c.hobbyId().equals(pref.currentHobby())));
            if (!economicMotive && !hobbyMotive) continue;

            // First qualifying row wins (original sequential resolution).
            this.craft = c;
            this.house = h;
            this.workstationPos = pos;
            return true;
        }
        return false;
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
        entity.setCurrentActivity(craft.activityLabel());
        if (workstationPos != null) {
            phase = Phase.WALKING_TO_WORKSTATION;
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(workstationPos, WALK_SPEED, CLOSE_ENOUGH));
        } else {
            phase = Phase.PRODUCING;
        }
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
        if (workstationPos != null) {
            entity.getLookControl().setLookAt(
                    workstationPos.getX() + 0.5, workstationPos.getY() + 1.0,
                    workstationPos.getZ() + 0.5);
        }
        if (subTimer >= craft.recipe().ticks()) {
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ProductionRecipe recipe = craft.recipe();
        // Consume all inputs — multi-input via takeItem per entry.
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : recipe.inputs().entrySet()) {
            if (!BuildingStorageAccess.takeItem(level, house, e.getKey(), e.getValue())) {
                // An input vanished mid-craft (consumed elsewhere). Abort cleanly.
                phase = Phase.DONE;
                return;
            }
        }
        ItemStack output = new ItemStack(recipe.output(), recipe.outputCount());
        BuildingStorageAccess.storeWithFallback(level, house, output,
                entity.getPersonalInventory());
        SkillXp.award(entity, craft.skill(), craft.xpPerBatch(), gameTime);

        LOGGER.info("[HomeProductionBehavior] {} ({} {}) made {}x {}; house stock now {}",
                entity.getNpcName(), craft.skill(),
                entity.getSkills().getLevel(craft.skill()),
                recipe.outputCount(), recipe.output(),
                BuildingStorageAccess.countItem(level, house, recipe.output()));
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        // No persistent cooldown — eligibility gates (output at quota, motive
        // lapsed once wealth refills, recipe cycle finished) naturally prevent
        // immediate re-fire.
    }

    private static boolean hasAllInputs(ServerLevel level, Building h, ProductionRecipe recipe) {
        for (Map.Entry<net.minecraft.world.item.Item, Integer> e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, h, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** First matching block pos across the amenity types, in preference order. */
    private static BlockPos firstAmenityPos(ServerLevel level, Building b,
                                            List<AmenityType> types) {
        for (AmenityType type : types) {
            BlockPos pos = findFirstAmenityPos(level, b, type);
            if (pos != null) return pos;
        }
        return null;
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
