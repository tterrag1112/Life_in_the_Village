package tterrag1112.life_in_the_village.Npc.Visitor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 29 — visitor metadata bolted onto a regular
 * {@code TownspersonMob} as a component (no separate entity class).
 * This is the same component pattern used for Phase 3
 * {@code PietyComponent} / {@code HealthComponent} — keeps the
 * visitor flux engine off the entity-registration boilerplate path
 * that a separate {@code Visitor extends TownspersonMob} subclass
 * would force.
 *
 * <p>An NPC is "a visitor" iff its {@link #visitorType} is non-null
 * and {@link #settledPermanently} is false. Refugees that the leader
 * accepts flip the settled flag to true so the
 * {@link tterrag1112.life_in_the_village.Events.TickSystems}
 * despawn check leaves them alone.</p>
 */
public final class VisitorState {

    private static final String SAVE_KEY = "visitorState";

    /** Null when the NPC is a regular resident. */
    private VisitorType visitorType;
    /** Origin village id when the visitor came from another village. */
    private UUID originVillageId;
    /** Game tick of arrival — drives the 7-day max-stay safety net. */
    private long arrivalTick;
    /** Soft target departure tick from the itinerary planner. The
     *  daily despawn pass uses {@code max(expectedDeparture, arrival
     *  + MAX_STAY_TICKS)} as the hard ceiling. */
    private long expectedDepartureTick;
    private final List<VisitorItinerary> itinerary = new ArrayList<>();
    private int currentItineraryIndex;
    private boolean settledPermanently;

    public VisitorState() {}

    private VisitorState(VisitorType type, UUID originVillageId,
                         long arrivalTick, long expectedDepartureTick,
                         List<VisitorItinerary> itinerary,
                         int currentItineraryIndex,
                         boolean settledPermanently) {
        this.visitorType = type;
        this.originVillageId = originVillageId;
        this.arrivalTick = arrivalTick;
        this.expectedDepartureTick = expectedDepartureTick;
        if (itinerary != null) this.itinerary.addAll(itinerary);
        this.currentItineraryIndex = currentItineraryIndex;
        this.settledPermanently = settledPermanently;
    }

    // ── Identity / queries ────────────────────────────────────────────────

    public boolean isVisitor() {
        return visitorType != null && !settledPermanently;
    }

    public Optional<VisitorType> type() { return Optional.ofNullable(visitorType); }

    public Optional<UUID> originVillageId() { return Optional.ofNullable(originVillageId); }

    public long arrivalTick()           { return arrivalTick; }
    public long expectedDepartureTick() { return expectedDepartureTick; }

    public List<VisitorItinerary> itinerary() {
        return Collections.unmodifiableList(itinerary);
    }

    public int currentItineraryIndex() { return currentItineraryIndex; }

    public Optional<VisitorItinerary> currentStop() {
        if (currentItineraryIndex < 0 || currentItineraryIndex >= itinerary.size()) {
            return Optional.empty();
        }
        return Optional.of(itinerary.get(currentItineraryIndex));
    }

    public boolean isItineraryComplete() {
        return currentItineraryIndex >= itinerary.size();
    }

    public boolean settledPermanently() { return settledPermanently; }

    /** True when the visitor's stay should end (itinerary complete or
     *  hard 7-day ceiling per spec line 318 reached). */
    public boolean shouldDespawn(long currentTick) {
        if (settledPermanently) return false;
        if (visitorType == null) return false;
        if (isItineraryComplete()) return true;
        long ceiling = arrivalTick + 7L * 24000L;
        return currentTick >= ceiling;
    }

    // ── Mutators ──────────────────────────────────────────────────────────

    /** Initialises the visitor state when an NPC is spawned by the
     *  flux engine. Replaces any prior state. */
    public void beginVisit(VisitorType type, UUID originVillageId, long arrivalTick,
                           long expectedDepartureTick,
                           List<VisitorItinerary> plannedItinerary) {
        this.visitorType           = type;
        this.originVillageId       = originVillageId;
        this.arrivalTick           = arrivalTick;
        this.expectedDepartureTick = expectedDepartureTick;
        this.itinerary.clear();
        if (plannedItinerary != null) this.itinerary.addAll(plannedItinerary);
        this.currentItineraryIndex = 0;
        this.settledPermanently    = false;
    }

    public void advanceItinerary() {
        if (currentItineraryIndex < itinerary.size()) currentItineraryIndex++;
    }

    public void settlePermanently() {
        this.settledPermanently = true;
    }

    /** Used by debug / settlement paths to re-seat a visitor's
     *  planned exit. */
    public void setExpectedDepartureTick(long tick) {
        this.expectedDepartureTick = tick;
    }

    // ── Persistence ───────────────────────────────────────────────────────

    public void save(ValueOutput output) { output.store(SAVE_KEY, CODEC, this); }

    public boolean load(ValueInput input) {
        var read = input.read(SAVE_KEY, CODEC);
        if (read.isEmpty()) return false;
        VisitorState s = read.get();
        this.visitorType            = s.visitorType;
        this.originVillageId        = s.originVillageId;
        this.arrivalTick            = s.arrivalTick;
        this.expectedDepartureTick  = s.expectedDepartureTick;
        this.itinerary.clear();
        this.itinerary.addAll(s.itinerary);
        this.currentItineraryIndex  = s.currentItineraryIndex;
        this.settledPermanently     = s.settledPermanently;
        return true;
    }

    public static final Codec<VisitorState> CODEC = RecordCodecBuilder.create(i -> i.group(
            VisitorType.CODEC.optionalFieldOf("type")
                    .forGetter(s -> Optional.ofNullable(s.visitorType)),
            UUIDUtil.CODEC.optionalFieldOf("originVillageId")
                    .forGetter(s -> Optional.ofNullable(s.originVillageId)),
            Codec.LONG.optionalFieldOf("arrivalTick", 0L)
                    .forGetter(s -> s.arrivalTick),
            Codec.LONG.optionalFieldOf("expectedDepartureTick", 0L)
                    .forGetter(s -> s.expectedDepartureTick),
            VisitorItinerary.CODEC.listOf().optionalFieldOf("itinerary", List.of())
                    .forGetter(s -> List.copyOf(s.itinerary)),
            Codec.INT.optionalFieldOf("currentItineraryIndex", 0)
                    .forGetter(VisitorState::currentItineraryIndex),
            Codec.BOOL.optionalFieldOf("settledPermanently", false)
                    .forGetter(VisitorState::settledPermanently)
    ).apply(i, (typeOpt, originOpt, arrival, departure, itin, idx, settled) ->
            new VisitorState(typeOpt.orElse(null), originOpt.orElse(null),
                    arrival, departure, itin, idx, settled)));
}
