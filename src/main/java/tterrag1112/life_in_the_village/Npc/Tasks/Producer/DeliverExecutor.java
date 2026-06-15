package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestBoard;
import tterrag1112.life_in_the_village.Guilds.Common.Requests.RequestCascadeLedger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.Optional;
import java.util.UUID;

/**
 * T5b-3 — the per-tick state machine for one delivery leg of a cascaded CRAFT
 * request. Take &rarr; walk &rarr; deposit &rarr; advance:
 * <ul>
 *   <li>take {@code min(stock, legRemaining)} of the item out of the producing
 *       NPC's work building;</li>
 *   <li>walk to the destination (guild hall or stockpile) the cascade
 *       resolved;</li>
 *   <li>deposit into the destination building
 *       ({@link BuildingStorageAccess#storeWithFallback});</li>
 *   <li>advance {@link RequestBoard#updateProgress} by the deposited amount and
 *       bump the {@link RequestCascadeLedger} leg's deliveredSoFar.</li>
 * </ul>
 *
 * <p>Delivery gates fulfillment: request progress moves on DEPOSIT, not on
 * craft. The existing {@code RequestBoardTicker} pass-1 settles when
 * {@code progress.isComplete()}.</p>
 *
 * <p>One instance per claimed deliver task; small phase state is per-run.</p>
 */
public final class DeliverExecutor implements TaskExecutor {

    /** Arrival tolerance (squared) at the destination. */
    private static final double ARRIVAL_SQ = 9.0;

    private enum Phase { TAKE, WALKING, DEPOSIT }

    private Phase phase = Phase.TAKE;
    private boolean started;
    private BlockPos destPos;
    private int carried;     // units currently in transit
    private ItemStack carriedStack = ItemStack.EMPTY;

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel level = ctx.level();
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;
        if (!(task.objective() instanceof Objective.Deliver deliver)) return Result.FAILED;

        Building source = DeliverFulfillment.sourceBuilding(level, npc).orElse(null);
        if (source == null) return Result.FAILED;

        Building destBuilding = destinationBuilding(level, deliver.destination()).orElse(null);
        if (destBuilding == null) {
            // Destination unloaded mid-run. If we already pulled items out of the
            // source, drop them at the NPC rather than silently delete them.
            dropCarried(level, npc);
            return Result.FAILED;
        }

        RequestCascadeLedger ledger = RequestCascadeLedger.get(level);
        RequestCascadeLedger.Entry leg = ledger.get(task.id().value()).orElse(null);
        // If the ledger has no record (manual/legacy Deliver), fall back to the
        // objective qty as the leg amount; progress just won't route anywhere.
        int legRemaining = leg != null ? leg.remaining() : deliver.qty();
        if (legRemaining <= 0) return Result.DONE; // nothing owed

        if (!started) {
            // Take min(stock, legRemaining) from the source building NOW so a
            // concurrent producer batch doesn't get re-delivered.
            int stock = BuildingStorageAccess.countItem(level, source, deliver.item());
            int take = Math.min(stock, legRemaining);
            if (take <= 0) return Result.FAILED; // nothing to carry yet
            if (!BuildingStorageAccess.takeItem(level, source, deliver.item(), take)) {
                return Result.FAILED;
            }
            carried = take;
            carriedStack = new ItemStack(deliver.item(), take);
            destPos = deliver.destination().pos();
            phase = Phase.WALKING;
            started = true;
            npc.setCurrentActivity("Delivering to request hall");
            npc.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(
                    destPos, 1.0f, 1));
            return Result.RUNNING;
        }

        return switch (phase) {
            case WALKING -> tickWalking(level, npc);
            case DEPOSIT -> tickDeposit(level, npc, deliver, destBuilding, ledger, task);
            case TAKE -> Result.FAILED; // unreachable post-start
        };
    }

    private Result tickWalking(ServerLevel level, TownspersonMob npc) {
        double d2 = npc.distanceToSqr(
                destPos.getX() + 0.5, destPos.getY(), destPos.getZ() + 0.5);
        if (d2 <= ARRIVAL_SQ) {
            npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.DEPOSIT;
        }
        return Result.RUNNING;
    }

    private Result tickDeposit(ServerLevel level, TownspersonMob npc, Objective.Deliver deliver,
                               Building destBuilding, RequestCascadeLedger ledger, Task task) {
        if (carriedStack.isEmpty() || carried <= 0) {
            return Result.DONE;
        }
        // Deposit the carried stack into the destination. Items that don't fit
        // are returned to the NPC's hand-drop fallback so they aren't lost.
        SimpleContainer overflow = new SimpleContainer(carriedStack.getCount());
        BuildingStorageAccess.storeWithFallback(level, destBuilding, carriedStack.copy(), overflow);
        int notStored = 0;
        for (int i = 0; i < overflow.getContainerSize(); i++) {
            ItemStack s = overflow.getItem(i);
            if (!s.isEmpty()) notStored += s.getCount();
        }
        int deposited = carried - notStored;

        if (deposited > 0) {
            // Advance the originating request by the deposited amount.
            RequestCascadeLedger.Entry leg = ledger.get(task.id().value()).orElse(null);
            if (leg != null) {
                ledger.addDelivered(task.id().value(), deposited);
                UUID requestId = leg.requestId();
                // Sum every leg's deliveredSoFar so progress reflects the whole
                // request (all businesses' shares), not just this leg.
                int total = 0;
                for (RequestCascadeLedger.Entry e : ledger.legsForRequest(requestId)) {
                    total += e.deliveredSoFar();
                }
                RequestBoard.get(level).updateProgress(requestId, total);
            }
        }

        // Drop any overflow at the destination so it isn't destroyed.
        for (int i = 0; i < overflow.getContainerSize(); i++) {
            ItemStack s = overflow.getItem(i);
            if (s.isEmpty()) continue;
            net.minecraft.world.entity.item.ItemEntity drop =
                    new net.minecraft.world.entity.item.ItemEntity(level,
                            npc.getX(), npc.getY() + 0.5, npc.getZ(), s.copy());
            level.addFreshEntity(drop);
        }
        carriedStack = ItemStack.EMPTY;
        carried = 0;
        npc.clearCurrentActivity();
        return Result.DONE;
    }

    /** Drop any in-transit stack at the NPC so a mid-run abort never destroys
     *  items already taken from the source building. */
    private void dropCarried(ServerLevel level, TownspersonMob npc) {
        if (carriedStack.isEmpty() || carried <= 0) return;
        net.minecraft.world.entity.item.ItemEntity drop =
                new net.minecraft.world.entity.item.ItemEntity(level,
                        npc.getX(), npc.getY() + 0.5, npc.getZ(), carriedStack.copy());
        level.addFreshEntity(drop);
        carriedStack = ItemStack.EMPTY;
        carried = 0;
    }

    /** Resolve the destination {@link GlobalPos} to a {@link Building} in the
     *  village data (the hall / stockpile the cascade targeted). */
    static Optional<Building> destinationBuilding(ServerLevel level, GlobalPos dest) {
        if (dest == null) return Optional.empty();
        return VillageSavedData.get(level).getBuildingAt(dest.pos());
    }
}
