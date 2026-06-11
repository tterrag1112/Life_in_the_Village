package tterrag1112.life_in_the_village.Village;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Liveliness — village chunkloading. Makes a village <b>fully active while a
 * player is inside it</b>, so NPCs on the far/unloaded side are real entities
 * (with position + pathfinding) that can commute, work, go home, and converge on
 * the town square / temple for a festival or rite. Village NPCs are ordinary
 * persistent entities that cease to exist when their chunks unload; rather than
 * simulate per-NPC positions, we force-load the player's current village's
 * footprint chunks at the vanilla forced-chunk (entity-ticking) level so the NPCs
 * tick and the existing gather machinery works.
 *
 * <h3>Mechanism</h3>
 * <ul>
 *   <li><b>Detection</b> — driven off the existing per-player tick
 *       ({@code PlayerEventProximityHandler}); self-throttled to one reconcile per
 *       {@link #RECONCILE_INTERVAL} ticks (no new per-tick scan). A village counts
 *       as "occupied" while any online player is within its bounds inflated by
 *       {@link #ENTRY_INFLATE}.</li>
 *   <li><b>Force</b> — every chunk the village footprint occupies (its building-AABB
 *       union inflated by {@link #FOOTPRINT_MARGIN}) is forced via
 *       {@link ServerLevel#setChunkForced} — the vanilla {@code /forceload}
 *       mechanism, which keeps the chunk + its block entities + its entities fully
 *       ticking. Scoped tightly to the footprint (not a broad radius), capped at
 *       {@link #MAX_VILLAGE_CHUNKS} defensively.</li>
 *   <li><b>Release (hysteresis)</b> — when no player is within, the village's chunks
 *       are released after {@link #RELEASE_HYSTERESIS_TICKS} of continuous absence
 *       (mirrors the roster derealize hysteresis) so standing at the village edge
 *       doesn't thrash tickets. Player <b>logout</b> releases emptied villages
 *       immediately (a definitive departure); a removed village is released on the
 *       next reconcile; {@link #releaseAll} on server stop drops everything — no
 *       leaked forced chunks on any live path.</li>
 * </ul>
 *
 * <p>Chunks are ref-counted ({@link #forceCount}) so two overlapping villages don't
 * unforce a shared chunk while the other still needs it.</p>
 *
 * <p><b>Cost.</b> Bounded by village size (≈ houses × 2 NPCs). The per-NPC brain
 * cost of more loaded NPCs is the known lag source a future <b>brain-tick LOD</b>
 * (distant loaded NPCs tick their brains less often) will cap — NOT done here.</p>
 */
public final class VillageChunkLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VillageChunkLoader() {}

    /** Continuous-absence ticks before a village's chunks release (anti-thrash). */
    private static final long RELEASE_HYSTERESIS_TICKS = 200L;
    /** A player within the village bounds inflated by this counts as "inside". */
    private static final int  ENTRY_INFLATE     = 16;
    /** Footprint margin (blocks) so edge buildings / paths / the venue are covered. */
    private static final int  FOOTPRINT_MARGIN  = 16;
    /** Reconcile cadence — one pass per this many ticks regardless of player count. */
    private static final int  RECONCILE_INTERVAL = 40;
    /** Defensive cap on a single village's forced-chunk count. */
    private static final int  MAX_VILLAGE_CHUNKS = 400;

    /** village → the chunks we currently force for it. */
    private static final Map<UUID, Set<ChunkPos>> forcedByVillage = new HashMap<>();
    /** village → last tick a player was present (inflated bounds). */
    private static final Map<UUID, Long> lastPresentTick = new HashMap<>();
    /** chunk → how many villages currently want it forced (ref-count). */
    private static final Map<ChunkPos, Integer> forceCount = new HashMap<>();

    private static long lastReconcileTick = Long.MIN_VALUE;

    // ── Entry points (wired from the event handlers) ─────────────────────────

    /** Driven from the existing per-player tick; self-throttled to one reconcile
     *  per {@link #RECONCILE_INTERVAL} ticks. */
    public static void onPlayerTick(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        long now = server.overworld().getGameTime();
        // Gate-0 fix: the Long.MIN_VALUE sentinel must be checked explicitly.
        // (now - Long.MIN_VALUE) overflows to a large NEGATIVE long, which is
        // always < RECONCILE_INTERVAL — so this guard early-returned on every
        // call and the loader never forced a single chunk.
        if (lastReconcileTick != Long.MIN_VALUE
                && now - lastReconcileTick < RECONCILE_INTERVAL) return;
        lastReconcileTick = now;
        reconcile(server, null);
    }

    /** A player left the server — release the chunks of any village now empty
     *  immediately (logout is definitive; no hysteresis). Also covers the
     *  "last player logs out" case the periodic reconcile would otherwise never
     *  reach (no more player ticks fire). */
    public static void onPlayerLogout(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        reconcile(server, player.getUUID());
    }

    /** Server stopping — release every forced chunk we hold. No leaked tickets. */
    public static void releaseAll(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (Set<ChunkPos> chunks : forcedByVillage.values()) {
            for (ChunkPos cp : chunks) level.setChunkForced(cp.x, cp.z, false);
        }
        forcedByVillage.clear();
        lastPresentTick.clear();
        forceCount.clear();
        lastReconcileTick = Long.MIN_VALUE;
    }

    // ── Reconcile ────────────────────────────────────────────────────────────

    /**
     * Recomputes which villages are occupied and forces / releases footprint
     * chunks accordingly. {@code excludePlayerId != null} marks a logout pass:
     * the departing player is excluded from the present-set and any village that
     * is consequently empty is released without waiting out the hysteresis.
     */
    private static void reconcile(MinecraftServer server, UUID excludePlayerId) {
        ServerLevel level = server.overworld();
        VillageSavedData data = VillageSavedData.get(level);
        long now = level.getGameTime();

        // Which villages currently hold a player (inflated bounds)?
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (excludePlayerId != null && p.getUUID().equals(excludePlayerId)) continue;
            if (p.level() != level) continue;
            double px = p.getX(), py = p.getY(), pz = p.getZ();
            for (Village v : data.getAllVillages()) {
                boolean in = v.getBounds(data)
                        .map(b -> b.inflate(ENTRY_INFLATE).contains(px, py, pz))
                        .orElse(false);
                if (in) present.add(v.getId());
            }
        }
        for (UUID vid : present) lastPresentTick.put(vid, now);

        boolean logoutPass = excludePlayerId != null;

        // Reconcile every village we currently force plus every occupied one.
        Set<UUID> consider = new HashSet<>(forcedByVillage.keySet());
        consider.addAll(present);
        for (UUID vid : consider) {
            Village v = data.getVillageById(vid).orElse(null);
            boolean keep;
            if (v == null) {
                keep = false;                               // village removed
            } else if (present.contains(vid)) {
                keep = true;                                // a player is inside
            } else if (logoutPass) {
                keep = false;                               // emptied by logout — release now
            } else {
                Long last = lastPresentTick.get(vid);       // hysteresis on a normal leave
                keep = last != null && (now - last) < RELEASE_HYSTERESIS_TICKS;
            }

            if (keep) {
                applyForce(level, data, vid, v);
            } else {
                releaseVillage(level, vid);
                lastPresentTick.remove(vid);
            }
        }
    }

    /** Forces exactly the village's current footprint chunks, adding newly-needed
     *  ones and dropping any no longer in the footprint. */
    private static void applyForce(ServerLevel level, VillageSavedData data,
                                   UUID vid, Village v) {
        Set<ChunkPos> want = footprintChunks(v, data);
        Set<ChunkPos> have = forcedByVillage.computeIfAbsent(vid, k -> new HashSet<>());
        boolean firstForce = have.isEmpty() && !want.isEmpty();
        // Add newly-wanted chunks.
        for (ChunkPos cp : want) {
            if (have.add(cp)) force(level, cp);
        }
        // One INFO line on the first force per village occupation (not per-tick)
        // so the loader is diagnosable in-world via the log + /forceload query.
        if (firstForce) {
            LOGGER.info("[VillageChunkLoader] force-loaded {} chunk(s) for village {}",
                    have.size(), v.getName());
        }
        // Drop chunks that left the footprint (village shrank / buildings removed).
        for (Iterator<ChunkPos> it = have.iterator(); it.hasNext(); ) {
            ChunkPos cp = it.next();
            if (!want.contains(cp)) { unforce(level, cp); it.remove(); }
        }
        if (have.isEmpty()) forcedByVillage.remove(vid);
    }

    private static void releaseVillage(ServerLevel level, UUID vid) {
        Set<ChunkPos> have = forcedByVillage.remove(vid);
        if (have == null) return;
        for (ChunkPos cp : have) unforce(level, cp);
        if (!have.isEmpty()) {
            LOGGER.info("[VillageChunkLoader] released {} chunk(s) for village {}",
                    have.size(), vid);
        }
    }

    // ── Ref-counted force/unforce (overlapping villages share chunks safely) ──

    private static void force(ServerLevel level, ChunkPos cp) {
        int c = forceCount.merge(cp, 1, Integer::sum);
        if (c == 1) level.setChunkForced(cp.x, cp.z, true);
    }

    private static void unforce(ServerLevel level, ChunkPos cp) {
        int c = forceCount.getOrDefault(cp, 0);
        if (c <= 1) {
            forceCount.remove(cp);
            level.setChunkForced(cp.x, cp.z, false);
        } else {
            forceCount.put(cp, c - 1);
        }
    }

    // ── Footprint → chunks ───────────────────────────────────────────────────

    private static Set<ChunkPos> footprintChunks(Village v, VillageSavedData data) {
        AABB bounds = v.getBounds(data).orElse(null);
        if (bounds == null) return Set.of();
        AABB b = bounds.inflate(FOOTPRINT_MARGIN);
        int minCX = (int) Math.floor(b.minX) >> 4;
        int maxCX = (int) Math.floor(b.maxX) >> 4;
        int minCZ = (int) Math.floor(b.minZ) >> 4;
        int maxCZ = (int) Math.floor(b.maxZ) >> 4;
        long count = (long) (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
        if (count > MAX_VILLAGE_CHUNKS) {
            LOGGER.warn("[VillageChunkLoader] village {} footprint {} chunks exceeds "
                    + "cap {} — skipping force-load (unexpectedly large village?)",
                    v.getName(), count, MAX_VILLAGE_CHUNKS);
            return Set.of();
        }
        Set<ChunkPos> chunks = new HashSet<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }
}
