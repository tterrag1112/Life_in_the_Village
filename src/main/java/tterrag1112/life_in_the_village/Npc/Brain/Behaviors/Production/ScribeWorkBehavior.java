package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
import tterrag1112.life_in_the_village.Npc.Tasks.TaskMigration;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.UUID;

/**
 * Spec line 106. Pulls the highest-priority commission from the
 * workshop's {@link CommissionQueue}, walks to the desk, "writes"
 * for {@link #WRITE_TICKS} ticks, mints a placeholder physical item
 * via {@link ScribalItems}, drops it for client pickup, and awards
 * LITERACY XP.
 */
public class ScribeWorkBehavior extends Behavior<TownspersonMob> {

    /** Time spent at the desk per commission. */
    public static final int WRITE_TICKS = 600;
    /** LITERACY XP per finished commission. */
    public static final int XP_PER_COMMISSION = 4;
    /** Distance squared inside which the scribe is "at the desk". */
    public static final double DESK_ARRIVAL_SQ = 4.0;

    private enum Phase { IDLE, WALKING, WRITING, DELIVERING }

    private TownspersonMob entity;

    public ScribeWorkBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private Phase phase = Phase.IDLE;
    private Building workshop;
    private BlockPos deskPos;
    private ScribeCommission active;
    private int writeTimer;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        // T3 — when the Task System owns the SCRIBE (flag on + migrated), the
        // legacy path yields so the brain falls through from WORK@0 to the
        // DoTaskBehavior dispatcher at WORK@1. Flag off OR not migrated => false,
        // so this behavior runs exactly as before (byte-identical).
        if (TaskMigration.ownsWork(entity.getProfession())) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;

        workshop = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.SCRIBE_WORKSHOP)
                .orElse(null);
        if (workshop == null) return false;

        CommissionQueue queue = VillageSavedData.get(level)
                .getOrCreateCommissionQueue(workshop.getId());
        active = queue.nextPending().orElse(null);
        if (active == null) return false;

        deskPos = workshop.getShape().getOrigin();
        return deskPos != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return phase != Phase.IDLE
                && entity.isWorkTime()
                && active != null;
    }

        @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.WALKING;
        writeTimer = 0;
        entity.setCurrentActivity("Heading to writing desk");
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                deskPos.getX() + 0.5, deskPos.getY(), deskPos.getZ() + 0.5, 1.0));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;

        switch (phase) {
            case WALKING    -> tickWalking();
            case WRITING    -> tickWriting(level);
            case DELIVERING -> tickDelivering(level);
            case IDLE       -> {}
        }
    }

    private void tickWalking() {
        double d2 = entity.distanceToSqr(
                deskPos.getX() + 0.5, deskPos.getY(), deskPos.getZ() + 0.5);
        if (d2 <= DESK_ARRIVAL_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.WRITING;
            entity.setCurrentActivity("Writing " + active.product().name().toLowerCase());
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FEATHER));
            // Mark commission in-progress so concurrent picks don't double-write.
            if (entity.level() instanceof ServerLevel sl) {
                CommissionQueue queue = VillageSavedData.get(sl)
                        .getOrCreateCommissionQueue(workshop.getId());
                queue.update(active = active.withStatus(CommissionStatus.IN_PROGRESS));
                VillageSavedData.get(sl).setDirty();
            }
        }
    }

    private void tickWriting(ServerLevel level) {
        writeTimer++;
        if (writeTimer % 30 == 0) entity.swing(InteractionHand.MAIN_HAND);
        if (writeTimer >= WRITE_TICKS) {
            // Produce the physical item and switch to delivery.
            ItemStack output = produceItem(active);
            phase = Phase.DELIVERING;
            entity.setItemInHand(InteractionHand.MAIN_HAND, output);
            entity.setCurrentActivity("Delivering " + active.product().name().toLowerCase());

            // Award LITERACY XP and bump the commission to READY.
            tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(entity, Skill.LITERACY, XP_PER_COMMISSION, level.getGameTime());
            CommissionQueue queue = VillageSavedData.get(level)
                    .getOrCreateCommissionQueue(workshop.getId());
            queue.update(active = active.withStatus(CommissionStatus.READY));
            VillageSavedData.get(level).setDirty();
        }
    }

    private void tickDelivering(ServerLevel level) {
        ItemStack output = entity.getMainHandItem().copy();
        if (output.isEmpty()) {
            finishCommission(level, true);
            return;
        }
        // Try to deliver to player client (if online); else drop into
        // the workshop storage path via floating-item drop at the desk.
        if (active.clientIsPlayer()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(active.clientId());
            if (player != null && player.distanceToSqr(entity) < 64.0) {
                if (player.getInventory().add(output)) {
                    entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    finishCommission(level, true);
                    return;
                }
            }
        }
        // Drop at desk for pickup.
        ItemEntity drop = new ItemEntity(level,
                entity.getX(), entity.getY() + 0.5, entity.getZ(), output);
        level.addFreshEntity(drop);
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        finishCommission(level, true);
    }

    private void finishCommission(ServerLevel level, boolean delivered) {
        if (active != null) {
            CommissionQueue queue = VillageSavedData.get(level)
                    .getOrCreateCommissionQueue(workshop.getId());
            queue.update(active.withStatus(
                    delivered ? CommissionStatus.DELIVERED : CommissionStatus.EXPIRED));
            VillageSavedData.get(level).setDirty();
        }
        active = null;
        phase = Phase.IDLE;
        writeTimer = 0;
        entity.clearCurrentActivity();
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        if (!entity.getMainHandItem().isEmpty()) {
            entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        if (entity.level() instanceof ServerLevel sl && active != null
                && phase != Phase.IDLE) {
            // Clean abort: revert in-progress to pending so another work
            // session can resume the order.
            CommissionQueue queue = VillageSavedData.get(sl)
                    .getOrCreateCommissionQueue(workshop.getId());
            if (active.status() == CommissionStatus.IN_PROGRESS) {
                queue.update(active.withStatus(CommissionStatus.PENDING));
                VillageSavedData.get(sl).setDirty();
            }
        }
        active = null;
        phase = Phase.IDLE;
        entity.clearCurrentActivity();
    }

    private ItemStack produceItem(ScribeCommission c) {
        String fromName = entity.getNpcName();
        String toName = c.targetId().map(UUID::toString).orElse("the bearer");
        return switch (c.product()) {
            case LETTER     -> ScribalItems.letter(c.content(), fromName, toName);
            case CONTRACT   -> ScribalItems.contract("Contract", c.content());
            case BOOK_COPY  -> ScribalItems.book("Copy", fromName, c.content());
            case DECREE     -> ScribalItems.decree("Decree", c.content());
        };
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
