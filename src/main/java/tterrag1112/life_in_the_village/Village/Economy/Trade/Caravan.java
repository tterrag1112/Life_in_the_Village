package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Travel.Roster;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroup;

import java.util.*;

public class Caravan implements TravellingGroup {

    public enum CaravanState {
        OUTBOUND,    // travelling from A to B
        RETURNING,   // travelling from B back to A
        DELIVERING,  // arrived, transferring goods
        FAILED;      // attacked/destroyed

        public static final Codec<CaravanState> CODEC =
                Codec.STRING.xmap(CaravanState::valueOf,
                        CaravanState::name);
    }

    public static final Codec<Caravan> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("caravanId")
                            .forGetter(Caravan::getCaravanId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("routeId")
                            .forGetter(Caravan::getRouteId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("originVillageId")
                            .forGetter(Caravan::getOriginVillageId),
                    Codec.STRING.xmap(UUID::fromString, UUID::toString)
                            .fieldOf("destVillageId")
                            .forGetter(Caravan::getDestVillageId),
                    CaravanState.CODEC.fieldOf("state")
                            .forGetter(Caravan::getState),
                    Codec.DOUBLE.fieldOf("progress")
                            .forGetter(Caravan::getProgress),
                    Codec.LONG.fieldOf("dispatchTick")
                            .forGetter(Caravan::getDispatchTick),
                    ItemStack.CODEC.listOf().fieldOf("goods")
                            .forGetter(Caravan::getGoods),
                    Codec.INT.fieldOf("guardCount")
                            .forGetter(Caravan::getGuardCount),
                    Roster.CODEC.fieldOf("roster")
                            .forGetter(Caravan::getRoster)
            ).apply(instance, Caravan::fromCodec));

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UUID caravanId;
    private final UUID routeId;
    private final UUID originVillageId;
    private final UUID destVillageId;
    private CaravanState state;
    private double progress;       // 0.0 = at origin, 1.0 = at dest
    private final long dispatchTick;
    private final List<ItemStack> goods;
    private final int guardCount;
    private final Roster roster;

    // Transient — never persisted directly; rebuilt at spawn
    private transient boolean isSpawned = false;
    // Transient — overrides road block lookup when set by debug dispatch command
    @org.jetbrains.annotations.Nullable private transient List<BlockPos> overridePath;

    public Caravan(UUID caravanId, UUID routeId,
                   UUID originVillageId, UUID destVillageId,
                   CaravanState state, double progress,
                   long dispatchTick, List<ItemStack> goods,
                   int guardCount, Roster roster) {
        this.caravanId       = caravanId;
        this.routeId         = routeId;
        this.originVillageId = originVillageId;
        this.destVillageId   = destVillageId;
        this.state           = state;
        this.progress        = progress;
        this.dispatchTick    = dispatchTick;
        this.goods           = new ArrayList<>(goods);
        this.guardCount      = guardCount;
        this.roster          = roster != null ? roster : new Roster();
    }

    public static Caravan fromCodec(
            UUID caravanId, UUID routeId,
            UUID originVillageId, UUID destVillageId,
            CaravanState state, double progress,
            long dispatchTick, List<ItemStack> goods,
            int guardCount, Roster roster) {
        return new Caravan(caravanId, routeId,
                originVillageId, destVillageId,
                state, progress, dispatchTick,
                goods, guardCount, roster);
    }

    public static Caravan create(UUID routeId,
                                 UUID originVillageId,
                                 UUID destVillageId,
                                 UUID principalId,
                                 UUID originMarketId,
                                 List<ItemStack> goods,
                                 int guardCount,
                                 long currentTick) {
        Roster r = new Roster();
        r.setPrincipalId(principalId);
        r.setOriginBuildingId(originMarketId);
        return new Caravan(
                UUID.randomUUID(),
                routeId,
                originVillageId,
                destVillageId,
                CaravanState.OUTBOUND,
                0.0,
                currentTick,
                goods,
                guardCount,
                r);
    }

    // -------------------------------------------------------------------------
    // Simulation tick
    // -------------------------------------------------------------------------

    /**
     * Advances caravan progress when unobserved.
     * Returns true if dirty.
     */
    public boolean tick(long currentTick,
                        double speedMultiplier) {
        if (isSpawned) return false;
        if (state == CaravanState.FAILED) return false;
        if (state == CaravanState.DELIVERING) return false;

        // speedMultiplier is now a bonus — 1.0 minimum
        double increment = BASE_PROGRESS_PER_TICK
                * Math.max(1.0, speedMultiplier);
        progress = Math.min(1.0, progress + increment);

        if (progress >= 1.0) {
            if (state == CaravanState.OUTBOUND) {
                state    = CaravanState.DELIVERING;
                progress = 1.0;
            } else if (state == CaravanState.RETURNING) {
                progress = 1.0;
            }
            return true;
        }

        return true;
    }

