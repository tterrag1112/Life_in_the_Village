package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tterrag1112.life_in_the_village.Life_in_the_village;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Roster.BuildingRoster;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Phase 6.3.3.k.4 — wires the long-pending chunk-load consumer for
 * {@link BuildingRoster#realizeNear} / {@link BuildingRoster#derealizeAll}
 * (infrastructure-only since 6.3.3.g.1) plus the entity-death bridge
 * that converts realized-animal deaths back into roster-slot removals.
 *
 * <h3>Realize / derealize lifecycle</h3>
 * <p>{@link ChunkEvent.Load} queues realize requests for any rosters
 * anchored in the loaded chunk; the queue drains one realize per
 * server tick (matching {@link NaturalVillageSpawnEvent}'s shape) so
 * a chunk-load burst doesn't choke a single tick.</p>
 *
 * <p>{@link ChunkEvent.Unload} doesn't derealize immediately — instead
 * it marks the building with an {@code unloadStartTick}. Each server
 * tick checks pending derealize candidates; only after
 * {@link #DEREALIZE_HYSTERESIS_TICKS} of continuous unload do we
 * finalize the derealize. This prevents thrash on chunk-edge
 * traversal (player walking back and forth across a boundary).</p>
 *
 * <h3>Entity-death bridge</h3>
 * <p>When a {@link LivingDeathEvent} fires for an entity whose UUID
 * matches any realized roster slot, the slot is removed. Without this
 * bridge, predator kills (k.5) and other animal deaths would leave
 * the roster holding ghost slots that pollute population counts,
 * production cycles, and breeding eligibility checks.</p>
 *
 * <p>Defensive: a death event that fires after the roster has already
 * been derealized (rare but possible due to event ordering) is a
 * silent no-op — {@code removeRealizedByUuid} returns false and we
 * move on.</p>
 */
@EventBusSubscriber(modid = Life_in_the_village.MODID)
public final class RosterChunkLoadHandler {

    /** Server ticks a chunk must remain unloaded before its rosters
     *  finalize derealize. Spec line k.4: 200 ticks (~10s). */
    public static final long DEREALIZE_HYSTERESIS_TICKS = 200L;

    private static final int REALIZES_PER_TICK = 1;

    /** Realize requests queued from {@link ChunkEvent.Load}. */
    private static final Queue<UUID> realizeQueue = new ArrayDeque<>();

    /** Derealize candidates: buildingId → tick at which the building's
     *  chunk first unloaded. Reset to absent when the chunk re-loads
     *  before the hysteresis expires. */
    private static final Map<UUID, Long> derealizePending = new HashMap<>();

    private RosterChunkLoadHandler() {}

    // ── Chunk load / unload ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.isClientSide()) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        VillageSavedData vdata = VillageSavedData.get(level);
        RosterSavedData rdata  = RosterSavedData.get(level);
        ChunkPos cp = chunk.getPos();

        for (var perBuilding : rdata.getAllRosters().entrySet()) {
            UUID buildingId = perBuilding.getKey();
            if (!isBuildingAnchoredIn(vdata, buildingId, cp)) continue;
            // Chunk came back before hysteresis expired — cancel
            // pending derealize.
            derealizePending.remove(buildingId);
            realizeQueue.add(buildingId);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.isClientSide()) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        VillageSavedData vdata = VillageSavedData.get(level);
        RosterSavedData rdata  = RosterSavedData.get(level);
        ChunkPos cp = chunk.getPos();
        long now = level.getGameTime();

        for (var perBuilding : rdata.getAllRosters().entrySet()) {
            UUID buildingId = perBuilding.getKey();
            if (!isBuildingAnchoredIn(vdata, buildingId, cp)) continue;
            // Already pending — keep the earlier timestamp so the
            // hysteresis window doesn't extend forever under thrash.
            derealizePending.putIfAbsent(buildingId, now);
        }
    }

    // ── Per-tick drain ───────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        long now = level.getGameTime();
        VillageSavedData vdata = VillageSavedData.get(level);
        RosterSavedData rdata  = RosterSavedData.get(level);

        // Drain realize queue (1/tick) so a chunk-load burst doesn't
        // spawn dozens of mobs in a single tick.
        int processed = 0;
        while (!realizeQueue.isEmpty() && processed < REALIZES_PER_TICK) {
            UUID buildingId = realizeQueue.poll();
            processed++;
            BlockPos fallback = resolveAnchorFallback(vdata, buildingId);
            if (fallback == null) continue;
            for (BuildingRoster roster : rdata.getRostersForBuilding(buildingId)) {
                BlockPos anchor = roster.resolveRealizationAnchor(level, fallback);
                try {
                    roster.realizeNear(level, anchor);
                } catch (RuntimeException e) {
                    // Don't take down the tick loop if a single roster
                    // realize fails (e.g. malformed entity definition).
                }
            }
        }

        // Hysteresis check on derealize candidates.
        if (!derealizePending.isEmpty()) {
            var it = derealizePending.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (now - e.getValue() < DEREALIZE_HYSTERESIS_TICKS) continue;
                UUID buildingId = e.getKey();
                it.remove();
                for (BuildingRoster roster : rdata.getRostersForBuilding(buildingId)) {
                    try {
                        roster.derealizeAll(level);
                    } catch (RuntimeException ex) {
                        // Same defensive shape as realize.
                    }
                }
            }
        }
    }

    // ── Entity death → slot removal ──────────────────────────────────────────

    @SubscribeEvent
    public static void onEntityDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        UUID dead = event.getEntity().getUUID();
        if (dead == null) return;
        RosterSavedData rdata = RosterSavedData.get(level);
        // Walk every roster looking for the matching realized slot.
        // Cost is O(rosters * slots) per death — fine for the small
        // counts a single-world server holds. Defensive: removal is
        // a no-op when the slot has already been derealized.
        for (var perBuilding : rdata.getAllRosters().values()) {
            for (BuildingRoster roster : perBuilding.values()) {
                if (roster == null) continue;
                if (roster.removeRealizedByUuid(dead)) {
                    rdata.markDirty();
                    return; // a UUID can match at most one slot
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isBuildingAnchoredIn(VillageSavedData vdata,
                                                UUID buildingId, ChunkPos cp) {
        var b = vdata.getBuildingById(buildingId).orElse(null);
        if (b == null) return false;
        BlockPos origin = b.getShape().getOrigin();
        return new ChunkPos(origin).equals(cp);
    }

    private static BlockPos resolveAnchorFallback(VillageSavedData vdata,
                                                  UUID buildingId) {
        return vdata.getBuildingById(buildingId)
                .map(b -> b.getShape().getOrigin())
                .orElse(null);
    }
}
