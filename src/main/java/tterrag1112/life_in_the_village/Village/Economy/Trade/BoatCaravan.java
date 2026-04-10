// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/BoatCaravan.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Travel.Roster;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A trade caravan that travels by boat along a {@link SeaRoute}.
 *
 * <h3>Differences from {@link Caravan}</h3>
 * <ul>
 *   <li>Travels along atlas cell centres at sea level instead of road blocks.</li>
 *   <li>Spawns a vanilla {@link Boat} entity carrying the merchant.</li>
 *   <li>No guard llama caravan — escort behaviour is left empty in 7c.</li>
 *   <li>Pulls its merchant from the origin village's existing
 *       MERCHANT pool. The same villager always drives the same boat.</li>
 * </ul>
 *
 * <h3>Position interpolation</h3>
 * Sea routes don't have a block list — they have cell keys. The boat
 * caravan's world position is computed by interpolating between cell
 * centres at sea level (Y = 63 in vanilla terrain). When the boat is
 * spawned, the merchant rides it; the engine handles spawn/despawn
 * via {@link tterrag1112.life_in_the_village.Village.Travel.TravellingGroupEngine}.
 */
public class BoatCaravan implements TravellingGroup {

    public enum BoatState {
        OUTBOUND,
        RETURNING,
        DELIVERING,
        FAILED;

