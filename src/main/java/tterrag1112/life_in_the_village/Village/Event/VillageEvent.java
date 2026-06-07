package tterrag1112.life_in_the_village.Village.Event;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Gathering.CommunityGathering;
import tterrag1112.life_in_the_village.Village.Gathering.GatheringStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A scheduled / active / completed village event.
 *
 * <p>Phase 3 shipped a 5-type catalogue (HARVEST_FESTIVAL, MARKET_DAY,
 * FESTIVAL_OF_LIGHTS, TRAINING_DAY, VILLAGE_FAIR). Phase 5 doc 32
 * expands the catalogue to ~33 types across 8 categories and adds
 * attendance, primary-subject, and arbitrary payload fields. The
 * codec stays backwards-compatible — every new field is optional, so
 * pre-Phase-5 saves still load.</p>
 *
 * <p>Spec: {@code docs/npc_redesign/32-events-expanded.md}.</p>
 */
public class VillageEvent implements CommunityGathering {

    public enum EventType {
        // ── Phase 3 originals (Seasonal / Cultural) ──────────────────
        HARVEST_FESTIVAL,
        MARKET_DAY,
        FESTIVAL_OF_LIGHTS,
        TRAINING_DAY,
        VILLAGE_FAIR,

        // ── Seasonal (Phase 5) ────────────────────────────────────────
        SPRING_EQUINOX,
        WINTER_FEAST,
        SUMMER_MARKET,

        // ── Religious (per-culture) ──────────────────────────────────
        SUNSTEAD_EQUINOX,
        LOOM_THREADING,
        TIDECALL_FULL_MOON,
        FORGE_CREED_KINGDOM_DAY,

        // ── Life-stage ────────────────────────────────────────────────
        COMING_OF_AGE,
        WEDDING,
        FUNERAL,
        NAMING_CEREMONY,

        // ── Economic ──────────────────────────────────────────────────
        CARAVAN_ARRIVAL,
        GUILD_FAIR,
        CRAFT_CONTEST,

        // ── Civic ─────────────────────────────────────────────────────
        TOWN_MEETING,
        TRIAL,
        OFFICE_INAUGURATION,
        LAW_ENACTMENT_ANNOUNCEMENT,

        // ── Cultural ──────────────────────────────────────────────────
        MINSTREL_PERFORMANCE,
        STORYTELLING_NIGHT,
        SCHOLAR_LECTURE,

        // ── Notable visit ────────────────────────────────────────────
        ENVOY_RECEPTION,
        SCHOLAR_EXCHANGE,
        PILGRIM_CONVERGENCE,

        // ── Crisis ────────────────────────────────────────────────────
        PLAGUE_OUTBREAK,
        FIRE,
        MILITIA_CALL,
        FAMINE;

        /**
         * Coarse grouping for filtering / UI / scheduler dispatch.
         * Spec: {@code 32-events-expanded.md} § Data model.
         */
        public EventCategory category() {
            return switch (this) {
                case HARVEST_FESTIVAL, FESTIVAL_OF_LIGHTS, VILLAGE_FAIR,
                     SPRING_EQUINOX, WINTER_FEAST, SUMMER_MARKET
                        -> EventCategory.SEASONAL_FESTIVAL;
                case SUNSTEAD_EQUINOX, LOOM_THREADING, TIDECALL_FULL_MOON,
                     FORGE_CREED_KINGDOM_DAY
                        -> EventCategory.RELIGIOUS_RITE;
                case COMING_OF_AGE, WEDDING, FUNERAL, NAMING_CEREMONY
                        -> EventCategory.LIFE_STAGE_RITE;
                case MARKET_DAY, CARAVAN_ARRIVAL, GUILD_FAIR, CRAFT_CONTEST
                        -> EventCategory.ECONOMIC;
                case TOWN_MEETING, TRIAL, OFFICE_INAUGURATION,
                     LAW_ENACTMENT_ANNOUNCEMENT, TRAINING_DAY
                        -> EventCategory.CIVIC;
                case MINSTREL_PERFORMANCE, STORYTELLING_NIGHT, SCHOLAR_LECTURE
                        -> EventCategory.CULTURAL;
                case ENVOY_RECEPTION, SCHOLAR_EXCHANGE, PILGRIM_CONVERGENCE
                        -> EventCategory.NOTABLE_VISIT;
                case PLAGUE_OUTBREAK, FIRE, MILITIA_CALL, FAMINE
                        -> EventCategory.CRISIS;
            };
        }

