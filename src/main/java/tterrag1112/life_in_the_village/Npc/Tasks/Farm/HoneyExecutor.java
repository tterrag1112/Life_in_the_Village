package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
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
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.UUID;

/**
 * G2b — executor for a {@link FarmVerb#COLLECT_HONEY} task.
 *
 * <p>Behavior-faithful port of {@code BeekeeperBehavior}:
 * same APIARY plot resolution, same full-hive scan, same output alternation
 * via per-instance {@link #lastHarvest}, same tool use (shears/bottle), same
 * HONEY_LEVEL reset, same farmhouse deposit, same BEEKEEPING XP.</p>
 *
 * <h3>GATED_SPECIES contract</h3>
 * <p>{@link BuildingRoster} excludes Realized BEE slots from the
 * simulation-cycle production count ({@code isProductionGated}).
 * This executor drives realized-bee production by resetting
 * {@code HONEY_LEVEL = 0} after harvest, allowing vanilla bee AI to
 * refill the hive. Without this reset, realized hives stay at level 5
 * and the bees never fill them again. Simulated adults continue to
 * produce via {@code BuildingRoster.tick} unaffected.</p>
 */
public final class HoneyExecutor implements TaskExecutor {

    private static final float  WALK_SPEED               = 0.65f;
    private static final double ARRIVAL_DIST_SQ_APIARY   = 16.0;
    private static final double ARRIVAL_DIST_SQ_HIVE     = 4.0;
    private static final int    HARVEST_TICKS             = 40;
    private static final int    BEEKEEPING_XP_PER_ACTION  = 2;
    private static final int    HONEYCOMB_STOCK_QUOTA     = 16;
    private static final int    HONEY_BOTTLE_STOCK_QUOTA  = 8;
    private static final int    HONEY_LEVEL_FULL          = 5;
    private static final int    CAMPFIRE_SCAN_RADIUS      = 5;
    private static final int    NAV_TIMEOUT               = 600;
    private static final int    APPROACH_TIMEOUT          = 400;

    private enum Phase { WALKING_TO_APIARY, APPROACHING_HIVE, HARVESTING, DEPOSITING, DONE }
    private enum Output { HONEYCOMB, HONEY_BOTTLE }

