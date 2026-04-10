// src/main/java/tterrag1112/life_in_the_village/Village/Economy/Trade/RoadEvent.java
package tterrag1112.life_in_the_village.Village.Economy.Trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.monster.illager.Pillager;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.ModEntities;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Travel.TravellingGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A scheduled or active happening on a trade road — a bandit ambush,
 * a wandering trader, a pilgrim group.
 *
 * <h3>Phase 7c migration</h3>
 * RoadEvent now implements {@link TravellingGroup}, sharing the
 * spawn/despawn/progress lifecycle with caravans through
 * {@link tterrag1112.life_in_the_village.Village.Travel.TravellingGroupEngine}.
 * The dedicated RoadEventRealisationSystem from Phase 7b is gone —
 * its job is now done by the engine.
 *
 * <h3>Persistence</h3>
 * The codec persists the scheduled state. Spawned entity UUIDs are
 * transient and rebuilt by {@link #onSpawn} when a player approaches.
 */
public class RoadEvent implements TravellingGroup {

    // =========================================================================
    // Type and state enums
    // =========================================================================

    public enum EventType {
        BANDIT_AMBUSH,
        WANDERING_TRADER,
        PILGRIM_GROUP;

        public static final Codec<EventType> CODEC = Codec.STRING.xmap(
                EventType::valueOf, EventType::name);
    }

    public enum EventState {
        SCHEDULED,
        ACTIVE,
        FINISHED;

        public static final Codec<EventState> CODEC = Codec.STRING.xmap(
                EventState::valueOf, EventState::name);
    }

    // =========================================================================
    // Codec
    // =========================================================================

    public static final Codec<RoadEvent> CODEC = RecordCodecBuilder.create(i ->
            i.group(
                    UUIDUtil.CODEC.fieldOf("eventId")
                            .forGetter(e -> e.eventId),
                    UUIDUtil.CODEC.fieldOf("roadId")
                            .forGetter(e -> e.roadId),
                    EventType.CODEC.fieldOf("type")
                            .forGetter(e -> e.type),
                    EventState.CODEC.fieldOf("state")
                            .forGetter(e -> e.state),
                    Codec.FLOAT.fieldOf("progress")
                            .forGetter(e -> e.progress),
                    Codec.FLOAT.fieldOf("travelSpeed")
                            .forGetter(e -> e.travelSpeed),
                    Codec.LONG.fieldOf("scheduledTick")
                            .forGetter(e -> e.scheduledTick),
                    Codec.LONG.fieldOf("expiresTick")
                            .forGetter(e -> e.expiresTick),
                    Codec.INT.optionalFieldOf("entityCount", 1)
                            .forGetter(e -> e.entityCount)
            ).apply(i, RoadEvent::new));

    // =========================================================================
    // Persistent state
    // =========================================================================

    private final UUID eventId;
    private final UUID roadId;
    private final EventType type;
    private EventState state;
    private float progress;
    private final float travelSpeed;
    private final long scheduledTick;
    private final long expiresTick;
    private final int entityCount;

    /** Spawned entity UUIDs — transient, rebuilt on each spawn. */
    private final List<UUID> spawnedEntityIds = new ArrayList<>();

    public RoadEvent(UUID eventId, UUID roadId,
                     EventType type, EventState state,
                     float progress, float travelSpeed,
                     long scheduledTick, long expiresTick,
                     int entityCount) {
        this.eventId       = eventId;
        this.roadId        = roadId;
        this.type          = type;
        // Ensure deserialised events always come back as SCHEDULED —
        // entity UUIDs were transient and the spawned ones no longer exist
        this.state         = (state == EventState.ACTIVE) ? EventState.SCHEDULED : state;
        this.progress      = progress;
        this.travelSpeed   = travelSpeed;
        this.scheduledTick = scheduledTick;
        this.expiresTick   = expiresTick;
        this.entityCount   = entityCount;
    }

    // =========================================================================
    // Factories
    // =========================================================================

    public static RoadEvent banditAmbush(UUID roadId, float progress,
                                         long currentTick, int banditCount) {
        return new RoadEvent(
                UUID.randomUUID(), roadId,
                EventType.BANDIT_AMBUSH, EventState.SCHEDULED,
                progress, 0.0f,
                currentTick, currentTick + 24000L * 3,
                banditCount);
    }

    public static RoadEvent wanderingTrader(UUID roadId, long currentTick) {
        return new RoadEvent(
                UUID.randomUUID(), roadId,
                EventType.WANDERING_TRADER, EventState.SCHEDULED,
                0.0f, 0.0001f,
                currentTick, currentTick + 24000L * 14,
                1);
    }

    public static RoadEvent pilgrimGroup(UUID roadId, long currentTick,
                                         int pilgrimCount) {
        return new RoadEvent(
                UUID.randomUUID(), roadId,
                EventType.PILGRIM_GROUP, EventState.SCHEDULED,
                0.0f, 0.00015f,
                currentTick, currentTick + 24000L * 7,
                pilgrimCount);
    }

    // =========================================================================
    // Bookkeeping
    // =========================================================================

    public boolean isExpired(long currentTick) {
        return currentTick >= expiresTick;
    }

    public boolean isFinished() {
        return state == EventState.FINISHED;
    }

    // =========================================================================
    // TravellingGroup implementation
    // =========================================================================

    @Override
    public UUID groupId() { return eventId; }

    @Override
    public List<BlockPos> getPath(ServerLevel level, VillageSavedData data) {
        return data.getRoadById(roadId)
                .map(TradeRoad::getBlocks)
                .orElse(List.of());
    }

    @Override
    public boolean isReversed() { return false; }

    @Override
    public double getProgress() { return progress; }

    @Override
    public void setProgress(double progress) {
        this.progress = (float) Math.max(0, Math.min(1, progress));
    }

    @Override
    public double getSpeedMultiplier(ServerLevel level, VillageSavedData data) {
        // travelSpeed is per-tick fraction; the engine multiplies by
        // BASE_PROGRESS_PER_TICK. To convert: speed multiplier =
        // (travelSpeed / BASE_PROGRESS_PER_TICK).
        // Static events (travelSpeed == 0) return 0 so the engine
        // skips progress advancement entirely.
        if (travelSpeed <= 0) return 0;
        return travelSpeed / 0.002;
    }

    @Override
    public boolean isSpawned() { return state == EventState.ACTIVE; }

    @Override
    public List<UUID> getEntityIds() { return spawnedEntityIds; }

    @Override
    public void onSpawn(ServerLevel level, BlockPos pos, VillageSavedData data) {
        spawnedEntityIds.clear();
        switch (type) {
            case BANDIT_AMBUSH    -> spawnBandits(level, pos);
            case WANDERING_TRADER -> spawnTrader(level, pos);
            case PILGRIM_GROUP    -> spawnPilgrims(level, pos);
        }
        if (!spawnedEntityIds.isEmpty()) {
            state = EventState.ACTIVE;
            System.out.println("RoadEvent: spawned " + type
                    + " at " + pos.toShortString() + " ("
                    + spawnedEntityIds.size() + " entities)");
        }
    }

    @Override
    public void onDespawn(ServerLevel level) {
        for (UUID id : spawnedEntityIds) {
            var ent = level.getEntity(id);
            if (ent != null && !ent.isRemoved()) ent.discard();
        }
        spawnedEntityIds.clear();
        state = EventState.SCHEDULED;
    }

    @Override
    public void onTickSpawned(ServerLevel level, VillageSavedData data) {
        // Spawned entity AI handles its own behaviour — for now nothing
    }

    @Override
    public void onTickSimulated(ServerLevel level, VillageSavedData data) {
        // No extra simulated work
    }

    @Override
    public void onPathComplete(ServerLevel level, VillageSavedData data) {
        // Travellers finish; static events ignore (their travelSpeed is 0
        // so the engine never calls this)
        state = EventState.FINISHED;
    }

    // =========================================================================
    // Spawn implementations
    // =========================================================================

    private void spawnBandits(ServerLevel level, BlockPos pos) {
        for (int i = 0; i < entityCount; i++) {
            try {
                Pillager bandit = net.minecraft.world.entity.EntityType.PILLAGER
                        .create(level, EntitySpawnReason.EVENT);
                if (bandit == null) continue;
                bandit.setPos(
                        pos.getX() + 0.5 + (i - 1) * 1.5,
                        pos.getY() + 1,
                        pos.getZ() + 0.5);
                bandit.setCustomName(
                        net.minecraft.network.chat.Component.literal("Bandit"));
                level.addFreshEntity(bandit);
                spawnedEntityIds.add(bandit.getUUID());
            } catch (Exception e) {
                System.out.println("RoadEvent: bandit spawn failed: " + e.getMessage());
            }
        }
    }

    private void spawnTrader(ServerLevel level, BlockPos pos) {
        try {
            TownspersonMob trader = ModEntities.TOWNSPERSON.get()
                    .create(level, EntitySpawnReason.EVENT);
            if (trader == null) return;
            trader.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            trader.finalizeSpawn(level,
                    level.getCurrentDifficultyAt(pos),
                    EntitySpawnReason.EVENT, null);
            trader.setProfession(Profession.WANDERING_TRADER);
            trader.setFamilyRole(FamilyRole.UNASSIGNED);
            level.addFreshEntity(trader);
            spawnedEntityIds.add(trader.getUUID());
        } catch (Exception e) {
            System.out.println("RoadEvent: trader spawn failed: " + e.getMessage());
        }
    }

    private void spawnPilgrims(ServerLevel level, BlockPos pos) {
        for (int i = 0; i < entityCount; i++) {
            try {
                TownspersonMob pilgrim = ModEntities.TOWNSPERSON.get()
                        .create(level, EntitySpawnReason.EVENT);
                if (pilgrim == null) continue;
                pilgrim.setPos(
                        pos.getX() + 0.5 + (i - 1) * 1.0,
                        pos.getY() + 1,
                        pos.getZ() + 0.5);
                pilgrim.finalizeSpawn(level,
                        level.getCurrentDifficultyAt(pos),
                        EntitySpawnReason.EVENT, null);
                pilgrim.setProfession(Profession.CITIZEN);
                pilgrim.setFamilyRole(FamilyRole.UNASSIGNED);
                level.addFreshEntity(pilgrim);
                spawnedEntityIds.add(pilgrim.getUUID());
            } catch (Exception e) {
                System.out.println("RoadEvent: pilgrim spawn failed: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Getters (preserved for codec and external queries)
    // =========================================================================

    public UUID       getEventId()       { return eventId; }
    public UUID       getRoadId()        { return roadId; }
    public EventType  getType()          { return type; }
    public EventState getState()         { return state; }
    public float      getTravelSpeed()   { return travelSpeed; }
    public long       getScheduledTick() { return scheduledTick; }
    public long       getExpiresTick()   { return expiresTick; }
    public int        getEntityCount()   { return entityCount; }
}