        public static final Codec<BoatState> CODEC =
                Codec.STRING.xmap(BoatState::valueOf, BoatState::name);
    }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<BoatCaravan> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    UUIDUtil.CODEC.fieldOf("caravanId")
                            .forGetter(c -> c.caravanId),
                    UUIDUtil.CODEC.fieldOf("seaRouteId")
                            .forGetter(c -> c.seaRouteId),
                    UUIDUtil.CODEC.fieldOf("originVillageId")
                            .forGetter(c -> c.originVillageId),
                    UUIDUtil.CODEC.fieldOf("destVillageId")
                            .forGetter(c -> c.destVillageId),
                    BoatState.CODEC.fieldOf("state")
                            .forGetter(c -> c.state),
                    Codec.DOUBLE.fieldOf("progress")
                            .forGetter(c -> c.progress),
                    Codec.LONG.fieldOf("dispatchTick")
                            .forGetter(c -> c.dispatchTick),
                    ItemStack.CODEC.listOf().fieldOf("goods")
                            .forGetter(c -> c.goods),
                    Roster.CODEC.fieldOf("roster")
                            .forGetter(c -> c.roster)
            ).apply(i, BoatCaravan::new));

    // =========================================================================
    // Fields
    // =========================================================================

    private final UUID caravanId;
    private final UUID seaRouteId;
    private final UUID originVillageId;
    private final UUID destVillageId;
    private BoatState state;
    private double progress;
    private final long dispatchTick;
    private final List<ItemStack> goods;
    private final Roster roster;

    private transient boolean spawned = false;

    public BoatCaravan(UUID caravanId, UUID seaRouteId,
                       UUID originVillageId, UUID destVillageId,
                       BoatState state, double progress,
                       long dispatchTick, List<ItemStack> goods,
                       Roster roster) {
        this.caravanId       = caravanId;
        this.seaRouteId      = seaRouteId;
        this.originVillageId = originVillageId;
        this.destVillageId   = destVillageId;
        this.state           = state;
        this.progress        = progress;
        this.dispatchTick    = dispatchTick;
        this.goods           = new ArrayList<>(goods);
        this.roster          = roster;
    }

    public static BoatCaravan create(UUID seaRouteId,
                                     UUID originVillageId,
                                     UUID destVillageId,
                                     UUID principalId,
                                     UUID originDockId,
                                     List<ItemStack> goods,
                                     long currentTick) {
        Roster r = new Roster();
        r.setPrincipalId(principalId);
        r.setOriginBuildingId(originDockId);
        return new BoatCaravan(
                UUID.randomUUID(),
                seaRouteId,
                originVillageId,
                destVillageId,
                BoatState.OUTBOUND,
                0.0,
                currentTick,
                goods,
                r);
    }

    // =========================================================================
    // TravellingGroup implementation
    // =========================================================================

    @Override public UUID groupId() { return caravanId; }

    @Override
    public List<BlockPos> getPath(ServerLevel level, VillageSavedData data) {
        // Convert the sea route's cell path to a block-position list
        // by interpolating cell centres at sea level. Each cell becomes
        // 4 waypoints so caravans don't make 64-block hops.
        Optional<SeaRoute> routeOpt = data.getSeaRouteById(seaRouteId);
        if (routeOpt.isEmpty()) return List.of();
        SeaRoute route = routeOpt.get();
        List<Long> cells = route.getCellPath();
        if (cells.isEmpty()) return List.of();

        int seaLevel = level.getSeaLevel();
        List<BlockPos> path = new ArrayList<>();

        for (int idx = 0; idx < cells.size(); idx++) {
            long key = cells.get(idx);
            int cx = tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackX(key);
            int cz = tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackZ(key);
            int blockX = (cx << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                    + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
            int blockZ = (cz << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                    + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
            path.add(new BlockPos(blockX, seaLevel, blockZ));
        }
        return path;
    }

    @Override public boolean isReversed() { return state == BoatState.RETURNING; }
    @Override public double getProgress() { return progress; }
    @Override public void setProgress(double p) { this.progress = Math.max(0, Math.min(1, p)); }

    @Override
    public double getSpeedMultiplier(ServerLevel level, VillageSavedData data) {
        return data.getSeaRouteById(seaRouteId)
                .map(SeaRoute::getSpeedMultiplier)
                .orElse(1.0);
    }

    @Override public boolean isSpawned() { return spawned; }

    @Override
    public List<UUID> getEntityIds() {
        List<UUID> all = new ArrayList<>();
        if (roster.getPrincipalId() != null) all.add(roster.getPrincipalId());
        all.addAll(roster.getSpawnedCarrierIds());
        return all;
    }

    @Override
    public void onSpawn(ServerLevel level, BlockPos spawnPos, VillageSavedData data) {
        // Find the principal villager — they should already exist in
        // saved data, marked as on this expedition.
        UUID principalId = roster.getPrincipalId();
        if (principalId == null) return;

        // Get or restore the merchant entity
        TownspersonMob merchant = findOrRestorePrincipal(level, data, principalId);
        if (merchant == null) {
            System.out.println("BoatCaravan: principal " + principalId
                    + " could not be found or restored — aborting spawn");
            return;
        }

        // Place the merchant at the boat position
        merchant.setPos(spawnPos.getX() + 0.5,
                spawnPos.getY() + 1,
                spawnPos.getZ() + 0.5);

        // Spawn the boat
        Boat boat = net.minecraft.world.entity.EntityType.OAK_BOAT
                .create(level, EntitySpawnReason.EVENT);
        if (boat != null) {
            boat.setPos(spawnPos.getX() + 0.5,
                    spawnPos.getY() + 0.6,
                    spawnPos.getZ() + 0.5);
            level.addFreshEntity(boat);
            roster.getSpawnedCarrierIds().add(boat.getUUID());

            // Mount the merchant on the boat
            merchant.startRiding(boat);
        }

        // Stock the boat with goods (visible cargo would need a chest
        // boat — vanilla Boat doesn't have inventory. Phase 7d will
        // upgrade to chest boats when vessels become persistent.)

        spawned = true;
    }

    @Override
    public void onDespawn(ServerLevel level) {
        // Discard the boat — it's transient
        for (UUID id : roster.getSpawnedCarrierIds()) {
            var ent = level.getEntity(id);
            if (ent != null && !ent.isRemoved()) ent.discard();
        }
        roster.clearSpawned();

        // Discard the merchant entity but keep their saved-data record.
        // The villager still exists; they're just "in transit, not in world."
        UUID principalId = roster.getPrincipalId();
        if (principalId != null) {
            var ent = level.getEntity(principalId);
            if (ent != null && !ent.isRemoved()) ent.discard();
        }

        spawned = false;
    }

    @Override
    public void onTickSpawned(ServerLevel level, VillageSavedData data) {
        // Boat AI is minimal — the boat doesn't have its own pathfinding.
        // The engine advances progress; the boat is teleported to the
        // expected position by the realisation system on each tick.
        // This is a known limitation of the placeholder implementation.
        List<BlockPos> path = getPath(level, data);
        if (path.isEmpty()) return;

        BlockPos targetPos = tterrag1112.life_in_the_village.Village.Travel
                .TravellingGroupEngine.computePosition(this, path);
        if (targetPos == null) return;

        for (UUID id : roster.getSpawnedCarrierIds()) {
            var ent = level.getEntity(id);
            if (ent == null) continue;
            // Soft drift — only teleport if more than 8 blocks off
            double dx = ent.getX() - targetPos.getX();
            double dz = ent.getZ() - targetPos.getZ();
            if (dx * dx + dz * dz > 64) {
                ent.teleportTo(
                        targetPos.getX() + 0.5,
                        targetPos.getY(),
                        targetPos.getZ() + 0.5);
            }
        }
    }

    @Override
    public void onTickSimulated(ServerLevel level, VillageSavedData data) {}

    @Override
    public void onPathComplete(ServerLevel level, VillageSavedData data) {
        if (state == BoatState.OUTBOUND) {
            state = BoatState.DELIVERING;
        } else if (state == BoatState.RETURNING) {
            // Caller will clean up
        }
    }

    // =========================================================================
    // Principal restoration
    // =========================================================================

    /**
     * Looks up the principal villager and either finds the existing
     * entity or restores it from saved data. In Phase 7c the lookup
     * is best-effort — if the merchant entity isn't currently loaded,
     * we attempt to find them by UUID across the level. Phase 7d will
     * make this more robust by persisting villagers as records when
     * they're "away on expedition" rather than relying on entity lookup.
     */
    private static TownspersonMob findOrRestorePrincipal(
            ServerLevel level,
            VillageSavedData data,
            UUID principalId) {

        var ent = level.getEntity(principalId);
        if (ent instanceof TownspersonMob mob) {
            return mob;
        }

        // Entity not found — log and return null. The caravan won't
        // spawn this tick; it'll retry on the next tick when the
        // entity (hopefully) loads in.
        System.out.println("BoatCaravan: principal " + principalId
                + " not currently loaded — will retry next tick");
        return null;
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public UUID getCaravanId()       { return caravanId; }
    public UUID getSeaRouteId()      { return seaRouteId; }
    public UUID getOriginVillageId() { return originVillageId; }
    public UUID getDestVillageId()   { return destVillageId; }
    public BoatState getState()      { return state; }
    public void setState(BoatState s) { this.state = s; }
    public List<ItemStack> getGoods() { return goods; }
    public Roster getRoster()        { return roster; }
}