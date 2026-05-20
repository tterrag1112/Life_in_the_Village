package tterrag1112.life_in_the_village.Npc.Brain.Sensors;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Village.Building;

import java.util.Set;

/**
 * Writes SHELTER_TARGET when the level is raining/thundering AND the
 * entity is currently exposed (heightmap top is at or below the
 * entity, meaning sky is clear above). Samples ~16 nearby positions
 * within 12 blocks; "sheltered" = either inside a known building
 * footprint OR sky-blocked.
 *
 * <p>When not raining, or already sheltered, erases SHELTER_TARGET.
 */
public class WeatherShelterSensor extends Sensor<TownspersonMob> {

    private static final int INTERVAL_TICKS = 60;
    private static final int SCAN_RADIUS = 12;
    private static final int SAMPLES = 16;

    public WeatherShelterSensor() {
        super(INTERVAL_TICKS);
    }

    @Override
    public Set<MemoryModuleType<?>> requires() {
        return Set.of(NpcMemoryTypes.SHELTER_TARGET.get());
    }

    @Override
    protected void doTick(ServerLevel level, TownspersonMob entity) {
        Brain<?> brain = entity.getBrain();

        if (!level.isRaining() && !level.isThundering()) {
            brain.eraseMemory(NpcMemoryTypes.SHELTER_TARGET.get());
            return;
        }

        BlockPos selfPos = entity.blockPosition();
        if (isSheltered(level, selfPos)) {
            brain.eraseMemory(NpcMemoryTypes.SHELTER_TARGET.get());
            return;
        }

        BlockPos best = pickShelterSpot(level, selfPos, entity.getRandom());
        if (best != null) {
            brain.setMemory(NpcMemoryTypes.SHELTER_TARGET.get(), best);
        } else {
            brain.eraseMemory(NpcMemoryTypes.SHELTER_TARGET.get());
        }
    }

    /** Sheltered = sky-blocked above (heightmap above us) OR inside a
     *  known building footprint. */
    private static boolean isSheltered(ServerLevel level, BlockPos pos) {
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        if (topY > pos.getY() + 1) return true;
        return insideAnyBuilding(level, pos);
    }

    private static boolean insideAnyBuilding(ServerLevel level, BlockPos pos) {
        VillageSavedData data = VillageSavedData.get(level);
        for (Building b : data.getAllBuildings()) {
            if (b.getShape().contains(pos)) return true;
        }
        return false;
    }

    /** Samples random offsets in a 12-block box; returns closest walkable
     *  shelter position (sky-blocked or inside any building), or null. */
    private static BlockPos pickShelterSpot(ServerLevel level, BlockPos origin, RandomSource rng) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            int dx = rng.nextInt(SCAN_RADIUS * 2 + 1) - SCAN_RADIUS;
            int dz = rng.nextInt(SCAN_RADIUS * 2 + 1) - SCAN_RADIUS;
            int dy = rng.nextInt(5) - 2;
            BlockPos stand = origin.offset(dx, dy, dz);
            if (!isSheltered(level, stand)) continue;
            if (!level.getBlockState(stand.below()).isSolid()) continue;
            if (!level.getBlockState(stand).isAir()) continue;
            if (!level.getBlockState(stand.above()).isAir()) continue;
            double dsq = stand.distSqr(origin);
            if (dsq < bestDistSq) {
                bestDistSq = dsq;
                best = stand.immutable();
            }
        }
        return best;
    }
}
