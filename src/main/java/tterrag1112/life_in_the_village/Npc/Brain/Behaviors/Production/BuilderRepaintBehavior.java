package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.RepaintJob;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.RuntimeTintPass;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.TintPass;


/**
 * Doc 15 §"Player-requested repaint" — the builder NPC's repaint
 * goal. Mirrors the structure of {@link BuilderMaintenanceGoal}:
 * a {@link Goal} subclass with a small phase machine, ticked while
 * the builder is on duty, walking → painting → idle.
 *
 * <h3>Visit cadence</h3>
 * One visit per in-game day (24000 ticks). Each visit paints a
 * fraction of the building's tintable blocks; total visits =
 * {@code ceil(footprintArea / 20)} per doc 15.
 *
 * <h3>Persistence</h3>
 * The {@link RepaintJob} state lives on the builder NPC's save data
 * (see {@code TownspersonMob.addAdditionalSaveData} /
 * {@code readAdditionalSaveData}); the goal is just the runtime
 * driver. On reload the goal re-attaches to whatever job the NPC
 * carries.
 *
 * <h3>Cancellation</h3>
 * If the building disappears from save data mid-job, the goal
 * marks the job complete and stops. If the builder dies, the job
 * stays on the NPC's save data and is dropped with the entity;
 * surfacing the job to another builder is a future iteration.
 */
