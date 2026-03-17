package tterrag1112.life_in_the_village.Village.Event;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VillageEvent {

    public enum EventType {
        HARVEST_FESTIVAL,
        MARKET_DAY,
        FESTIVAL_OF_LIGHTS,
        TRAINING_DAY,
        VILLAGE_FAIR;

        public long getDurationTicks() {
            return switch (this) {
                case MARKET_DAY       -> 24000L;      // 1 day
                case TRAINING_DAY     -> 12000L;      // half day
                case HARVEST_FESTIVAL -> 48000L;      // 2 days
                case VILLAGE_FAIR     -> 48000L;      // 2 days
                case FESTIVAL_OF_LIGHTS -> 72000L;    // 3 days
            };
        }

        public boolean isAnnual() {
            return switch (this) {
                case HARVEST_FESTIVAL   -> true;  // day 100 each year
                case FESTIVAL_OF_LIGHTS -> true;  // day 200 each year
                default                 -> false;
            };
        }

        public int annualDay() {
            return switch (this) {
                case HARVEST_FESTIVAL   -> 100;
                case FESTIVAL_OF_LIGHTS -> 200;
                default                 -> -1;
            };
        }

        // Min prosperity score (0-100) needed for random trigger
        public int minProsperity() {
            return switch (this) {
                case MARKET_DAY   -> 20;
                case TRAINING_DAY -> 30;
                case VILLAGE_FAIR -> 50;
                default           -> 0;
            };
        }

        // Chance per day of triggering (if prosperity met)
        public float randomChance() {
            return switch (this) {
                case MARKET_DAY   -> 0.15f;
                case TRAINING_DAY -> 0.10f;
                case VILLAGE_FAIR -> 0.08f;
                default           -> 0.0f;
            };
        }
    }

    public enum EventStatus {
        ANNOUNCED,  // announced but not started
        ACTIVE,     // currently happening
        ENDED
    }

    public static final Codec<VillageEvent> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                                    .fieldOf("id").forGetter(VillageEvent::getId),
                            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                                    .fieldOf("villageId").forGetter(VillageEvent::getVillageId),
                            Codec.STRING.xmap(EventType::valueOf, EventType::name)
                                    .fieldOf("type").forGetter(VillageEvent::getType),
                            Codec.STRING.xmap(EventStatus::valueOf, EventStatus::name)
                                    .fieldOf("status").forGetter(VillageEvent::getStatus),
                            Codec.LONG.fieldOf("startTick")
                                    .forGetter(VillageEvent::getStartTick),
                            Codec.LONG.fieldOf("endTick")
                                    .forGetter(VillageEvent::getEndTick),
                            BlockPos.CODEC.listOf()
                                    .optionalFieldOf("decorations", new ArrayList<>())
                                    .forGetter(VillageEvent::getDecorations)
                    ).apply(instance, VillageEvent::new)
            );

    private final UUID id;
    private final UUID villageId;
    private final EventType type;
    private EventStatus status;
    private final long startTick;
    private final long endTick;
    private final List<BlockPos> decorations; // placed decoration blocks

    public VillageEvent(UUID id, UUID villageId, EventType type,
                        EventStatus status, long startTick,
                        long endTick, List<BlockPos> decorations) {
        this.id          = id;
        this.villageId   = villageId;
        this.type        = type;
        this.status      = status;
        this.startTick   = startTick;
        this.endTick     = endTick;
        this.decorations = new ArrayList<>(decorations);
    }

    public static VillageEvent create(UUID villageId, EventType type,
                                      long currentTick) {
        long start = currentTick + 1200; // announce 1 minute before
        long end   = start + type.getDurationTicks();
        return new VillageEvent(UUID.randomUUID(), villageId, type,
                EventStatus.ANNOUNCED, start, end, new ArrayList<>());
    }

    public UUID getId()                   { return id; }
    public UUID getVillageId()            { return villageId; }
    public EventType getType()            { return type; }
    public EventStatus getStatus()        { return status; }
    public long getStartTick()            { return startTick; }
    public long getEndTick()              { return endTick; }
    public List<BlockPos> getDecorations(){ return decorations; }

    public void setStatus(EventStatus s)  { this.status = s; }
    public void addDecoration(BlockPos p) { decorations.add(p); }

    public boolean isActive()    { return status == EventStatus.ACTIVE; }
    public boolean isAnnounced() { return status == EventStatus.ANNOUNCED; }

    public boolean shouldStart(long tick) {
        return status == EventStatus.ANNOUNCED && tick >= startTick;
    }

    public boolean shouldEnd(long tick) {
        return status == EventStatus.ACTIVE && tick >= endTick;
    }

    public double getProgress(long tick) {
        if (!isActive()) return 0;
        return (double)(tick - startTick) / (endTick - startTick);
    }
}