    private static final double BASE_PROGRESS_PER_TICK = 0.002;

    /**
     * Gets the current world position based on progress
     * along the road path.
     */
    public BlockPos getWorldPosition(
            List<BlockPos> roadBlocks) {
        if (roadBlocks.isEmpty()) return null;

        if (state == CaravanState.RETURNING) {
            // Returning — traverse road in reverse
            int index = (int)((1.0 - progress)
                    * (roadBlocks.size() - 1));
            index = Math.max(0, Math.min(
                    roadBlocks.size() - 1, index));
            return roadBlocks.get(index);
        }

        int index = (int)(progress
                * (roadBlocks.size() - 1));
        index = Math.max(0, Math.min(
                roadBlocks.size() - 1, index));
        return roadBlocks.get(index);
    }

    // -------------------------------------------------------------------------
    // Getters / setters
    // -------------------------------------------------------------------------

    public UUID getCaravanId()           { return caravanId; }
    public UUID getRouteId()             { return routeId; }
    public UUID getOriginVillageId()     { return originVillageId; }
    public UUID getDestVillageId()       { return destVillageId; }
    public CaravanState getState()       { return state; }
    public double getProgress()          { return progress; }
    public long getDispatchTick()        { return dispatchTick; }
    public List<ItemStack> getGoods()    { return goods; }
    public int getGuardCount()           { return guardCount; }
    public boolean isSpawned()           { return isSpawned; }

    public Roster getRoster() { return roster; }

    @Override
    public List<UUID> getEntityIds() {
        List<UUID> all = new ArrayList<>();
        if (roster.getPrincipalId() != null) all.add(roster.getPrincipalId());
        all.addAll(roster.getSpawnedEscortIds());
        all.addAll(roster.getSpawnedCarrierIds());
        return all;
    }

    public void setState(CaravanState state) {
        this.state = state;
    }
    public void setProgress(double p) {
        this.progress = Math.max(0, Math.min(1, p));
    }
    public void setSpawned(boolean spawned) {
        this.isSpawned = spawned;
    }

    @org.jetbrains.annotations.Nullable
    public List<BlockPos> getOverridePath() { return overridePath; }
    public void setOverridePath(List<BlockPos> path) { this.overridePath = path; }


    // =========================================================================
    // TravellingGroup implementation
    // =========================================================================

    @Override
    public UUID groupId() { return caravanId; }

    @Override
    public List<BlockPos> getPath(ServerLevel level, VillageSavedData data) {
        return data.getRouteById(routeId)
                .flatMap(r -> data.getRoadById(r.getConnectionId()))
                .map(TradeRoad::getBlocks)
                .orElse(java.util.List.of());
    }

    @Override
    public boolean isReversed() {
        return state == CaravanState.RETURNING;
    }

    @Override
    public double getSpeedMultiplier(ServerLevel level, VillageSavedData data) {
        return data.getRouteById(routeId)
                .flatMap(r -> data.getRoadById(r.getConnectionId()))
                .map(TradeRoad::getSpeedMultiplier)
                .orElse(1.0);
    }

    @Override
    public void onSpawn(ServerLevel level, BlockPos spawnPos, VillageSavedData data) {
        // Delegate to the existing spawn logic on CaravanSavedData.
        // We can't call the private method directly from here, so the
        // SavedData provides a public spawn helper that takes a Caravan.
        CaravanSavedData.get(level).spawnCaravanEntities(this, spawnPos, level, data);
    }

    @Override
    public void onDespawn(ServerLevel level) {
        CaravanSavedData.get(level).despawnCaravanEntities(this, level);
    }

    @Override
    public void onTickSpawned(ServerLevel level, VillageSavedData data) {
        // CaravanMerchantGoal handles per-tick walking; nothing here
    }

    @Override
    public void onTickSimulated(ServerLevel level, VillageSavedData data) {
        // Engine advances progress; nothing else needed
    }

    @Override
    public void onPathComplete(ServerLevel level, VillageSavedData data) {
        if (state == CaravanState.OUTBOUND) {
            state = CaravanState.DELIVERING;
        }
        // RETURNING completion is handled by CaravanSavedData.tick which
        // checks for progress >= 1.0 and removes the caravan
    }
    public void onDespawned() {
        this.isSpawned = false;
        roster.clearSpawned();
    }
}