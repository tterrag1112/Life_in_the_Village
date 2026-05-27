package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions;
import tterrag1112.life_in_the_village.Village.Roster.BuildingRoster;
import tterrag1112.life_in_the_village.Village.Roster.PastureRotation;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.List;

/**
 * Phase 6.7.2 — opportunistic shepherd-tier work for FARMERs whose
 * {@link FarmRole} pinned to {@link FarmRole#SHEPHERD} (via locked
 * FARMER_SHEPHERD specialization, see 6.7.1.7). Walks to the bound
 * pasture pen, performs a physical shear cycle on a real
 * {@link Sheep} entity, deposits color-correct wool into farmhouse
 * storage, and rotates the pen binding when grass quality drops.
 *
 * <h3>Physical-shear retrofit (Phase 6.7.2.8)</h3>
 * <p>The original v1 used abstract WHITE_WOOL deposits. This pass
 * upgrades to real entity interaction:</p>
 * <ul>
 *   <li>Targets a real {@link Sheep} entity in pen bounds, filtered
 *       on {@link Sheep#readyForShearing}.</li>
 *   <li>Reads {@link Sheep#getColor} to build a color-correct wool
 *       stack — black sheep produce BLACK_WOOL, etc.</li>
 *   <li>Marks the sheep via {@link Sheep#setSheared(boolean)} so the
 *       wool model disappears and the vanilla regrow timer applies
 *       (eats grass over time, then re-grows wool).</li>
 *   <li>Damages the SHEPHERD's shears via the canonical
 *       {@link ToolUseSupport#useToolFromInventory} helper — durability
 *       decrements per action and the slot clears when the tool breaks.</li>
 * </ul>
 *
 * <p>The vanilla {@link Sheep#shear(ServerLevel, SoundSource, ItemStack)}
 * method is deliberately NOT called — it routes drops through a loot
 * table and spawns them as world ItemEntities, requiring a pickup
 * phase. The direct-inventory approach keeps the gameplay loop tight
 * (shear → wool in NPC inventory → deposit at farmhouse) without
 * losing color fidelity or visual feedback (setSheared + SHEEP_SHEAR
 * sound).</p>
 *
 * <h3>Mutual exclusion with FarmerBehavior</h3>
 * <p>Both behaviors live in WORK priority 0 for FARMER. Registration
 * order puts ShepherdBehavior first so Brain selects it when its
 * gates pass; FarmerBehavior also explicitly defers via
 * {@link #hasActionableWork} so the SHEPHERD doesn't redundantly
 * enter generic-FARMER phases while shepherd work is pending.</p>
 */
