package tterrag1112.life_in_the_village.Events;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Planning.VillagePlanHelper;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageSpawner;

/**
 * Watches player positions and realises planned villages when a player
 * enters the realisation radius.
 *
 * <h3>Trigger radius</h3>
 * Villages are realised when any player is within {@link #REALISE_RADIUS}
 * blocks of the planned origin. 256 blocks is ~16 chunks — far enough
 * that the player can't see the village spawning in, close enough that
 * the chunks around the origin are already loaded by the time the
 * spawner runs.
 *
 * <h3>Rate limiting</h3>
 * At most one village is realised per tick. Village spawning is
 * expensive (terrain prep, building placement, NPC spawning, trade
 * routes), so firing several at once would produce visible hitches.
 * Spreading them across ticks keeps the frame times smooth even when
 * a player teleports into a cluster of unrealised villages.
 *
 * <h3>Order</h3>
 * When multiple villages are eligible, the closest one to any player
 * is realised first. This keeps the realisation work focused on what
 * the player is about to see.
 */
public class VillageRealisationSystem implements TickSubsystem {


    // Add at top of VillageRealisationSystem class
    private final java.util.Map<java.util.UUID, FailureRecord> failureHistory = new java.util.HashMap<>();

    private record FailureRecord(int attempts, long nextAttemptTick) {}

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final long[] BACKOFF_TICKS = {100L, 400L, 1200L, 3600L, 12000L};

    /** Player must be within this many blocks of a planned village origin. */
    private static final int REALISE_RADIUS = 256;

    /** Square of the above for cheap comparisons. */
    private static final double REALISE_RADIUS_SQ =
            (double) REALISE_RADIUS * REALISE_RADIUS;

    @Override public String name()     { return "village_realisation"; }
    @Override public int    interval() { return 20; } // 1 Hz
    @Override public int    priority() { return 60; } // before heavier systems

    @Override
    public void tick(TickContext ctx) {
        ServerLevel level = ctx.level();
        if (level.dimension() != Level.OVERWORLD) return;

        VillageSavedData data = ctx.villageData();
        long currentTick = level.getGameTime();

        Village target = null;
        double bestDistSq = REALISE_RADIUS_SQ;

        for (Village v : data.getAllVillages()) {
            if (!VillagePlanHelper.isPlanned(v)) continue;

            // Skip villages in failure cooldown
            FailureRecord failureState = failureHistory.get(v.getId());
            if (failureState != null) {
                if (failureState.attempts() >= MAX_RETRY_ATTEMPTS) continue;
                if (currentTick < failureState.nextAttemptTick()) continue;
            }

            BlockPos origin = v.getPlannedOrigin();
            if (origin == null) continue;

            for (ServerPlayer player : level.players()) {
                double dx = player.getX() - origin.getX();
                double dz = player.getZ() - origin.getZ();
                double dSq = dx * dx + dz * dz;
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    target = v;
                }
            }
        }

        if (target == null) return;

        BlockPos origin = target.getPlannedOrigin();
        String   type   = target.getVillageType();
        String   name   = target.getName();

        System.out.println("VillageRealisationSystem: realising '" + name
                + "' (" + type + ") at " + origin.toShortString());

        java.util.UUID targetId = target.getId();
        realisePlannedVillage(level, data, target);

        // If the village is still planned (i.e. spawn failed), record the failure
        // so we back off instead of hammering the same spot every tick.
        // We check by name since the UUID changes between planned and realised.
        boolean stillPlanned = data.getAllVillages().stream()
                .anyMatch(v -> v.getName().equals(name) && VillagePlanHelper.isPlanned(v));

