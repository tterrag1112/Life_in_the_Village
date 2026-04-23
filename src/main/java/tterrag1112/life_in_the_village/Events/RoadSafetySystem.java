package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadProximityCache;
import tterrag1112.life_in_the_village.Village.Roads.Travel.RoadProximityChecker;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Suppresses natural hostile-mob spawns within the safety corridor of
 * maintained roads during daytime.
 *
 * <h3>Suppression rules</h3>
 * <ol>
 *   <li>Only {@link Monster} entities are affected. Passive mobs are never
 *       suppressed — animals and villagers near roads enhance the world-feel.</li>
 *   <li>Only {@link EntitySpawnReason#NATURAL} spawns are checked. Spawner blocks,
 *       structure spawns, and future programmatic road events all bypass this
 *       handler entirely.</li>
 *   <li>Suppression fires only when {@code level.isDay()} returns {@code true}.
 *       At night — and during thunderstorms, which the vanilla method treats as
 *       dark — roads provide no safety benefit. Night remains dangerous.</li>
 *   <li>A road counts as "maintained" only if {@code maintenance >= 30} or it
 *       is a GREAT_ROAD (invariant 3). Overgrown roads below that threshold
 *       provide no suppression, aligning the mechanical decay with the visual
 *       overgrowth from Phase 6b.</li>
 * </ol>
 *
 * <h3>Suppression chances</h3>
 * <pre>
 * GREAT_ROAD  0.85 — 85% of natural hostile spawns cancelled (~1 in 7 succeed)
 * TRUNK       0.70
 * CONNECTOR   0.50
 * LOCAL       0.30 — minor safety; wilderness character still present
 * </pre>
 *
 * <h3>Cache strategy</h3>
 * {@link RoadProximityCache} provides a chunk-level pre-filter: the vast
 * majority of spawn positions are far from any road and are rejected in O(1).
 * Only chunks flagged as road-adjacent proceed to the precise
 * {@link RoadProximityChecker} test. The cache is built lazily on the first
 * spawn event and is invalidated by {@link RoadUpkeepSystem} after each daily
 * upkeep cycle.
 *
 * <h3>Statistics</h3>
 * {@link #getTotalEvents()}, {@link #getSuppressedCount()}, and
 * {@link #resetStats(long)} are used by the {@code safety_stats} debug command.
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class RoadSafetySystem {

    /** Precise search radius for in-depth check — must exceed max corridor (12). */
    private static final int SEARCH_RADIUS = 16;

    // ── Statistics ────────────────────────────────────────────────────────────
    private static final AtomicLong totalEvents    = new AtomicLong(0);
    private static final AtomicLong suppressed     = new AtomicLong(0);
    private static volatile long    windowStartTick = 0L;

    private RoadSafetySystem() {}

    // =========================================================================
    // Spawn event handler
    // =========================================================================

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        // ── Guard: hostile mob only ────────────────────────────────────────────
        if (!(event.getEntity() instanceof Monster)) return;

        // ── Guard: natural spawning only (not spawners, structures, events) ───
        if (event.getSpawnReason() != EntitySpawnReason.NATURAL) return;

        // ── Guard: server side only ────────────────────────────────────────────
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // ── Guard: daytime only (isDay() == false during night AND storms) ────
        if (!level.isDay()) return;

        BlockPos spawnPos = event.getEntity().blockPosition();

        // ── Fast chunk-level reject via cache ─────────────────────────────────
        ChunkPos chunk = new ChunkPos(spawnPos);
        if (!RoadProximityCache.isBuilt()) {
            WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
            RoadProximityCache.rebuild(graph);
        }
        if (!RoadProximityCache.couldChunkBeNearRoad(chunk)) {
            totalEvents.incrementAndGet();
            return;  // fast-reject: no road near this chunk
        }

        // ── Precise in-depth check ────────────────────────────────────────────
        totalEvents.incrementAndGet();
        WorldRoadGraph graph = WorldRoadSavedData.get(level).getGraph();
        Optional<RoadEdge.EdgeTier> nearbyTier =
                RoadProximityChecker.nearestMaintainedRoadTier(graph, spawnPos, SEARCH_RADIUS);

        if (nearbyTier.isEmpty()) return;

        // ── Probabilistic suppression ─────────────────────────────────────────
        float chance = suppressionChanceForTier(nearbyTier.get());
        if (level.getRandom().nextFloat() < chance) {
            event.setSpawnCancelled(true);
            suppressed.incrementAndGet();
        }
    }

    // =========================================================================
    // Suppression table (public for debug command access)
    // =========================================================================

    public static float suppressionChanceForTier(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case GREAT_ROAD -> 0.85f;
            case TRUNK      -> 0.70f;
            case CONNECTOR  -> 0.50f;
            case LOCAL      -> 0.30f;
        };
    }

    // =========================================================================
    // Statistics API
    // =========================================================================

    public static long getTotalEvents()      { return totalEvents.get(); }
    public static long getSuppressedCount()  { return suppressed.get(); }
    public static long getWindowStartTick()  { return windowStartTick; }

    public static void resetStats(long currentTick) {
        totalEvents.set(0);
        suppressed.set(0);
        windowStartTick = currentTick;
    }
}