    // ── Executor state ────────────────────────────────────────────────────────
    private Building farmhouse;
    private FarmPlot apiaryPlot;
    private BlockPos targetHive;
    private Output   chosenOutput;
    /** Per-instance alternation state — mirrors BeekeeperBehavior.lastHarvest. */
    private Output   lastHarvest;
    private Phase    phase;
    private int      subTimer;

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
            case WALKING_TO_APIARY -> tickWalkingToApiary(level, npc);
            case APPROACHING_HIVE  -> tickApproachingHive(level, npc);
            case HARVESTING        -> tickHarvesting(level, npc, ctx.gameTime());
            case DEPOSITING        -> tickDepositing(level, npc, ctx.gameTime());
            case DONE              -> Result.DONE;
        };
    }

    private boolean init(ServerLevel level, TownspersonMob npc, UUID farmhouseId) {
        farmhouse = VillageSavedData.get(level).getBuildingById(farmhouseId)
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);
        if (farmhouse == null) return false;

        BuildingRoster beeRoster = RosterSavedData.get(level)
                .getRoster(farmhouseId, AnimalRosterDefinitions.BEE).orElse(null);
        if (beeRoster == null || beeRoster.countAdults() == 0) return false;

        // APIARY plot from roster's boundPlotId.
        FarmPlot plot = VillageSavedData.get(level)
                .getFarmPlotById(beeRoster.boundPlotId().orElse(null))
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.APIARY)
                .orElse(null);
        if (plot == null) return false;

        // Pick output mode (mirrors BeekeeperBehavior.checkExtraStartConditions).
        Output mode = chooseOutput(level, farmhouse, npc);
        if (mode == null) return false;

        // Find a full hive at init time; re-check on arrival.
        BlockPos hive = findFullHive(level, plot);
        if (hive == null) return false;

        apiaryPlot   = plot;
        targetHive   = hive;
        chosenOutput = mode;
        phase        = Phase.WALKING_TO_APIARY;
        subTimer     = 0;
        npc.setCurrentActivity("Tending hives");
        NpcBehaviorHelpers.walkTo(npc, plot.getOrigin(), WALK_SPEED);
        return true;
    }

    // ── Phase ticks ───────────────────────────────────────────────────────────

    private Result tickWalkingToApiary(ServerLevel level, TownspersonMob npc) {
        BlockPos anchor = apiaryPlot.getOrigin();
        double distSq = npc.distanceToSqr(
                anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ_APIARY) {
            // Re-pick hive on arrival — original may have been emptied.
            BlockPos hive = findFullHive(level, apiaryPlot);
            if (hive == null) {
                phase = Phase.DONE;
                return Result.DONE;
            }
            targetHive = hive;
            phase      = Phase.APPROACHING_HIVE;
            subTimer   = 0;
            NpcBehaviorHelpers.walkTo(npc, hive, WALK_SPEED);
            return Result.RUNNING;
        }
        if (subTimer > NAV_TIMEOUT && !npc.getNavigation().isInProgress()) {
            phase = Phase.DONE;
            return Result.DONE;
        }
        return Result.RUNNING;
    }

    private Result tickApproachingHive(ServerLevel level, TownspersonMob npc) {
        // Re-verify hive still full — race against external harvest.
        BlockState s = level.getBlockState(targetHive);
        if (!(s.getBlock() instanceof BeehiveBlock)
                || s.getValue(BeehiveBlock.HONEY_LEVEL) < HONEY_LEVEL_FULL) {
            phase = Phase.DONE;
            return Result.DONE;
        }
        double distSq = npc.distanceToSqr(
                targetHive.getX() + 0.5, targetHive.getY(), targetHive.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ_HIVE) {
            phase    = Phase.HARVESTING;
            subTimer = 0;
            return Result.RUNNING;
        }
        if (subTimer > APPROACH_TIMEOUT && !npc.getNavigation().isInProgress()) {
            phase = Phase.DONE;
            return Result.DONE;
        }
        return Result.RUNNING;
    }

    private Result tickHarvesting(ServerLevel level, TownspersonMob npc, long gameTime) {
        npc.getLookControl().setLookAt(
                targetHive.getX() + 0.5, targetHive.getY() + 0.5, targetHive.getZ() + 0.5);
        if (subTimer < HARVEST_TICKS) return Result.RUNNING;

        BlockState hiveState = level.getBlockState(targetHive);
        if (!(hiveState.getBlock() instanceof BeehiveBlock)
                || hiveState.getValue(BeehiveBlock.HONEY_LEVEL) < HONEY_LEVEL_FULL) {
            phase = Phase.DONE;
            return Result.DONE;
        }

        // Diagnostic only (not gating) — same as BeekeeperBehavior.
        @SuppressWarnings("unused")
        boolean smoked = campfireNearby(level, targetHive);

        Item produced;
        int count;
        if (chosenOutput == Output.HONEYCOMB) {
            ToolUseSupport.useToolFromInventory(npc,
                    s -> s.getItem() == Items.SHEARS, level, InteractionHand.MAIN_HAND);
            produced = Items.HONEYCOMB;
            count    = 3; // vanilla shear yields 3 honeycomb
            level.playSound(null, targetHive,
                    SoundEvents.BEEHIVE_SHEAR, SoundSource.NEUTRAL, 1.0f, 1.0f);
        } else {
            if (!consumeOne(npc, Items.GLASS_BOTTLE)) {
                phase = Phase.DONE;
                return Result.DONE;
            }
            produced = Items.HONEY_BOTTLE;
            count    = 1;
            level.playSound(null, targetHive,
                    SoundEvents.BOTTLE_FILL_DRAGONBREATH, SoundSource.NEUTRAL, 1.0f, 1.0f);
        }

        // GATED_SPECIES realized-production driver: resetting HONEY_LEVEL=0
        // allows vanilla bee AI to start refilling the hive. Without this,
        // realized hives stay at level 5 and produce nothing.
        level.setBlock(targetHive, hiveState.setValue(BeehiveBlock.HONEY_LEVEL, 0), 3);
        npc.getPersonalInventory().addItem(new ItemStack(produced, count));
        this.lastHarvest = chosenOutput;

        phase    = Phase.DEPOSITING;
        subTimer = 0;
        NpcBehaviorHelpers.walkTo(npc, farmhouse.getShape().getOrigin(), WALK_SPEED);
        return Result.RUNNING;
    }

    private Result tickDepositing(ServerLevel level, TownspersonMob npc, long gameTime) {
        BlockPos fhOrigin = farmhouse.getShape().getOrigin();
        double distSq = npc.distanceToSqr(
                fhOrigin.getX() + 0.5, fhOrigin.getY(), fhOrigin.getZ() + 0.5);
        if (distSq > ARRIVAL_DIST_SQ_APIARY) {
            if (subTimer > NAV_TIMEOUT && !npc.getNavigation().isInProgress()) {
                phase = Phase.DONE;
                return Result.DONE;
            }
            return Result.RUNNING;
        }

        Item producedItem = chosenOutput == Output.HONEYCOMB
                ? Items.HONEYCOMB : Items.HONEY_BOTTLE;
        var inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != producedItem) continue;
            BuildingStorageAccess.storeItem(level, farmhouse, s);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
        }

        float xp = BEEKEEPING_XP_PER_ACTION
                * FarmerSpecialtyMultiplier.of(npc, Skill.BEEKEEPING);
        SkillXp.award(npc, Skill.BEEKEEPING, xp, gameTime);

        npc.clearCurrentActivity();
        phase = Phase.DONE;
        return Result.DONE;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Mirrors BeekeeperBehavior.chooseOutput exactly, including alternation. */
    private Output chooseOutput(ServerLevel level, Building fh, TownspersonMob npc) {
        boolean canMakeComb = hasItem(npc, Items.SHEARS)
                && BuildingStorageAccess.countItem(level, fh, Items.HONEYCOMB)
                        < HONEYCOMB_STOCK_QUOTA;
        boolean canMakeBottle = hasItem(npc, Items.GLASS_BOTTLE)
                && BuildingStorageAccess.countItem(level, fh, Items.HONEY_BOTTLE)
                        < HONEY_BOTTLE_STOCK_QUOTA;
        if (canMakeComb && canMakeBottle) {
            return lastHarvest == Output.HONEYCOMB ? Output.HONEY_BOTTLE : Output.HONEYCOMB;
        }
        if (canMakeComb)   return Output.HONEYCOMB;
        if (canMakeBottle) return Output.HONEY_BOTTLE;
        return null;
    }

    /** Scans apiary plot bounds for a hive block at HONEY_LEVEL >= 5. */
    private static BlockPos findFullHive(ServerLevel level, FarmPlot plot) {
        BlockPos o = plot.getOrigin();
        int r = Math.max(plot.getRadius(), 4);
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    cur.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    BlockState s = level.getBlockState(cur);
                    if (!(s.getBlock() instanceof BeehiveBlock)) continue;
                    if (s.getValue(BeehiveBlock.HONEY_LEVEL) >= HONEY_LEVEL_FULL) {
                        return cur.immutable();
                    }
                }
            }
        }
        return null;
    }

    /** Diagnostic campfire check — not gating (mirrors BeekeeperBehavior). */
    private static boolean campfireNearby(ServerLevel level, BlockPos hive) {
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -CAMPFIRE_SCAN_RADIUS; dy <= 0; dy++) {
                    cur.set(hive.getX() + dx, hive.getY() + dy, hive.getZ() + dz);
                    if (level.getBlockState(cur).getBlock() instanceof CampfireBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasItem(TownspersonMob entity, Item item) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) return true;
        }
        return false;
    }

    /** Removes one of {@code item} from personal inventory. */
    private static boolean consumeOne(TownspersonMob entity, Item item) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            s.shrink(1);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }
}
