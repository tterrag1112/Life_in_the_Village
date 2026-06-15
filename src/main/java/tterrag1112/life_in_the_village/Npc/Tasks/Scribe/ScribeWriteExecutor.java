package tterrag1112.life_in_the_village.Npc.Tasks.Scribe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionQueue;
import tterrag1112.life_in_the_village.Npc.Scribal.CommissionStatus;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribalItems;
import tterrag1112.life_in_the_village.Npc.Scribal.ScribeCommission;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;
import java.util.UUID;

/**
 * T3 — the per-tick state machine for writing one scribal commission. The
 * walk &rarr; write &rarr; deliver loop ported verbatim from the legacy
 * {@code ScribeWorkBehavior} (its WALKING / WRITING / DELIVERING phases, the
 * {@code writeTimer}, {@code deskPos}, {@code produceItem}, the LITERACY +4
 * award, the player-online-vs-drop delivery, and the queue status transitions),
 * re-homed onto the {@link TaskExecutor} lifecycle:
 * <ul>
 *   <li>arrive at the desk &rarr; flip commission to IN_PROGRESS &rarr; RUNNING;</li>
 *   <li>write for {@link #WRITE_TICKS} &rarr; produce the item + award XP + flip
 *       to READY &rarr; RUNNING;</li>
 *   <li>deliver (to the player if online &amp; near, else drop at the desk)
 *       &rarr; flip to DELIVERED &rarr; DONE;</li>
 *   <li>any precondition lost (workshop / commission gone) &rarr; FAILED, and
 *       an IN_PROGRESS commission is reverted to PENDING so a later run resumes
 *       it (mirrors the legacy {@code stop()} clean-abort).</li>
 * </ul>
 *
 * <p>One executor instance is created per claimed task (see
 * {@link ScribeWriteFulfillment#executor()}), so its small phase state is
 * per-run, exactly like a {@code Behavior} field is per-run.</p>
 */
public final class ScribeWriteExecutor implements TaskExecutor {

    /** Time spent at the desk per commission (legacy {@code WRITE_TICKS}). */
    public static final int WRITE_TICKS = 600;
    /** LITERACY XP per finished commission (legacy {@code XP_PER_COMMISSION}). */
    public static final int XP_PER_COMMISSION = 4;
    /** Distance squared inside which the scribe is "at the desk". */
    public static final double DESK_ARRIVAL_SQ = 4.0;

    private enum Phase { WALKING, WRITING, DELIVERING }

    private Phase phase = Phase.WALKING;
    private boolean started;
    private BlockPos deskPos;
    private int writeTimer;
    private boolean inProgressMarked; // true once we flipped PENDING -> IN_PROGRESS

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;

        UUID commissionId = commissionIdOf(task).orElse(null);
        if (commissionId == null) return revertAndFail(level, npc, null, null);

        Building workshop = workshop(level, npc).orElse(null);
        if (workshop == null) return revertAndFail(level, npc, null, commissionId);

        CommissionQueue queue = VillageSavedData.get(level)
                .getOrCreateCommissionQueue(workshop.getId());
        ScribeCommission active = queue.get(commissionId).orElse(null);
        if (active == null) return revertAndFail(level, npc, workshop, null);