        public long getDurationTicks() {
            return switch (this) {
                case MARKET_DAY                     -> 24000L;
                case TRAINING_DAY                   -> 12000L;
                case HARVEST_FESTIVAL, VILLAGE_FAIR -> 48000L;
                case FESTIVAL_OF_LIGHTS             -> 72000L;
                case SUMMER_MARKET                  -> 24000L;
                case WINTER_FEAST                   -> 36000L;
                case SPRING_EQUINOX                 -> 12000L;
                case SUNSTEAD_EQUINOX, LOOM_THREADING,
                     TIDECALL_FULL_MOON, FORGE_CREED_KINGDOM_DAY
                                                    -> 6000L;
                case WEDDING                        -> 1200L;
                case FUNERAL                        -> 800L;
                case NAMING_CEREMONY                -> 600L;
                case COMING_OF_AGE                  -> 1200L;
                case TOWN_MEETING                   -> 1200L;
                case TRIAL                          -> 600L;
                case OFFICE_INAUGURATION,
                     LAW_ENACTMENT_ANNOUNCEMENT     -> 400L;
                case CRAFT_CONTEST                  -> 24000L;
                case GUILD_FAIR                     -> 24000L;
                case CARAVAN_ARRIVAL                -> 12000L;
                case ENVOY_RECEPTION                -> 2400L;
                case SCHOLAR_EXCHANGE               -> 6000L;
                case SCHOLAR_LECTURE                -> 2400L;
                case STORYTELLING_NIGHT             -> 3000L;
                case MINSTREL_PERFORMANCE           -> 3000L;
                case PILGRIM_CONVERGENCE            -> 12000L;
                case PLAGUE_OUTBREAK                -> 168000L; // ~7 days, resolved by health system
                case FIRE                           -> 1200L;
                case FAMINE                         -> 168000L;
                case MILITIA_CALL                   -> 12000L;
            };
        }

        public boolean isAnnual() {
            return switch (this) {
                case HARVEST_FESTIVAL, FESTIVAL_OF_LIGHTS,
                     SPRING_EQUINOX, WINTER_FEAST, SUMMER_MARKET -> true;
                default -> false;
            };
        }

        public int annualDay() {
            return switch (this) {
                case HARVEST_FESTIVAL   -> 100;
                case FESTIVAL_OF_LIGHTS -> 200;
                default                 -> -1;
            };
        }

        // Min prosperity score (0-100) needed for random trigger.
        public int minProsperity() {
            return switch (this) {
                case MARKET_DAY   -> 20;
                case TRAINING_DAY -> 30;
                case VILLAGE_FAIR -> 50;
                default           -> 0;
            };
        }

        // Chance per day of triggering (if prosperity met). Phase-5 types
        // schedule via specific sources (life events, calendar, crises),
        // not via this random roll.
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
        /** Pre-Phase-5 alias for SCHEDULED. Kept for save-data compatibility. */
        ANNOUNCED,
        ACTIVE,
        /** Pre-Phase-5 alias for COMPLETED. Kept for save-data compatibility. */
        ENDED,
        /** Phase 5: dropped before reaching ACTIVE (required attendee unavailable, etc.). */
        CANCELLED,
        /** Phase 5: started but cut short by a higher-priority event. */
        DISRUPTED
    }

