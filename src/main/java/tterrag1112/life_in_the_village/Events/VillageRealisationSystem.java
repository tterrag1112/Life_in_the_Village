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

        // ── Find the closest planned village to any player ───────────────────
        Village target = null;
        double bestDistSq = REALISE_RADIUS_SQ;

        for (Village v : data.getAllVillages()) {
            if (!VillagePlanHelper.isPlanned(v)) continue;
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

        // ── Realise the chosen village ───────────────────────────────────────
        BlockPos origin = target.getPlannedOrigin();
        String   type   = target.getVillageType();
        String   name   = target.getName();

        System.out.println("VillageRealisationSystem: realising '" + name
                + "' (" + type + ") at " + origin.toShortString());

        // The existing spawner creates a NEW Village record. We need to
        // remove the planned placeholder first so there's no duplicate,
        // then rename the spawned village back to the planned name/kingdom.
        realisePlannedVillage(level, data, target);
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

        // Remember kingdom membership if any
        java.util.Optional<tterrag1112.life_in_the_village.Kingdom.Kingdom> kingdomOpt
                = data.getKingdomForVillage(plannedId);

        // Remove the placeholder so the spawner doesn't see a duplicate name
        data.removeVillage(plannedId);
        kingdomOpt.ifPresent(k -> k.removeVillage(plannedId));
        data.setDirty();

        // Hand off to the normal spawner
        java.util.Optional<Village> spawnedOpt =
                VillageSpawner.spawnVillage(level, origin, type, name);

        if (spawnedOpt.isEmpty()) {
            // Spawn failed (terrain unsuitable, etc.) — restore the planned
            // placeholder so the system can retry later when conditions change.
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