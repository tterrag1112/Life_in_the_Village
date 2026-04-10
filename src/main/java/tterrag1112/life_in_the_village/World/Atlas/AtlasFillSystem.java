package tterrag1112.life_in_the_village.World.Atlas;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Events.TickSubsystem;

import java.util.*;

/**
 * Lazily fills the {@link WorldAtlas} around active players.
 *
 * <h3>Strategy</h3>
 * Every {@link #interval()} ticks, this system:
 * <ol>
 *   <li>For each player in the overworld, finds atlas cells within
 *       {@link #FILL_RADIUS_CELLS} of the player that are not yet
 *       sampled.</li>
 *   <li>Adds the missing cell coordinates to {@code pending}, a
 *       LinkedHashSet that preserves insertion order and dedupes.</li>
 *   <li>Drains up to {@link #CELLS_PER_TICK} from {@code pending} and
 *       samples them via {@link AtlasSampler#sampleCell}.</li>
 * </ol>
 *
 * <h3>Performance budget</h3>
 * Each {@code sampleCell} call performs 5 height queries and 1 biome
 * query — typically &lt;100 µs total on a modern machine. With a
 * budget of {@link #CELLS_PER_TICK} = 16 cells per tick at interval 4,
 * the system uses ~400 µs per tick (4 cells/tick avg) — well within
 * a single-millisecond slice of the 50 ms server tick budget.
 *
 * <h3>Why not a worker thread</h3>
 * NeoForge's {@code ChunkGenerator.getBaseHeight} is internally
 * thread-safe (it only reads the dimension's RandomState) but moving
 * the sampling off-thread complicates the SavedData mutation path.
 * Keeping it on the server tick is simpler for Phase 1; if profiling
 * shows it as a bottleneck the work can be moved to a
 * ForkJoinPool.commonPool task with a result-queue handoff.
 *
 * <h3>Multi-dimension</h3>
 * For now this only fills the overworld. Other dimensions can be
 * added by checking each player's level and maintaining one pending
 * queue per dimension.
 */
public class AtlasFillSystem implements TickSubsystem {

    /** Cells around each player to keep filled. 32 cells = 2048 blocks. */
    private static final int FILL_RADIUS_CELLS = 32;

    /** Maximum cells to sample in a single tick. */
    private static final int CELLS_PER_TICK    = 16;

    /** Pending cells to sample, deduplicated and FIFO-ordered. */
    private final LinkedHashSet<Long> pending = new LinkedHashSet<>();

    @Override public String name()     { return "atlas_fill"; }
    @Override public int    interval() { return 4; }   // 5 Hz
    @Override public int    priority() { return 50; }  // run early in the tick

    @Override
    public void tick(TickContext ctx) {
        ServerLevel level = ctx.level();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;

        WorldAtlas atlas = WorldAtlas.get(level);

        // ── Step 1: queue missing cells around each player ───────────────────
        for (ServerPlayer player : level.players()) {
            int pcx = WorldAtlas.blockToCell(player.blockPosition().getX());
            int pcz = WorldAtlas.blockToCell(player.blockPosition().getZ());

            // Spiral outward from the player so closer cells fill first.
            // Cheap unordered iteration is fine — LinkedHashSet preserves
            // first-seen ordering across multiple players.
            for (int r = 0; r <= FILL_RADIUS_CELLS; r++) {
                queueRing(atlas, pcx, pcz, r);
                // Early break — don't enqueue more than we can drain in
                // a few seconds, otherwise the queue grows unboundedly
                // when a player teleports far away.
                if (pending.size() >= CELLS_PER_TICK * 8) break;
            }
        }

        // ── Step 2: drain up to CELLS_PER_TICK from the queue ────────────────
        int processed = 0;
        Iterator<Long> it = pending.iterator();
        while (it.hasNext() && processed < CELLS_PER_TICK) {
            long key = it.next();
            it.remove();

            int cellX = AtlasCell.unpackX(key);
            int cellZ = AtlasCell.unpackZ(key);

            // Skip if it was filled by something else in the meantime
            if (atlas.isFilled(cellX, cellZ)) continue;

            try {
                AtlasCell cell = AtlasSampler.sampleCell(level, cellX, cellZ);
                atlas.addCell(cell);
                processed++;
            } catch (Exception e) {
                // Sampling can throw if the dimension is unloading;
                // swallow and continue so one bad cell doesn't kill the queue
            }
        }
    }

    /** Queues every unfilled cell on the ring at radius r around (pcx, pcz). */
    private void queueRing(WorldAtlas atlas, int pcx, int pcz, int r) {
        if (r == 0) {
            tryQueue(atlas, pcx, pcz);
            return;
        }
        // Top and bottom edges
        for (int dx = -r; dx <= r; dx++) {
            tryQueue(atlas, pcx + dx, pcz - r);
            tryQueue(atlas, pcx + dx, pcz + r);
        }
        // Left and right edges (excluding corners already done)
        for (int dz = -r + 1; dz <= r - 1; dz++) {
            tryQueue(atlas, pcx - r, pcz + dz);
            tryQueue(atlas, pcx + r, pcz + dz);
        }
    }

    private void tryQueue(WorldAtlas atlas, int cellX, int cellZ) {
        if (atlas.isFilled(cellX, cellZ)) return;
        pending.add(AtlasCell.packKey(cellX, cellZ));
    }
}