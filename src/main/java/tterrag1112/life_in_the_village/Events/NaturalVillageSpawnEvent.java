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
import tterrag1112.life_in_the_village.Village.Planning.VillagePlanHelper;
import tterrag1112.life_in_the_village.Village.VillageSpawner;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

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
        if (level.getServer() == null) return;

        // Re-check minimum distance now that more villages may have spawned
        BlockPos checkPos = new BlockPos(spawn.worldX, 64, spawn.worldZ);
        if (!VillageSpawner.isFarEnoughFromExistingVillages(level, checkPos)) return;

        // ── Atlas query for site validity and biome ──────────────────────────
        WorldAtlas atlas =
                WorldAtlas.get(level);
        AtlasCell cell =
                atlas.ensureCell(level, spawn.worldX, spawn.worldZ);

        if (!cell.isBuildable()) return;          // ocean/void
        if (cell.isSteep())      return;          // too rough
        if (cell.centerY() <= level.getMinY() + 8) return;

        BlockPos spawnPos = new BlockPos(
                cell.blockCenterX(), cell.centerY(), cell.blockCenterZ());

        String type = chooseTypeFromCell(cell, spawn.rng);
        if (type == null) return;
        String name = generateName(spawn.rng);

        System.out.println("NaturalVillageSpawn: spawning '" + name
                + "' (" + type + ", " + cell.category() + ") at "
                + spawnPos.toShortString());

        VillagePlanHelper.planVillage(
                level,
                tterrag1112.life_in_the_village.Networking.VillageSavedData.get(level),
                spawnPos, type, name);    }

    // =========================================================================
    // Atlas-cell-based type selection
    // =========================================================================

    /**
     * Chooses a village type appropriate for the given atlas cell's
     * biome category and water adjacency. Returns null to abort the spawn.
     */
    private static String chooseTypeFromCell(
            AtlasCell cell,
            java.util.Random rng) {

        java.util.Set<String> available =
                VillageTypeRegistry.INSTANCE.getAvailableTypes();

        // ── Water adjacency: prefer riverside type if any water touches us ───
        if ((cell.isCoast() || cell.isRiverAdj())
                && available.contains("riverside_town")) {
            return "riverside_town";
        }

        // ── Category preferences ─────────────────────────────────────────────
        switch (cell.category()) {
            case OCEAN, VOID -> { return null; }
            case SNOWY -> {
                if (available.contains("farming_village")) return "farming_village";
            }
            case DESERT, SAVANNA -> {
                if (available.contains("trade_hub")) return "trade_hub";
            }
            case FOREST -> {
                if (available.contains("mining_camp"))
                    return rng.nextBoolean() ? "mining_camp" : "default";
            }
            case PLAINS -> {
                if (available.contains("farming_village"))
                    return rng.nextFloat() < 0.6f ? "farming_village" : "default";
            }
            default -> {}
        }

        // ── Weighted random fallback (default 3× any specialist) ─────────────
        java.util.List<String> weightedPool = new java.util.ArrayList<>();
        for (String t : available) {
            weightedPool.add(t);
            if (t.equals("default")) {
                weightedPool.add(t);
                weightedPool.add(t);
            }
        }
        return weightedPool.get(rng.nextInt(weightedPool.size()));
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