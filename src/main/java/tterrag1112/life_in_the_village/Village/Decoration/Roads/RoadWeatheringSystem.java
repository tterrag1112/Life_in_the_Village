package tterrag1112.life_in_the_village.Village.Decoration.Roads;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import tterrag1112.life_in_the_village.Events.TickSubsystem;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;

/**
 * Tick subsystem that gradually weathers obsolete road segments.
 *
 * <h3>How it works</h3>
 * Each in-game day, for each village, this system:
 * <ol>
 *   <li>Finds all VillagePaths marked as {@code obsolete}</li>
 *   <li>For each, randomly weathers 10-20% of remaining road blocks
 *       by one stage (stone → cobble → gravel → dirt → grass)</li>
 *   <li>Advances the path's {@code weatherProgress} counter</li>
 *   <li>When fully weathered (progress ≥ 1.0), removes the VillagePath
 *       record entirely</li>
 * </ol>
 *
 * A road takes ~5-7 in-game days to fully weather away, creating a
 * gradual visual transition that players can observe.
 *
 * <h3>Registration</h3>
 * Register in {@code TickSubsystemRegistry.registerDefaults()}:
 * <pre>
 * register(new RoadWeatheringSystem());
 * </pre>
 */
public class RoadWeatheringSystem implements TickSubsystem {

    /** Weather 15% of remaining road blocks per pass. */
    private static final float BLOCKS_PER_PASS = 0.15f;
    /** Progress per pass — reaches 1.0 after ~7 passes. */
    private static final float PROGRESS_PER_PASS = 0.15f;

    @Override
    public String name()     { return "road_weathering"; }
    @Override public int interval() { return 1; }  // self-gated to once/day
    @Override public int priority() { return 250; }

    @Override
    public void tick(TickContext ctx) {
        long tick = ctx.tick();
        VillageSavedData vdata = ctx.villageData();
        ServerLevel level = ctx.level();
        RandomSource random = level.getRandom();

        for (Village village : vdata.getAllVillages()) {
            // Stagger per village, once per day
            long offset = Math.abs(village.getName().hashCode() % 24000L);
            if ((tick + offset + 6000L) % 24000L != 0) continue;

            weatherObsoletePaths(level, village, vdata, random);
        }
    }

    private void weatherObsoletePaths(ServerLevel level,
                                      Village village,
                                      VillageSavedData data,
                                      RandomSource random) {
        List<VillagePath> toRemove = new ArrayList<>();

        for (VillagePath path : data.getPathsForVillage(village.getId())) {
            if (!path.isObsolete()) continue;

            List<BlockPos> blocks = path.getBlocks();
            if (blocks.isEmpty()) {
                toRemove.add(path);
                continue;
            }

            // Weather a fraction of blocks this pass
            int count = Math.max(1, (int)(blocks.size() * BLOCKS_PER_PASS));
            for (int i = 0; i < count; i++) {
                int idx = random.nextInt(blocks.size());
                BlockPos pos = blocks.get(idx);

                // Only weather if chunk is loaded
                if (!level.isLoaded(pos)) continue;

                VillageRoadNetwork.weatherBlock(level, pos);
            }

            // Advance progress
            boolean done = path.advanceWeathering(PROGRESS_PER_PASS);
            if (done) {
                toRemove.add(path);
            }

            data.setDirty();
        }

        // Clean up fully weathered paths
        for (VillagePath path : toRemove) {
            data.removeVillagePath(path.getId());
        }
    }
}