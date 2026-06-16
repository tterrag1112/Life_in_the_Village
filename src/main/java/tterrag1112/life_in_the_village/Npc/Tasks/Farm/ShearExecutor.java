package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.ToolUseSupport;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions;
import tterrag1112.life_in_the_village.Village.Roster.BuildingRoster;
import tterrag1112.life_in_the_village.Village.Roster.PastureRotation;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.List;
import java.util.UUID;

/**
 * G2b — executor for a {@link FarmVerb#SHEAR} task.
 *
 * <p>Behavior-faithful port of {@code ShepherdBehavior}:
 * same pen-selection via {@link PastureRotation#chooseAndBindActivePen},
 * same AABB scan for shearable sheep, same {@link Sheep#setSheared(boolean)},
 * same colour-correct wool drop, same shears damage via
 * {@link ToolUseSupport#useToolFromInventory}, same farmhouse deposit,
 * same SHEPHERDING XP + FarmerSpecialtyMultiplier.</p>
 *
 * <h3>GATED_SPECIES contract</h3>
 * <p>{@link BuildingRoster} excludes Realized SHEEP slots from the
 * simulation-cycle production count ({@code isProductionGated}).
 * This executor drives realized-sheep production by calling
 * {@code sheep.setSheared(true)}, which marks the wool as removed and
 * starts vanilla's "eat grass → regrow wool" timer. Without this call
 * realized sheep never re-grow wool. Simulated adults continue to
 * produce via {@code BuildingRoster.tick} unaffected.</p>
 */
public final class ShearExecutor implements TaskExecutor {

    private static final float  WALK_SPEED             = 0.65f;
    private static final double ARRIVAL_DIST_SQ_PEN    = 16.0;
    private static final double ARRIVAL_DIST_SQ_SHEEP  = 4.0;
    private static final int    SHEAR_TICKS            = 30;
    private static final int    WOOL_PER_SHEAR         = 2;
    private static final int    SHEARING_XP_PER_ACTION = 2;
    private static final float  ROTATION_QUALITY_THRESHOLD = 0.4f;
    private static final int    SHEEP_SEARCH_PADDING   = 4;
    private static final int    NAV_TIMEOUT            = 600;
    private static final int    APPROACH_TIMEOUT       = 400;

    private enum Phase { WALKING_TO_PEN, APPROACHING_SHEEP, SHEARING, DEPOSITING, DONE }

    // ── Executor state ────────────────────────────────────────────────────────
    private Building  farmhouse;
    private FarmPlot  activePen;
    private Sheep     targetSheep;
    private DyeColor  shearedColor;
    private Phase     phase;
    private int       subTimer;

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;
        if (npc.isChild()) return Result.FAILED;
        if (!BrainNavGuard.canSteerNavigation(npc)) return Result.RUNNING;

        if (!(task.objective() instanceof Objective.PerformService ps)) return Result.FAILED;
        UUID farmhouseId = ps.ref().map(UUID::fromString).orElse(null);
        if (farmhouseId == null) return Result.FAILED;

        // ── Lazy init (first tick) ────────────────────────────────────────────
        if (phase == null) {
            if (!init(level, npc, farmhouseId)) return Result.FAILED;
        }