public class BuilderRepaintBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOG = LoggerFactory.getLogger(BuilderRepaintBehavior.class);

    /** Doc 15: 1 visit per in-game day. */
    public static final long DAY_LENGTH_TICKS = 24000L;
    /** How long the builder stands at the building per visit. */
    private static final int PAINT_DURATION_TICKS = 600; // ≈30 seconds
    /** XZ Chebyshev distance² inside which the builder is "at" the building. */
    private static final double INTERACT_RANGE_SQ = 25.0; // 5 blocks

    private enum Phase { IDLE, WALKING, PAINTING }

    private TownspersonMob entity;

    public BuilderRepaintBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Phase phase = Phase.IDLE;
    private int paintTimer = 0;
    private Building targetBuilding = null;

    

    // ── Goal lifecycle ───────────────────────────────────────────────────

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;
        RepaintJob job = entity.getRepaintJob();
        if (job == null) return false;
        if (job.state() == RepaintJob.State.COMPLETE) return false;
        if (level.getGameTime() < job.nextVisitTick()) return false;

        targetBuilding = VillageSavedData.get(level)
                .getBuildingById(job.buildingId()).orElse(null);
        if (targetBuilding == null) {
            // Building was destroyed — close out the job.
            job.setState(RepaintJob.State.COMPLETE);
            entity.setRepaintJob(null);
            return false;
        }
        return true;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.WALKING;
        paintTimer = 0;
        if (targetBuilding != null) {
            entity.setCurrentActivity("Heading to repaint "
                    + targetBuilding.getName());
            entity.setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(Items.YELLOW_DYE));
            BlockPos dest = targetBuilding.getShape().getOrigin();
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0));
            RepaintJob job = entity.getRepaintJob();
            if (job != null) job.setState(RepaintJob.State.EN_ROUTE);
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE
                && targetBuilding != null
                && entity.getRepaintJob() != null;
    }

        @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        switch (phase) {
            case WALKING  -> tickWalking(level);
            case PAINTING -> tickPainting(level);
            default -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        resetState();
    }

    private void resetState() {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
        phase = Phase.IDLE;
    }

    // ── Phase: WALKING ───────────────────────────────────────────────────

    private void tickWalking(ServerLevel level) {
        if (targetBuilding == null) { resetState(); return; }
        BlockPos dest = targetBuilding.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                dest.getX(), dest.getY(), dest.getZ());

        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.PAINTING;
            paintTimer = 0;
            entity.setCurrentActivity("Repainting " + targetBuilding.getName());
            RepaintJob job = entity.getRepaintJob();
            if (job != null) job.setState(RepaintJob.State.PAINTING);
        } else if (!entity.getNavigation().isInProgress()) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0));
        }
    }

    // ── Phase: PAINTING ──────────────────────────────────────────────────

    private void tickPainting(ServerLevel level) {
        paintTimer++;

        if (paintTimer % 40 == 0) {
            BlockPos dest = targetBuilding.getShape().getOrigin();
            entity.getLookControl().setLookAt(
                    dest.getX(), dest.getY() + 1, dest.getZ());
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.WOOL_PLACE, SoundSource.NEUTRAL,
                    0.5f, 0.8f + level.getRandom().nextFloat() * 0.4f);
        }

        if (paintTimer < PAINT_DURATION_TICKS) return;

        // Apply this visit's paint batch.
        applyPaintBatch(level);

        // Schedule next visit one in-game day from now (or finish).
        RepaintJob job = entity.getRepaintJob();
        if (job != null) {
            int area = footprintArea(targetBuilding);
            if (job.isComplete(area)) {
                job.setState(RepaintJob.State.COMPLETE);
                entity.setRepaintJob(null);
                level.playSound(null, entity.blockPosition(),
                        SoundEvents.NOTE_BLOCK_CHIME.value(),
                        SoundSource.NEUTRAL, 0.6f, 1.0f);
            } else {
                job.setState(RepaintJob.State.PLANNED);
                job.setNextVisitTick(level.getGameTime() + DAY_LENGTH_TICKS);
            }
        }

        phase = Phase.IDLE;
        targetBuilding = null;
    }

    // ── Paint batch ──────────────────────────────────────────────────────

    private void applyPaintBatch(ServerLevel level) {
        RepaintJob job = entity.getRepaintJob();
        if (job == null || targetBuilding == null) return;

        // Doc 15: "footprint / 20 blocks repainted per visit".
        int area = footprintArea(targetBuilding);
        int totalVisits = job.totalVisits(area);
        int visitsLeft = Math.max(1, totalVisits - job.visitsCompleted());
        // Per-visit cap. Aim for at least 8 blocks so very small
        // buildings still feel like work.
        int perVisitCap = Math.max(8,
                (int) Math.ceil(area / (double) totalVisits));

        // We don't know how many tintable blocks the building has
        // without scanning; the runtime tint pass returns a
        // "remaining" count so the goal can stretch across visits.
        TintPass.Plan plan = new TintPass.Plan(
                job.primary(), job.accent(), job.roof());
        RuntimeTintPass.Result result = RuntimeTintPass.apply(
                level, targetBuilding, job.slots(), plan, perVisitCap);

        job.incrementVisits();

        // Push live updated colour fields onto the Building. The
        // record was already updated at confirm time, but we re-write
        // here defensively in case the Building object instance
        // changed.
        if (job.slots().contains(tterrag1112.life_in_the_village
                .Village.Decoration.Variants.ColorSlot.PRIMARY)
                && job.primary() != null) {
            targetBuilding.setPrimaryColor(job.primary());
        }
        if (job.slots().contains(tterrag1112.life_in_the_village
                .Village.Decoration.Variants.ColorSlot.ACCENT)
                && job.accent() != null) {
            targetBuilding.setAccentColor(job.accent());
        }
        if (job.slots().contains(tterrag1112.life_in_the_village
                .Village.Decoration.Variants.ColorSlot.ROOF)
                && job.roof() != null) {
            targetBuilding.setRoofColor(job.roof());
        }
        VillageSavedData.get(level).markDirty();

        LOG.info("BuilderRepaintGoal: visit {} of {} — painted {} blocks "
                + "(remaining: {}) on building {}",
                job.visitsCompleted(), totalVisits,
                result.painted(), result.remaining(),
                targetBuilding.getName());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static int footprintArea(Building building) {
        Building.BuildingShape shape = building.getShape();
        return Math.max(1, shape.getWidth() * shape.getLength());
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
