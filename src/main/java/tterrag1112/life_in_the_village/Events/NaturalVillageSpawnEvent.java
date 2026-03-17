package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.VillageSpawner;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

import java.util.Random;

@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class NaturalVillageSpawnEvent {

    // Only attempt spawning in chunks that are multiples of this distance
    private static final int SPAWN_GRID_SIZE = 64; // chunks
    // Probability of a spawn attempt succeeding at a valid grid point
    private static final float SPAWN_CHANCE = 0.15f;

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.isClientSide()) return;

        LevelChunk chunk = (LevelChunk) event.getChunk();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Only consider chunks on the spawn grid
        if (chunkX % SPAWN_GRID_SIZE != 0) return;
        if (chunkZ % SPAWN_GRID_SIZE != 0) return;

        // Use chunk coords as seed for deterministic spawning
        Random random = new Random(
                level.getSeed() ^ ((long) chunkX * 341873128712L)
                        ^ ((long) chunkZ * 132897987541L)
        );

        if (random.nextFloat() > SPAWN_CHANCE) return;

        BlockPos origin = new BlockPos(
                chunkX * 16 + 8,
                64, // will be adjusted to surface
                chunkZ * 16 + 8
        );

        if (!VillageSpawner.isFarEnoughFromExistingVillages(level, origin)) return;

        // Pick a random village type
        String[] types = VillageTypeRegistry.INSTANCE
                .getAvailableTypes().toArray(new String[0]);
        if (types.length == 0) return;

        String type = types[random.nextInt(types.length)];
        String name = generateVillageName(random);

        System.out.println("NaturalVillageSpawn: attempting spawn of '"
                + name + "' at " + origin);

        VillageSpawner.spawnVillage(level, origin, type, name);
    }

    private static final String[] PREFIXES = {
            "Oak", "Stone", "River", "Hill", "North", "South",
            "East", "West", "Old", "New", "High", "Low"
    };

    private static final String[] SUFFIXES = {
            "haven", "wick", "ford", "bury", "ton", "stead",
            "field", "wood", "bridge", "hollow", "dale", "mill"
    };

    private static String generateVillageName(Random random) {
        return PREFIXES[random.nextInt(PREFIXES.length)]
                + SUFFIXES[random.nextInt(SUFFIXES.length)];
    }
}