    // ──────────────────────────────────────────────────────────────────
    // Codec
    //
    // 14 fields total — under DFU's 16-field cap. Every field added in
    // Phase 5 is wrapped in {@code optionalFieldOf} so events written by
    // pre-Phase-5 versions still deserialize.
    // ──────────────────────────────────────────────────────────────────

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<VillageEvent> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                            UUID_CODEC.fieldOf("id").forGetter(VillageEvent::getId),
                            UUID_CODEC.fieldOf("villageId").forGetter(VillageEvent::getVillageId),
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
                                    .forGetter(VillageEvent::getDecorations),
                            BlockPos.CODEC.optionalFieldOf("location")
                                    .forGetter(e -> Optional.ofNullable(e.location)),
                            UUID_CODEC.optionalFieldOf("primarySubjectId")
                                    .forGetter(e -> Optional.ofNullable(e.primarySubjectId)),
                            UUID_CODEC.listOf().optionalFieldOf("requiredAttendees", new ArrayList<>())
                                    .forGetter(VillageEvent::getRequiredAttendees),
                            UUID_CODEC.listOf().optionalFieldOf("invitedAttendees", new ArrayList<>())
                                    .forGetter(VillageEvent::getInvitedAttendees),
                            UUID_CODEC.listOf().optionalFieldOf("actualAttendees", new ArrayList<>())
                                    .forGetter(VillageEvent::getActualAttendees),
                            Codec.unboundedMap(Codec.STRING, Codec.STRING)
                                    .optionalFieldOf("eventData", new HashMap<>())
                                    .forGetter(VillageEvent::getEventData),
                            Codec.LONG.optionalFieldOf("completedTick", 0L)
                                    .forGetter(VillageEvent::getCompletedTick)
                    ).apply(instance, VillageEvent::fromCodec)
            );

    // ──────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────

    private final UUID id;
    private final UUID villageId;
    private final EventType type;
    private EventStatus status;
    private final long startTick;
    private final long endTick;
    private final List<BlockPos> decorations;
    private BlockPos location;                     // nullable
    private UUID primarySubjectId;                 // nullable
    private final List<UUID> requiredAttendees;
    private final List<UUID> invitedAttendees;
    private final List<UUID> actualAttendees;
    private final Map<String, String> eventData;
    private long completedTick;

    public VillageEvent(UUID id, UUID villageId, EventType type,
                        EventStatus status, long startTick,
                        long endTick, List<BlockPos> decorations) {
        this(id, villageId, type, status, startTick, endTick, decorations,
                null, null,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new HashMap<>(), 0L);
    }

    public VillageEvent(UUID id, UUID villageId, EventType type,
                        EventStatus status, long startTick, long endTick,
                        List<BlockPos> decorations,
                        BlockPos location, UUID primarySubjectId,
                        List<UUID> requiredAttendees, List<UUID> invitedAttendees,
                        List<UUID> actualAttendees,
                        Map<String, String> eventData, long completedTick) {
        this.id                 = id;
        this.villageId          = villageId;
        this.type               = type;
        this.status             = status;
        this.startTick          = startTick;
        this.endTick            = endTick;
        this.decorations        = new ArrayList<>(decorations);
        this.location           = location;
        this.primarySubjectId   = primarySubjectId;
        this.requiredAttendees  = new ArrayList<>(requiredAttendees);
        this.invitedAttendees   = new ArrayList<>(invitedAttendees);
        this.actualAttendees    = new ArrayList<>(actualAttendees);
        this.eventData          = new HashMap<>(eventData);
        this.completedTick      = completedTick;
    }

    /** Codec apply target — unwraps the optional fields. */
    private static VillageEvent fromCodec(UUID id, UUID villageId, EventType type,
                                          EventStatus status, long startTick,
                                          long endTick, List<BlockPos> decorations,
                                          Optional<BlockPos> location,
                                          Optional<UUID> primarySubjectId,
                                          List<UUID> requiredAttendees,
                                          List<UUID> invitedAttendees,
                                          List<UUID> actualAttendees,
                                          Map<String, String> eventData,
                                          long completedTick) {
        return new VillageEvent(id, villageId, type, status, startTick, endTick,
                decorations, location.orElse(null), primarySubjectId.orElse(null),
                requiredAttendees, invitedAttendees, actualAttendees,
                eventData, completedTick);
    }

    public static VillageEvent create(UUID villageId, EventType type,
                                      long currentTick) {
        long start = currentTick + 1200; // announce 1 minute before
        long end   = start + type.getDurationTicks();
        return new VillageEvent(UUID.randomUUID(), villageId, type,
                EventStatus.ANNOUNCED, start, end, new ArrayList<>());
    }

    /**
     * Creates a Phase-5 event with a primary subject and pre-populated
     * attendee lists. The scheduler uses this for life-event-driven
     * scheduling (weddings, funerals, etc.). The end tick is computed
     * from the type's default duration.
     */
    public static VillageEvent create(UUID villageId, EventType type, long scheduledTick,
                                      UUID primarySubjectId,
                                      List<UUID> requiredAttendees,
                                      List<UUID> invitedAttendees) {
        long end = scheduledTick + type.getDurationTicks();
        return new VillageEvent(UUID.randomUUID(), villageId, type,
                EventStatus.ANNOUNCED, scheduledTick, end, new ArrayList<>(),
                null, primarySubjectId,
                new ArrayList<>(requiredAttendees),
                new ArrayList<>(invitedAttendees),
                new ArrayList<>(),
                new HashMap<>(), 0L);
    }

    // ── Accessors ────────────────────────────────────────────────────

    public UUID getId()                            { return id; }
    public UUID getVillageId()                     { return villageId; }
    public EventType getType()                     { return type; }
    public EventCategory getCategory()             { return type.category(); }
    public EventStatus getStatus()                 { return status; }
    public long getStartTick()                     { return startTick; }
    public long getEndTick()                       { return endTick; }
    public long getCompletedTick()                 { return completedTick; }
    public List<BlockPos> getDecorations()         { return decorations; }
    public Optional<BlockPos> getLocation()        { return Optional.ofNullable(location); }
    public Optional<UUID> getPrimarySubjectId()    { return Optional.ofNullable(primarySubjectId); }
    public List<UUID> getRequiredAttendees()       { return requiredAttendees; }
    public List<UUID> getInvitedAttendees()        { return invitedAttendees; }
    public List<UUID> getActualAttendees()         { return actualAttendees; }
    public Map<String, String> getEventData()      { return eventData; }

    // ── Mutators ─────────────────────────────────────────────────────

    public void setStatus(EventStatus s)           { this.status = s; }
    public void setLocation(BlockPos pos)          { this.location = pos; }
    public void setPrimarySubjectId(UUID id)       { this.primarySubjectId = id; }
    public void setCompletedTick(long tick)        { this.completedTick = tick; }
    public void addDecoration(BlockPos p)          { decorations.add(p); }
    public void addRequiredAttendee(UUID id)       { if (!requiredAttendees.contains(id)) requiredAttendees.add(id); }
    public void addInvitedAttendee(UUID id)        { if (!invitedAttendees.contains(id)) invitedAttendees.add(id); }
    public void addActualAttendee(UUID id)         { if (!actualAttendees.contains(id)) actualAttendees.add(id); }
    public void putEventData(String key, String v) { eventData.put(key, v); }

    // ── Status queries ───────────────────────────────────────────────

    public boolean isActive()    { return status == EventStatus.ACTIVE; }
    public boolean isAnnounced() { return status == EventStatus.ANNOUNCED; }
    public boolean isCompleted() { return status == EventStatus.ENDED || status == EventStatus.CANCELLED; }

    public boolean shouldStart(long tick) {
        return status == EventStatus.ANNOUNCED && tick >= startTick;
    }

    public boolean shouldEnd(long tick) {
        return status == EventStatus.ACTIVE && tick >= endTick;
    }

    public double getProgress(long tick) {
        if (!isActive()) return 0;
        long span = Math.max(1L, endTick - startTick);
        return (double)(tick - startTick) / (double) span;
    }

    // ── CommunityGathering (R2a) ─────────────────────────────────────────
    // New interface methods delegating to the existing accessors — no
    // existing getter is renamed. A VillageEvent IS the gathering.

    @Override public UUID gatheringId()                  { return id; }
    @Override public UUID villageId()                    { return villageId; }
    @Override public Optional<BlockPos> gatheringLocation() { return getLocation(); }
    @Override public long startTick()                    { return startTick; }
    @Override public long endTick()                      { return endTick; }
    @Override public List<UUID> requiredAttendees()      { return requiredAttendees; }
    @Override public List<UUID> invitedAttendees()       { return invitedAttendees; }
    @Override public List<UUID> actualAttendees()        { return actualAttendees; }
    @Override public Optional<UUID> primarySubjectId()   { return getPrimarySubjectId(); }

    @Override
    public GatheringStatus gatheringStatus() {
        return switch (status) {
            case ANNOUNCED            -> GatheringStatus.SCHEDULED;
            case ACTIVE               -> GatheringStatus.ACTIVE;
            case ENDED                -> GatheringStatus.COMPLETED;
            case CANCELLED, DISRUPTED -> GatheringStatus.CANCELLED;
        };
    }
}
