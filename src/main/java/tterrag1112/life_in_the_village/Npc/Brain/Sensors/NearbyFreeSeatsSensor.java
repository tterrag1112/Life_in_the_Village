package tterrag1112.life_in_the_village.Npc.Brain.Sensors;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.SeatOccupancyRegistry;

import java.util.Set;

/**
 * Scans for a seat block within ~12 blocks and writes NEAREST_FREE_SEAT.
 *
 * <p>Seat definition (Phase 6.1.b — chosen to match the existing
 * {@code ElderlyRelaxGoal} pattern for visual consistency): any
 * {@link SlabBlock} or {@link StairBlock} instance, or any state with
 * {@link BlockTags#WOODEN_SLABS} / {@link BlockTags#WOODEN_STAIRS}.
 * The block above must be air (somewhere to put the entity). A
 * dedicated {@code lit:sittable} tag is deferred — when richer
 * mod-specific furniture lands it can become a tag-driven sensor.
 *
 * <p>Free = the seat's {@link SeatOccupancyRegistry} slot is unclaimed
 * or claimed by this entity (so a sitting NPC's sensor doesn't
 * immediately forget its own seat).
 */
public class NearbyFreeSeatsSensor extends Sensor<TownspersonMob> {

    private static final int INTERVAL_TICKS = 80;
    private static final int SCAN_RADIUS = 12;

    public NearbyFreeSeatsSensor() {
        super(INTERVAL_TICKS);
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Set.of(NpcMemoryTypes.NEAREST_FREE_SEAT.get());
    }

    @Override
    protected void doTick(ServerLevel level, TownspersonMob entity) {
        Brain<?> brain = entity.getBrain();
        BlockPos origin = entity.blockPosition();

        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-SCAN_RADIUS, -2, -SCAN_RADIUS),
                origin.offset(SCAN_RADIUS,  2, SCAN_RADIUS))) {
            if (!isSeat(level.getBlockState(pos))) continue;
            if (!level.getBlockState(pos.above()).isAir()) continue;
            BlockPos seat = pos.immutable();
            if (!SeatOccupancyRegistry.isFreeFor(seat, entity.getUUID())) continue;
            double dsq = seat.distSqr(origin);
            if (dsq < bestDistSq) {
                bestDistSq = dsq;
                best = seat;
            }
        }

        if (best != null) {
            brain.setMemory(NpcMemoryTypes.NEAREST_FREE_SEAT.get(), best);
        } else {
            brain.eraseMemory(NpcMemoryTypes.NEAREST_FREE_SEAT.get());
        }
    }

    private static boolean isSeat(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                || state.getBlock() instanceof StairBlock
                || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_STAIRS);
    }
}