        if (!started) {
            deskPos = workshop.getShape().getOrigin();
            if (deskPos == null) return revertAndFail(level, npc, workshop, commissionId);
            phase = Phase.WALKING;
            writeTimer = 0;
            npc.setCurrentActivity("Heading to writing desk");
            npc.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    deskPos.getX() + 0.5, deskPos.getY(), deskPos.getZ() + 0.5, 1.0));
            started = true;
            return Result.RUNNING;
        }

        return switch (phase) {
            case WALKING    -> tickWalking(level, npc, workshop, active);
            case WRITING    -> tickWriting(level, npc, workshop, active);
            case DELIVERING -> tickDelivering(level, npc, workshop, active);
        };
    }

    private Result tickWalking(ServerLevel level, TownspersonMob npc,
                               Building workshop, ScribeCommission active) {
        double d2 = npc.distanceToSqr(
                deskPos.getX() + 0.5, deskPos.getY(), deskPos.getZ() + 0.5);
        if (d2 <= DESK_ARRIVAL_SQ) {
            npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.WRITING;
            npc.setCurrentActivity("Writing " + active.product().name().toLowerCase());
            npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
            // Mark commission in-progress so concurrent picks don't double-write.
            CommissionQueue queue = VillageSavedData.get(level)
                    .getOrCreateCommissionQueue(workshop.getId());
            queue.update(active.withStatus(CommissionStatus.IN_PROGRESS));
            VillageSavedData.get(level).setDirty();
            inProgressMarked = true;
        }
        return Result.RUNNING;
    }

    private Result tickWriting(ServerLevel level, TownspersonMob npc,
                               Building workshop, ScribeCommission active) {
        writeTimer++;
        if (writeTimer % 30 == 0) npc.swing(InteractionHand.MAIN_HAND);
        if (writeTimer >= WRITE_TICKS) {
            ItemStack output = produceItem(npc, active);
            phase = Phase.DELIVERING;
            npc.setItemInHand(InteractionHand.MAIN_HAND, output);
            npc.setCurrentActivity("Delivering " + active.product().name().toLowerCase());

            SkillXp.award(npc, Skill.LITERACY, XP_PER_COMMISSION, level.getGameTime());
            CommissionQueue queue = VillageSavedData.get(level)
                    .getOrCreateCommissionQueue(workshop.getId());
            queue.update(active.withStatus(CommissionStatus.READY));
            VillageSavedData.get(level).setDirty();
        }
        return Result.RUNNING;
    }

    private Result tickDelivering(ServerLevel level, TownspersonMob npc,
                                  Building workshop, ScribeCommission active) {
        ItemStack output = npc.getMainHandItem().copy();
        if (output.isEmpty()) {
            return finishCommission(level, npc, workshop, active, true);
        }
        // Deliver to the player client (if online & near); else drop at the desk.
        if (active.clientIsPlayer()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(active.clientId());
            if (player != null && player.distanceToSqr(npc) < 64.0) {
                if (player.getInventory().add(output)) {
                    npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    return finishCommission(level, npc, workshop, active, true);
                }
            }
        }
        ItemEntity drop = new ItemEntity(level,
                npc.getX(), npc.getY() + 0.5, npc.getZ(), output);
        level.addFreshEntity(drop);
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return finishCommission(level, npc, workshop, active, true);
    }

    private Result finishCommission(ServerLevel level, TownspersonMob npc,
                                    Building workshop, ScribeCommission active,
                                    boolean delivered) {
        CommissionQueue queue = VillageSavedData.get(level)
                .getOrCreateCommissionQueue(workshop.getId());
        queue.update(active.withStatus(
                delivered ? CommissionStatus.DELIVERED : CommissionStatus.EXPIRED));
        VillageSavedData.get(level).setDirty();
        if (!npc.getMainHandItem().isEmpty()) {
            npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        npc.clearCurrentActivity();
        return Result.DONE;
    }

    /**
     * Precondition lost mid-run. Mirrors the legacy {@code stop()} clean abort:
     * an IN_PROGRESS commission is reverted to PENDING so a later run resumes
     * it; the hand item is cleared. The dispatcher's FAILED handling releases
     * the claim, so the source can re-emit the (now-PENDING) task next refresh.
     */
    private Result revertAndFail(ServerLevel level, TownspersonMob npc,
                                 Building workshop, UUID commissionId) {
        if (workshop != null && commissionId != null && inProgressMarked) {
            CommissionQueue queue = VillageSavedData.get(level)
                    .getOrCreateCommissionQueue(workshop.getId());
            queue.get(commissionId).ifPresent(c -> {
                if (c.status() == CommissionStatus.IN_PROGRESS) {
                    queue.update(c.withStatus(CommissionStatus.PENDING));
                    VillageSavedData.get(level).setDirty();
                }
            });
        }
        if (!npc.getMainHandItem().isEmpty()) {
            npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        npc.clearCurrentActivity();
        return Result.FAILED;
    }

    private ItemStack produceItem(TownspersonMob npc, ScribeCommission c) {
        String fromName = npc.getNpcName();
        String toName = c.targetId().map(UUID::toString).orElse("the bearer");
        return switch (c.product()) {
            case LETTER    -> ScribalItems.letter(c.content(), fromName, toName);
            case CONTRACT  -> ScribalItems.contract("Contract", c.content());
            case BOOK_COPY -> ScribalItems.book("Copy", fromName, c.content());
            case DECREE    -> ScribalItems.decree("Decree", c.content());
        };
    }

    private Optional<Building> workshop(ServerLevel level, TownspersonMob npc) {
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.SCRIBE_WORKSHOP);
    }

    static Optional<UUID> commissionIdOf(Task task) {
        if (!(task.objective() instanceof Objective.PerformService ps)) return Optional.empty();
        if (!ScribeService.KIND.equals(ps.kind())) return Optional.empty();
        return ps.ref().flatMap(ScribeWriteExecutor::parseUuid);
    }

    private static Optional<UUID> parseUuid(String s) {
        try {
            return Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(BlockPos.containing(x, y, z), (float) speed, 1);
    }
}