        if (stillPlanned) {
            FailureRecord prev = failureHistory.get(targetId);
            // The planned village got a new UUID after restore — migrate the record
            java.util.UUID newId = data.getAllVillages().stream()
                    .filter(v -> v.getName().equals(name))
                    .findFirst().map(Village::getId).orElse(targetId);
            int attempts = prev == null ? 1 : prev.attempts() + 1;
            long backoff = BACKOFF_TICKS[Math.min(attempts - 1, BACKOFF_TICKS.length - 1)];
            failureHistory.remove(targetId);
            failureHistory.put(newId, new FailureRecord(attempts, currentTick + backoff));

            boolean replanned = tryReplan(level, data, name, type, origin);
            if (replanned) {
                failureHistory.remove(targetId);
                System.out.println("VillageRealisationSystem: replanned '" + name
                        + "' to a new location after " + attempts + " failures");
            } else {
                System.out.println("VillageRealisationSystem: giving up on '" + name
                        + "' after " + MAX_RETRY_ATTEMPTS + " failures at "
                        + origin.toShortString() + " — no alternative cell available");
            }
        }
            failureHistory.remove(targetId);
    }
    /**
     * Attempts to find a better cell for a persistently-failing planned
     * village. Walks the village's kingdom's territorial claim, runs the
     * current placement scorer over every cell, and moves the village to
     * the top-scoring one — provided the new score is significantly
     * better than the current location's. Returns false if no better
     * cell exists.
     */
    private static boolean tryReplan(ServerLevel level, VillageSavedData data,
                                     String name, String villageType,
                                     BlockPos currentOrigin) {
        var kingdomOpt = data.getAllKingdoms().stream()
                .filter(k -> data.getAllVillages().stream()
                        .anyMatch(v -> v.getName().equals(name)
                                && k.containsVillage(v.getId())))
                .findFirst();
        if (kingdomOpt.isEmpty()) return false;

        var kingdom = kingdomOpt.get();
        var claim = kingdom.getTerritorialClaim().orElse(null);
        if (claim == null) return false;

        var typeData = tterrag1112.life_in_the_village.Village.VillageTypeRegistry
                .INSTANCE.getType(villageType);
        if (typeData == null) return false;

        var atlas = tterrag1112.life_in_the_village.World.Atlas.WorldAtlas.get(level);

        // Other already-realised village positions in this kingdom act as
        // proximity penalties so we don't replan onto a neighbour.
        java.util.List<BlockPos> peerPositions = new java.util.ArrayList<>();
        for (var vid : kingdom.getVillageIds()) {
            var peer = data.getVillageById(vid).orElse(null);
            if (peer == null || peer.getName().equals(name)) continue;
            BlockPos anchor = peer.getAnchorPos();
            if (anchor != null) peerPositions.add(anchor);
        }

        BlockPos capitalOrigin = new BlockPos(
                tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackX(claim.originCellKey())
                        << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT,
                64,
                tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackZ(claim.originCellKey())
                        << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT);

        tterrag1112.life_in_the_village.World.Atlas.AtlasCell bestCell = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (long key : claim.claimedCellKeys()) {
            var cell = atlas.getCellByCoord(
                    tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackX(key),
                    tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackZ(key));
            if (cell == null) continue;
            if (!tterrag1112.life_in_the_village.Kingdom.Placement.VillageTypeMatcher
                    .cellMatchesType(cell, typeData)) continue;

            double s = tterrag1112.life_in_the_village.Kingdom.Placement
                    .VillagePlacementScorer.score(
                            atlas, cell, typeData, capitalOrigin, peerPositions);
            if (s > bestScore) { bestScore = s; bestCell = cell; }
        }

        if (bestCell == null) return false;

        BlockPos newPos = new BlockPos(
                bestCell.blockCenterX(), bestCell.centerY(), bestCell.blockCenterZ());

        // Don't bother moving if the "best" cell is the current one
        if (newPos.equals(currentOrigin)) return false;

        // Update the planned village's origin
        var villageOpt = data.getAllVillages().stream()
                .filter(v -> v.getName().equals(name)).findFirst();
        if (villageOpt.isEmpty()) return false;
        villageOpt.get().setPlannedOrigin(newPos);
        data.setDirty();
        return true;
    }

    /**
     * Replaces a planned village with a fully-spawned one while preserving
     * its name and kingdom membership.
     *
     * <p>The flow:
     * <ol>
     *   <li>Remember the planned village's kingdom and origin.</li>
     *   <li>Remove the planned village from saved data so the spawner
     *       doesn't see a duplicate name.</li>
     *   <li>Call the normal {@link VillageSpawner#spawnVillage} pipeline.</li>
     *   <li>If it succeeds, flip the new village to realised and add it
     *       to the preserved kingdom. If it fails, put the placeholder back.</li>
     * </ol>
     *
     * <p>The village's UUID changes during realisation because the spawner
     * creates a fresh {@code Village} record. Kingdom membership is
     * transferred using the new UUID.
     */
    private static void realisePlannedVillage(ServerLevel level,
                                              VillageSavedData data,
                                              Village planned) {
        BlockPos origin = planned.getPlannedOrigin();
        String   name   = planned.getName();
        String   type   = planned.getVillageType();
        java.util.UUID plannedId = planned.getId();

        java.util.Optional<tterrag1112.life_in_the_village.Kingdom.Kingdom> kingdomOpt
                = data.getKingdomForVillage(plannedId);

        data.removeVillage(plannedId);
        kingdomOpt.ifPresent(k -> k.removeVillage(plannedId));
        data.setDirty();

        // ── Instrumented spawn attempt ────────────────────────────────────────
        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .beginAttempt();

        java.util.Optional<Village> spawnedOpt;
        try {
            spawnedOpt = VillageSpawner.spawnVillage(level, origin, type, name);
        } catch (Exception e) {
            tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                    .record(
                            tterrag1112.life_in_the_village.Kingdom.Placement
                                    .PlacementFailureRecorder.Reason.PLANNER_EXCEPTION,
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            origin, type);
            spawnedOpt = java.util.Optional.empty();
        }

        boolean succeeded = spawnedOpt.isPresent();
        tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder
                .endAttempt(succeeded, type, origin);

        if (!succeeded) {
            System.out.println("VillageRealisationSystem: spawn failed for '"
                    + name + "' — restoring planned state");
            Village restored = new Village(name);
            restored.setVillageType(type);
            restored.setPlannedOrigin(origin);
            restored.setRealised(false);
            data.addVillage(restored);
            kingdomOpt.ifPresent(k -> k.addVillage(restored.getId()));
            data.setDirty();
            return;
        }

        Village spawned = spawnedOpt.get();
        VillagePlanHelper.markRealised(spawned, data);
        kingdomOpt.ifPresent(k -> k.addVillage(spawned.getId()));
        data.setDirty();
    }
}