public class ShepherdBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float WALK_SPEED = 0.65f;
    private static final int CLOSE_ENOUGH_PEN = 3;
    private static final int CLOSE_ENOUGH_SHEEP = 1;
    private static final double ARRIVAL_DIST_SQ_PEN = 16.0;
    private static final double ARRIVAL_DIST_SQ_SHEEP = 4.0;
    private static final int MAX_RUN = 6000;
    private static final int SHEAR_TICKS = 30;
    private static final int SHEARING_XP_PER_ACTION = 2;
    /** Grass-quality threshold at or below which the current pen is
     *  considered exhausted; SHEPHERD triggers a rotation lookup. */
    private static final float ROTATION_QUALITY_THRESHOLD = 0.4f;
    /** Wool stack size per shearing action — matches vanilla's 1-3
     *  shear-drop range, biased mid-range. */
    private static final int WOOL_PER_SHEAR = 2;
    /** Buffer added to the pen radius when scanning for shearable
     *  sheep — sheep wander a few blocks outside fenced bounds. */
    private static final int SHEEP_SEARCH_PADDING = 4;

    private enum Phase {
        WALKING_TO_PEN,
        APPROACHING_SHEEP,
        SHEARING,
        DEPOSITING,
        DONE
    }

    private Phase phase;
    private BlockPos penAnchor;
    private Building farmhouse;
    private BuildingRoster sheepRoster;
    private FarmPlot activePen;
    private Sheep targetSheep;
    private DyeColor shearedColor;
    private long startTick;
    private int subTimer;

    public ShepherdBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    /**
     * Static probe for FarmerBehavior's defer guard. Returns true when
     * this NPC has actionable shepherd work right now — pinned to the
     * SHEPHERD role, FARMER profession, has a sheep roster, and has
     * shears in inventory. Whether a physically-shearable sheep exists
     * is NOT checked here (avoiding the AABB scan on every FARMER
     * tick); the per-tick {@link #checkExtraStartConditions} handles
     * that and gracefully ends the behavior when no targets exist.
     */
    public static boolean hasActionableWork(ServerLevel level, TownspersonMob entity) {
        if (entity.getProfession() != Profession.FARMER
                && entity.getProfession() != Profession.FARMHAND) return false;
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);
        if (role != FarmRole.SHEPHERD) return false;
        Building farmhouse = entity.getAssignedBuildingId()
                .flatMap(VillageSavedData.get(level)::getBuildingById).orElse(null);
        if (farmhouse == null) return false;
        BuildingRoster roster = RosterSavedData.get(level)
                .getRoster(farmhouse.getId(), AnimalRosterDefinitions.SHEEP).orElse(null);
        if (roster == null) return false;
        if (roster.countAdults() == 0) return false;
        if (!hasShears(entity)) return false;
        return true;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;
        if (!hasActionableWork(level, entity)) return false;

        VillageSavedData vdata = VillageSavedData.get(level);
        Building fh = entity.getAssignedBuildingId().flatMap(vdata::getBuildingById)
                .orElse(null);
        if (fh == null) return false;
        BuildingRoster roster = RosterSavedData.get(level)
                .getRoster(fh.getId(), AnimalRosterDefinitions.SHEEP).orElse(null);
        if (roster == null) return false;

        // Rotate if current bound pen has depleted grass (or no
        // binding at all). chooseAndBindActivePen updates the roster's
        // boundPlotId in-place.
        FarmPlot pen = vdata.getFarmPlotById(roster.boundPlotId().orElse(null))
                .orElse(null);
        boolean needsRotation = pen == null
                || pen.getSoilQuality() <= ROTATION_QUALITY_THRESHOLD;
        if (needsRotation) {
            pen = PastureRotation.chooseAndBindActivePen(level, fh.getId(), roster)
                    .orElse(null);
        }
        if (pen == null) return false;

        this.farmhouse   = fh;
        this.sheepRoster = roster;
        this.activePen   = pen;
        this.penAnchor   = pen.getOrigin();
        this.targetSheep = null;
        this.shearedColor = null;
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
        phase = Phase.WALKING_TO_PEN;
        entity.setCurrentActivity("Tending sheep");
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(penAnchor, WALK_SPEED, CLOSE_ENOUGH_PEN));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case WALKING_TO_PEN    -> tickWalkingToPen(level, entity);
            case APPROACHING_SHEEP -> tickApproachingSheep(entity);
            case SHEARING          -> tickShearing(level, entity, gameTime);
            case DEPOSITING        -> tickDepositing(level, entity, gameTime);
            case DONE              -> { /* canStillUse short-circuits */ }
        }
    }

    private void tickWalkingToPen(ServerLevel level, TownspersonMob entity) {
        double distSq = entity.distanceToSqr(
                penAnchor.getX() + 0.5, penAnchor.getY(), penAnchor.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ_PEN) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            // Now at pen — find a shearable sheep. If none, mark
            // pen as visited (grazed, even without shear) and end.
            targetSheep = findShearableSheep(level, entity);
            if (targetSheep == null) {
                if (activePen != null) activePen.onGrazed(level.getGameTime());
                phase = Phase.DONE;
                return;
            }
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(targetSheep.blockPosition(),
                            WALK_SPEED, CLOSE_ENOUGH_SHEEP));
            phase = Phase.APPROACHING_SHEEP;
            subTimer = 0;
            return;
        }
        if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickApproachingSheep(TownspersonMob entity) {
        // Sheep may have moved or died since we picked them.
        if (targetSheep == null || !targetSheep.isAlive()
                || !targetSheep.readyForShearing()) {
            // Lost target — bail gracefully.
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.DONE;
            return;
        }
        // Track moving target — sheep wander, refresh the walk
        // target every 20 ticks.
        if (subTimer % 20 == 0) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(targetSheep.blockPosition(),
                            WALK_SPEED, CLOSE_ENOUGH_SHEEP));
        }
        double distSq = entity.distanceToSqr(targetSheep);
        if (distSq <= ARRIVAL_DIST_SQ_SHEEP) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.SHEARING;
            subTimer = 0;
            return;
        }
        if (subTimer > 400 && !entity.getNavigation().isInProgress()) {
            // Lost the sheep — pick a new one next cycle.
            phase = Phase.DONE;
        }
    }

    private void tickShearing(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (targetSheep == null || !targetSheep.isAlive()) {
            phase = Phase.DONE;
            return;
        }
        entity.getLookControl().setLookAt(
                targetSheep.getX(), targetSheep.getY() + 0.5, targetSheep.getZ());
        if (subTimer < SHEAR_TICKS) return;

        // Perform the shear — direct setSheared + manual wool stack,
        // bypassing vanilla shear()'s loot table / world-drop path.
        if (!targetSheep.readyForShearing()) {
            // Race: another sheared them between approach and now.
            phase = Phase.DONE;
            return;
        }
        DyeColor color = targetSheep.getColor();
        shearedColor = color;
        targetSheep.setSheared(true);
        level.playSound(null, targetSheep.blockPosition(),
                SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 1.0f, 1.0f);
        // Damage the shears (and break + clear slot if exhausted).
        // Returns true unconditionally here because hasShears was
        // checked earlier — defensive ignore of the return.
        ToolUseSupport.useToolFromInventory(entity,
                s -> s.getItem() == Items.SHEARS, level, InteractionHand.MAIN_HAND);

        ItemStack wool = new ItemStack(woolForColor(color), WOOL_PER_SHEAR);
        // Carry wool to farmhouse in personal inventory rather than
        // depositing now — keeps DEPOSITING phase symmetrical with
        // other production behaviors and gives a visible "walking with
        // wool" beat. addItem auto-stacks with any existing wool.
        entity.getPersonalInventory().addItem(wool);

        if (activePen != null) activePen.onGrazed(gameTime);

        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(farmhouse.getShape().getOrigin(),
                        WALK_SPEED, CLOSE_ENOUGH_PEN));
        phase = Phase.DEPOSITING;
        subTimer = 0;
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        BlockPos fhOrigin = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                fhOrigin.getX() + 0.5, fhOrigin.getY(), fhOrigin.getZ() + 0.5);
        if (distSq > ARRIVAL_DIST_SQ_PEN) {
            if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
                // Couldn't reach farmhouse — wool stays in inventory;
                // next cycle retries.
                phase = Phase.DONE;
            }
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        // Drain matching wool from personal inventory into farmhouse
        // storage. storeItem mutates the stack — shrinks it as items
        // are placed — so post-call inspection of the stack tells us
        // how many actually landed.
        Item woolItem = woolForColor(shearedColor != null ? shearedColor : DyeColor.WHITE);
        var inv = entity.getPersonalInventory();
        int deposited = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != woolItem) continue;
            int before = s.getCount();
            BuildingStorageAccess.storeItem(level, farmhouse, s);
            int placed = before - s.getCount();
            if (placed > 0) {
                deposited += placed;
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        // FARMER_SHEPHERD spec +50% bonus (FarmerSpecialtyMultiplier
        // recognizes SHEPHERDING + ANIMAL_HUSBANDRY targets per 6.7.1.6).
        // SkillXp.award stacks AMBITION + mentor multipliers on top.
        float xp = SHEARING_XP_PER_ACTION
                * FarmerSpecialtyMultiplier.of(entity, Skill.SHEPHERDING);
        SkillXp.award(entity, Skill.SHEPHERDING, xp, gameTime);
        LOGGER.info("[ShepherdBehavior] {} (SHEPHERDING {}) sheared {} sheep at pen {}; "
                + "deposited {} {}",
                entity.getNpcName(),
                entity.getSkills().getLevel(Skill.SHEPHERDING),
                shearedColor != null ? shearedColor.name() : "?",
                activePen.getId(),
                deposited,
                woolItem);
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        this.targetSheep = null;
        this.shearedColor = null;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean hasShears(TownspersonMob entity) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Items.SHEARS) return true;
        }
        return false;
    }

    /**
     * Scans the pen bounds (plus a small padding for sheep that wander
     * past the fence) for the nearest live, adult, un-sheared sheep.
     * Returns null when no candidate exists — caller ends the behavior
     * gracefully (regrow timers will eventually restock targets).
     */
    private Sheep findShearableSheep(ServerLevel level, TownspersonMob entity) {
        if (activePen == null) return null;
        int radius = Math.max(activePen.getRadius(), 4) + SHEEP_SEARCH_PADDING;
        BlockPos o = activePen.getOrigin();
        AABB area = new AABB(
                o.getX() - radius, o.getY() - 4, o.getZ() - radius,
                o.getX() + radius, o.getY() + 8, o.getZ() + radius);
        List<Sheep> candidates = level.getEntitiesOfClass(Sheep.class, area,
                s -> s.isAlive() && s.readyForShearing());
        if (candidates.isEmpty()) return null;
        Sheep best = null;
        double bestSq = Double.MAX_VALUE;
        for (Sheep s : candidates) {
            double sq = entity.distanceToSqr(s);
            if (sq < bestSq) { bestSq = sq; best = s; }
        }
        return best;
    }

    /** 16-case mapping from DyeColor to the matching wool {@link Item}. */
    private static Item woolForColor(DyeColor color) {
        return switch (color) {
            case WHITE      -> Items.WHITE_WOOL;
            case ORANGE     -> Items.ORANGE_WOOL;
            case MAGENTA    -> Items.MAGENTA_WOOL;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_WOOL;
            case YELLOW     -> Items.YELLOW_WOOL;
            case LIME       -> Items.LIME_WOOL;
            case PINK       -> Items.PINK_WOOL;
            case GRAY       -> Items.GRAY_WOOL;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_WOOL;
            case CYAN       -> Items.CYAN_WOOL;
            case PURPLE     -> Items.PURPLE_WOOL;
            case BLUE       -> Items.BLUE_WOOL;
            case BROWN      -> Items.BROWN_WOOL;
            case GREEN      -> Items.GREEN_WOOL;
            case RED        -> Items.RED_WOOL;
            case BLACK      -> Items.BLACK_WOOL;
        };
    }
}
