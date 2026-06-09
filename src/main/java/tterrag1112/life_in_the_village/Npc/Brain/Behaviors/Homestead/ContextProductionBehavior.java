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
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Production Architecture M2/R6b — the context-parameterized production primitive.
 * One {@code skill × amenity × recipe → produce → deposit} state machine, shared
 * by every production <i>context</i>: the {@link HomeProductionBehavior} (HOME)
 * and the {@code MonkProductionBehavior} (MONASTERY), with more to come.
 *
 * <p><b>The split (audit's "one primitive, thin context routers"):</b> this base
 * owns the universal skeleton — the gates (not-child, nav), the phase machine
 * (walk to the workstation → produce for the recipe's ticks → consume the
 * recipe's inputs and deposit its output → award the skill XP), and the amenity /
 * input helpers. Each context subclass implements only {@link #selectPlan}: which
 * skills it exercises, the motive, and the output destination — returning a
 * {@link Plan} (the chosen building + workstation + recipe + skill) or empty.</p>
 *
 * <p>HOME is byte-preserved: its {@code selectPlan} is the verbatim M2 selection,
 * and the phase machine here is the verbatim M2 tick logic — only the deposit
 * target is generalized from "the house" to "the plan's building" (which IS the
 * house for HOME).</p>
 */
public abstract class ContextProductionBehavior extends Behavior<TownspersonMob> {

    protected static final Logger LOGGER = LogUtils.getLogger();

    // ── Shared skeleton constants (identical across contexts; were M2's) ─────
    private static final double ARRIVAL_DIST_SQ = 4.0;
    private static final float  WALK_SPEED      = 0.7f;
    private static final int    CLOSE_ENOUGH    = 1;
    protected static final int  MAX_RUN         = 24000;

    /**
     * A selected production run: the recipe to run, at {@code workstationPos}
     * (null = no workstation, produce in place), consuming/depositing in
     * {@code building}, awarding {@code skill} XP. The context builds this in
     * {@link #selectPlan}.
     */
    public record Plan(Building building, BlockPos workstationPos,
                       ProductionRecipe recipe, Skill skill,
                       int xpPerBatch, String activityLabel) {}

    protected enum Phase { WALKING_TO_WORKSTATION, PRODUCING, DEPOSITING, DONE }

    private Phase phase;
    private Plan plan;
    private long startTick;
    private int subTimer;

    protected ContextProductionBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    /**
     * Context selection: choose what (if anything) this NPC produces right now.
     * Implementations check their craft table (skill ≥ level + amenity present +
     * inputs available + the context's motive) and return the first qualifying
     * {@link Plan}, or empty to do nothing this tick.
     */
    protected abstract Optional<Plan> selectPlan(ServerLevel level, TownspersonMob entity);

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        Optional<Plan> selected = selectPlan(level, entity);
        if (selected.isEmpty()) return false;
        this.plan = selected.get();
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
        entity.setCurrentActivity(plan.activityLabel());
        if (plan.workstationPos() != null) {
            phase = Phase.WALKING_TO_WORKSTATION;
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(plan.workstationPos(), WALK_SPEED, CLOSE_ENOUGH));
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
        BlockPos pos = plan.workstationPos();
        double distSq = entity.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
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
        BlockPos pos = plan.workstationPos();
        if (pos != null) {
            entity.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        }
        if (subTimer >= plan.recipe().ticks()) {
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ProductionRecipe recipe = plan.recipe();
        Building building = plan.building();
        // Consume all inputs — multi-input via takeItem per entry.
        for (Map.Entry<Item, Integer> e : recipe.inputs().entrySet()) {
            if (!BuildingStorageAccess.takeItem(level, building, e.getKey(), e.getValue())) {
                // An input vanished mid-craft (consumed elsewhere). Abort cleanly.
                phase = Phase.DONE;
                return;
            }
        }
        ItemStack output = producedStack(level, entity, building, recipe);
        BuildingStorageAccess.storeWithFallback(level, building, output,
                entity.getPersonalInventory());
        SkillXp.award(entity, plan.skill(), plan.xpPerBatch(), gameTime);

        LOGGER.info("[{}] {} ({} {}) made {}x {}; building stock now {}",
                getClass().getSimpleName(), entity.getNpcName(), plan.skill(),
                entity.getSkills().getLevel(plan.skill()),
                recipe.outputCount(), recipe.output(),
                BuildingStorageAccess.countItem(level, building, recipe.output()));
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        // No persistent cooldown — eligibility gates (output at quota, motive
        // lapsed, recipe cycle finished) naturally prevent immediate re-fire.
    }

    // ── Shared selection helpers (used by subclasses' selectPlan) ────────────

    /**
     * The ItemStack a completed cycle deposits — by default the recipe's plain
     * output. A context may override to produce a special stack (D3: the monk's
     * COPY_MANUSCRIPT deposits the monastery's faith scripture). HOME does not
     * override, so it stays byte-exact.
     */
    protected ItemStack producedStack(ServerLevel level, TownspersonMob entity,
                                      Building building, ProductionRecipe recipe) {
        return new ItemStack(recipe.output(), recipe.outputCount());
    }

    protected static boolean hasAllInputs(ServerLevel level, Building b, ProductionRecipe recipe) {
        for (Map.Entry<Item, Integer> e : recipe.inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, b, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** First matching block pos across the amenity types, in preference order
     *  (empty list ⇒ null = no workstation needed). Delegates to the single
     *  amenity-scan home {@link AmenityType#firstPresent}. */
    protected static BlockPos firstAmenityPos(ServerLevel level, Building b,
                                              List<AmenityType> types) {
        return AmenityType.firstPresent(level, b, types);
    }
}