        subTimer++;
        return switch (phase) {
            case WALKING_TO_PEN    -> tickWalkingToPen(level, npc);
            case APPROACHING_SHEEP -> tickApproachingSheep(npc);
            case SHEARING          -> tickShearing(level, npc, ctx.gameTime());
            case DEPOSITING        -> tickDepositing(level, npc, ctx.gameTime());
            case DONE              -> Result.DONE;
        };
    }

    private boolean init(ServerLevel level, TownspersonMob npc, UUID farmhouseId) {
        farmhouse = VillageSavedData.get(level).getBuildingById(farmhouseId)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);
        if (farmhouse == null) return false;

        BuildingRoster sheepRoster = RosterSavedData.get(level)
                .getRoster(farmhouseId, AnimalRosterDefinitions.SHEEP).orElse(null);
        if (sheepRoster == null || sheepRoster.countAdults() == 0) return false;
        if (!hasShears(npc)) return false;

        // Pen selection: rotate if current pen has depleted grass or no binding.
        FarmPlot pen = VillageSavedData.get(level)
                .getFarmPlotById(sheepRoster.boundPlotId().orElse(null)).orElse(null);
        boolean needsRotation = pen == null
                || pen.getSoilQuality() <= ROTATION_QUALITY_THRESHOLD;
        if (needsRotation) {
            pen = PastureRotation.chooseAndBindActivePen(
                    level, farmhouseId, sheepRoster).orElse(null);
        }
        if (pen == null) return false;

        activePen = pen;
        phase     = Phase.WALKING_TO_PEN;
        subTimer  = 0;
        npc.setCurrentActivity("Shearing sheep");
        NpcBehaviorHelpers.walkTo(npc, activePen.getOrigin(), WALK_SPEED);
        return true;
    }

    // ── Phase ticks ───────────────────────────────────────────────────────────

    private Result tickWalkingToPen(ServerLevel level, TownspersonMob npc) {
        BlockPos penPos = activePen.getOrigin();
        double distSq = npc.distanceToSqr(
                penPos.getX() + 0.5, penPos.getY(), penPos.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ_PEN) {
            targetSheep = findShearableSheep(level, npc);
            if (targetSheep == null) {
                // No shearable sheep in pen — mark grazed and end cleanly.
                activePen.onGrazed(level.getGameTime());
                phase = Phase.DONE;
                return Result.DONE;
            }
            phase    = Phase.APPROACHING_SHEEP;
            subTimer = 0;
            NpcBehaviorHelpers.walkTo(npc, targetSheep.blockPosition(), WALK_SPEED);
            return Result.RUNNING;
        }
        if (subTimer > NAV_TIMEOUT && !npc.getNavigation().isInProgress()) {
            phase = Phase.DONE;
            return Result.DONE;
        }
        return Result.RUNNING;
    }

    private Result tickApproachingSheep(TownspersonMob npc) {
        if (targetSheep == null || !targetSheep.isAlive() || !targetSheep.readyForShearing()) {
            phase = Phase.DONE;
            return Result.DONE;
        }
        // Refresh walk target toward moving sheep every 20 ticks.
        if (subTimer % 20 == 0) {
            NpcBehaviorHelpers.walkTo(npc, targetSheep.blockPosition(), WALK_SPEED);
        }
        double distSq = npc.distanceToSqr(targetSheep);
        if (distSq <= ARRIVAL_DIST_SQ_SHEEP) {
            phase    = Phase.SHEARING;
            subTimer = 0;
            return Result.RUNNING;
        }
        if (subTimer > APPROACH_TIMEOUT && !npc.getNavigation().isInProgress()) {
            phase = Phase.DONE;
            return Result.DONE;
        }
        return Result.RUNNING;
    }

    private Result tickShearing(ServerLevel level, TownspersonMob npc, long gameTime) {
        if (targetSheep == null || !targetSheep.isAlive()) {
            phase = Phase.DONE;
            return Result.DONE;
        }
        npc.getLookControl().setLookAt(
                targetSheep.getX(), targetSheep.getY() + 0.5, targetSheep.getZ());
        if (subTimer < SHEAR_TICKS) return Result.RUNNING;

        if (!targetSheep.readyForShearing()) {
            // Race: another actor sheared this sheep.
            phase = Phase.DONE;
            return Result.DONE;
        }

        // ── Physical shear — behavior-faithful, bypasses vanilla loot-table drop ──
        DyeColor color = targetSheep.getColor();
        shearedColor   = color;
        // setSheared(true) is the GATED_SPECIES realized-production driver:
        // marks wool removed and starts the vanilla "eat grass → regrow" timer.
        targetSheep.setSheared(true);
        level.playSound(null, targetSheep.blockPosition(),
                SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 1.0f, 1.0f);
        ToolUseSupport.useToolFromInventory(npc,
                s -> s.getItem() == Items.SHEARS, level, InteractionHand.MAIN_HAND);

        npc.getPersonalInventory().addItem(new ItemStack(woolForColor(color), WOOL_PER_SHEAR));

        if (activePen != null) activePen.onGrazed(gameTime);

        phase    = Phase.DEPOSITING;
        subTimer = 0;
        NpcBehaviorHelpers.walkTo(npc, farmhouse.getShape().getOrigin(), WALK_SPEED);
        return Result.RUNNING;
    }

    private Result tickDepositing(ServerLevel level, TownspersonMob npc, long gameTime) {
        BlockPos fhOrigin = farmhouse.getShape().getOrigin();
        double distSq = npc.distanceToSqr(
                fhOrigin.getX() + 0.5, fhOrigin.getY(), fhOrigin.getZ() + 0.5);
        if (distSq > ARRIVAL_DIST_SQ_PEN) {
            if (subTimer > NAV_TIMEOUT && !npc.getNavigation().isInProgress()) {
                phase = Phase.DONE;
                return Result.DONE;
            }
            return Result.RUNNING;
        }

        // Drain matching wool from personal inventory into farmhouse storage.
        Item woolItem = woolForColor(shearedColor != null ? shearedColor : DyeColor.WHITE);
        var inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != woolItem) continue;
            BuildingStorageAccess.storeItem(level, farmhouse, s);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }

        float xp = SHEARING_XP_PER_ACTION
                * FarmerSpecialtyMultiplier.of(npc, Skill.SHEPHERDING);
        SkillXp.award(npc, Skill.SHEPHERDING, xp, gameTime);

        npc.clearCurrentActivity();
        phase = Phase.DONE;
        return Result.DONE;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean hasShears(TownspersonMob entity) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Items.SHEARS) return true;
        }
        return false;
    }

    /** Scans pen bounds (plus padding) for the nearest live, adult, unsheared sheep. */
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

    /** 16-case mapping from DyeColor to the matching wool Item. */
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
