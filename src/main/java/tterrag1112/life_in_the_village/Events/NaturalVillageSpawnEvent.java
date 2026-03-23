// src/main/java/tterrag1112/life_in_the_village/Events/NaturalVillageSpawnEvent.java
package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Village.VillageSpawner;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

import java.util.*;

/**
 * Handles natural village generation as the world is explored.
 *
 * <h3>Design</h3>
 * Villages cannot be spawned directly inside {@link ChunkEvent.Load} because
 * {@link tterrag1112.life_in_the_village.Village.VillageSpawner} loads
 * additional chunks for site preparation, which would cause re-entrant chunk
 * generation and potential deadlocks. Instead, spawn requests are queued in
 * {@code ChunkEvent.Load} and drained one-at-a-time in
 * {@link ServerTickEvent.Post} when the server is in a stable state.
 *
 * <h3>Grid model</h3>
 * Only chunk coordinates that are exact multiples of {@link #SPAWN_GRID_SIZE}
 * are considered. Each grid point has a deterministic RNG seeded from the
 * world seed + chunk coords, so the same point always produces the same
 * spawn decision across server restarts.
 *
 * <h3>Biome-aware type selection</h3>
 * The village type is chosen based on the dominant biome category at the
 * spawn point, biasing toward village types that make ecological sense
 * (farming villages in plains, riverside towns near rivers, etc.).
 * Falls back to "default" when no biome match is found.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public class NaturalVillageSpawnEvent {

    // Grid spacing in chunks (64 chunks = 1024 blocks)
    private static final int   SPAWN_GRID_SIZE  = 64;
    // Probability that a valid grid point actually gets a village
    private static final float SPAWN_CHANCE     = 0.20f;
    // Only process one queued spawn per server tick to avoid lag spikes
    private static final int   SPAWNS_PER_TICK  = 1;

    // ── Pending spawn queue ───────────────────────────────────────────────────
    // Populated during ChunkEvent.Load (safe — just bookkeeping).
    // Drained during ServerTickEvent.Post (safe — server is idle between ticks).
    private static final Queue<PendingSpawn> pendingSpawns = new ArrayDeque<>();
    // Tracks grid cells already attempted this session (prevents duplicate queuing).
    private static final Set<Long> attemptedCells = new HashSet<>();

    // =========================================================================
    // Phase 1 — queue spawn requests during chunk load (lightweight)
    // =========================================================================

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.isClientSide()) return;

        // Only generate villages in the overworld
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        // VillageTypeRegistry must have loaded types before we can spawn
        if (VillageTypeRegistry.INSTANCE.getAvailableTypes().isEmpty()) return;

        if (!(event.getChunk() instanceof LevelChunk chunk)) return;
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Only grid-aligned chunks
        if (chunkX % SPAWN_GRID_SIZE != 0) return;
        if (chunkZ % SPAWN_GRID_SIZE != 0) return;

        long cellKey = cellKey(chunkX, chunkZ);
        if (attemptedCells.contains(cellKey)) return;

        // Deterministic decision using world seed + chunk coords
        Random rng = new Random(
                level.getSeed()
                        ^ ((long) chunkX * 341873128712L)
                        ^ ((long) chunkZ * 132897987541L));

        if (rng.nextFloat() > SPAWN_CHANCE) {
            attemptedCells.add(cellKey); // decided: no spawn at this cell
            return;
        }

        // Queue the spawn for processing in ServerTickEvent
        int worldX = chunkX * 16 + 8;
        int worldZ = chunkZ * 16 + 8;
        pendingSpawns.add(new PendingSpawn(level, worldX, worldZ, rng));
        attemptedCells.add(cellKey);
    }

    // =========================================================================
    // Phase 2 — process queued spawns during stable server tick
    // =========================================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int processed = 0;
        while (!pendingSpawns.isEmpty() && processed < SPAWNS_PER_TICK) {
            PendingSpawn spawn = pendingSpawns.poll();
            processSpawn(spawn);
            processed++;
        }
    }

    private static void processSpawn(PendingSpawn spawn) {
        ServerLevel level = spawn.level;

        // Abort if level was unloaded between queue and processing
        if (level.getServer() == null) return;

        // Re-check minimum distance now that more villages may have spawned
        // since the chunk load event
        BlockPos origin = new BlockPos(spawn.worldX, 64, spawn.worldZ);
        if (!VillageSpawner.isFarEnoughFromExistingVillages(level, origin)) return;

        // Surface Y — heightmap is valid here because we're not inside a chunk load
        int surfY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                spawn.worldX, spawn.worldZ);
        if (surfY <= level.getMinY() + 8) return; // ocean floor / void

        BlockPos spawnPos = new BlockPos(spawn.worldX, surfY, spawn.worldZ);

        // Pick a biome-appropriate village type
        String type = chooseBiomeAwareType(level, spawnPos, spawn.rng);
        String name = generateName(spawn.rng);

        System.out.println("NaturalVillageSpawn: spawning '"
                + name + "' (" + type + ") at " + spawnPos.toShortString());

        VillageSpawner.spawnVillage(level, spawnPos, type, name);
    }

    // =========================================================================
    // Biome-aware type selection
    // =========================================================================

    /**
     * Chooses a village type that fits the biome at the given position.
     *
     * <p>The selection order is:
     * <ol>
     *   <li>Water nearby → {@code riverside_town} if the type exists</li>
     *   <li>Snowy/cold biome → {@code farming_village} (potatoes)</li>
     *   <li>Desert/savanna → {@code trade_hub} (merchants, not farmers)</li>
     *   <li>Forest/taiga → {@code mining_camp} or {@code default}</li>
     *   <li>Plains/meadow → {@code farming_village}</li>
     *   <li>Otherwise → weighted random from available types</li>
     * </ol>
     */
    private static String chooseBiomeAwareType(ServerLevel level,
                                               BlockPos pos,
                                               Random rng) {
        Set<String> available = VillageTypeRegistry.INSTANCE.getAvailableTypes();

        // ── Water check ───────────────────────────────────────────────────────
        if (hasWaterNearby(level, pos, 80) && available.contains("riverside_town")) {
            return "riverside_town";
        }

        // ── Biome check ───────────────────────────────────────────────────────
        ResourceKey<Biome> biomeKey = level.getBiome(pos).unwrapKey().orElse(null);
        if (biomeKey != null) {
            String path = biomeKey.registry().getPath();

            boolean isSnowy   = path.contains("snowy") || path.contains("frozen") || path.contains("ice");
            boolean isDesert  = path.contains("desert") || path.contains("badlands") || path.contains("savanna");
            boolean isForest  = path.contains("forest") || path.contains("taiga");
            boolean isPlains  = path.contains("plains") || path.contains("meadow") || path.contains("steppe");
            boolean isOcean   = path.contains("ocean") || path.contains("beach");

            if (isOcean) return null; // abort — don't spawn in oceans

            if (isSnowy && available.contains("farming_village"))
                return "farming_village";
            if (isDesert && available.contains("trade_hub"))
                return "trade_hub";
            if (isForest && available.contains("mining_camp"))
                return rng.nextBoolean() ? "mining_camp" : "default";
            if (isPlains && available.contains("farming_village"))
                return rng.nextFloat() < 0.6f ? "farming_village" : "default";
        }

        // ── Weighted random fallback ──────────────────────────────────────────
        // "default" is 3× more likely than any specialist type to keep
        // the world feeling like a normal village landscape
        List<String> weightedPool = new ArrayList<>();
        for (String t : available) {
            weightedPool.add(t);
            if (t.equals("default")) {
                weightedPool.add(t);
                weightedPool.add(t);
            }
        }
        return weightedPool.get(rng.nextInt(weightedPool.size()));
    }

    private static boolean hasWaterNearby(ServerLevel level, BlockPos pos, int radius) {
        // Sample 8 radial points at the given radius
        for (int i = 0; i < 8; i++) {
            double angle = 2 * Math.PI * i / 8;
            int sx = pos.getX() + (int)(Math.cos(angle) * radius);
            int sz = pos.getZ() + (int)(Math.sin(angle) * radius);
            int sy = level.getHeight(Heightmap.Types.WORLD_SURFACE, sx, sz);
            // Water surface is below WORLD_SURFACE but at or above
            // MOTION_BLOCKING_NO_LEAVES — detect by checking block state
            BlockPos check = new BlockPos(sx, sy - 1, sz);
            if (level.isWaterAt(check) || level.isWaterAt(check.above())) return true;
        }
        return false;
    }

    // =========================================================================
    // Name generation
    // =========================================================================

    private static final String[] PREFIXES = {
            "Oak", "Stone", "River", "Hill", "North", "South",
            "East", "West", "Old", "New", "High", "Low",
            "Green", "Red", "White", "Black", "Bright", "Deep",
            "Far", "Near", "Golden", "Silver"
    };

    private static final String[] SUFFIXES = {
            "haven", "wick", "ford", "bury", "ton", "stead",
            "field", "wood", "bridge", "hollow", "dale", "mill",
            "gate", "port", "keep", "moor", "hurst", "cross",
            "croft", "ham", "lea", "worth"
    };

    private static String generateName(Random rng) {
        return PREFIXES[rng.nextInt(PREFIXES.length)]
                + SUFFIXES[rng.nextInt(SUFFIXES.length)];
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static long cellKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    private record PendingSpawn(ServerLevel level, int worldX, int worldZ, Random rng) {}
}