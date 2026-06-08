package tterrag1112.life_in_the_village.Village.Travel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.GraphTradeRouteEstablisher;
import tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoute;
import tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Religion Rework R3e-3b — a single resident on a religious pilgrimage, modelled
 * as a one-member {@link TravellingGroup}. Mirrors the {@code Caravan} traveller
 * lifecycle (the engine drives realized↔simulated + progress + completion) but
 * carries no trade payload: one principal (the departing adherent), their faith,
 * a home village, a same-faith destination village, and the {@link TradeRoute}
 * between them (route-connected destinations reuse the road path + map polyline).
 *
 * <p>R3e-3b-2 added the destination dwell: OUTBOUND → <b>AT_DESTINATION</b> (a
 * bounded dwell during which the host's grand festival is attended, setting
 * {@link #attended}) → RETURNING. The dwell state machine lives here (driven by
 * the engine's spawned/simulated ticks); {@code PilgrimTravelBehavior} adds the
 * realized venue-walk. {@link PilgrimageSavedData} owns realize/dehydrate, the
 * return reintegration, and the boon (scaled by {@link #attended}).</p>
 */
public class Pilgrimage implements TravellingGroup {

    /** Bounded dwell at the destination (long enough to overlap an active grand
     *  festival, which runs 12000t). */
    public static final long DWELL_TICKS = 4000L;

    /** The grand-festival EventTypes a pilgrim travels to attend (R3d-2). */
    private static final Set<VillageEvent.EventType> GRAND_FESTIVALS = Set.of(
            VillageEvent.EventType.HARVEST_HOME, VillageEvent.EventType.GREAT_WEAVING,
            VillageEvent.EventType.TIDES_RETURN, VillageEvent.EventType.FOUNDING_DAY);

    /** Whether {@code type} is a grand-festival gathering a pilgrim travels for. */
    public static boolean isGrandFestival(VillageEvent.EventType type) {
        return GRAND_FESTIVALS.contains(type);
    }

    public enum PilgrimState {
        OUTBOUND,        // home → destination
        AT_DESTINATION,  // dwelling at the destination, attending the festival
        RETURNING;       // destination → home

        public static final Codec<PilgrimState> CODEC =
                Codec.STRING.xmap(PilgrimState::valueOf, PilgrimState::name);
    }

    public static final Codec<Pilgrimage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                    .fieldOf("pilgrimageId").forGetter(Pilgrimage::getPilgrimageId),
            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                    .fieldOf("routeId").forGetter(Pilgrimage::getRouteId),
            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                    .fieldOf("originVillageId").forGetter(Pilgrimage::getOriginVillageId),
            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                    .fieldOf("destVillageId").forGetter(Pilgrimage::getDestVillageId),
            Codec.STRING.xmap(UUID::fromString, UUID::toString)
                    .fieldOf("principalId").forGetter(Pilgrimage::getPrincipalId),
            PilgrimState.CODEC.fieldOf("state").forGetter(Pilgrimage::getState),
            Codec.DOUBLE.fieldOf("progress").forGetter(Pilgrimage::getProgress),
            Codec.LONG.fieldOf("dispatchTick").forGetter(Pilgrimage::getDispatchTick),
            // R3e-3b-2 additions — optional so any R3e-3b-1-era saved pilgrimage loads.
            Codec.STRING.optionalFieldOf("faith", "").forGetter(Pilgrimage::getFaith),
            Codec.LONG.optionalFieldOf("dwellUntilTick", 0L).forGetter(p -> p.dwellUntilTick),
            Codec.BOOL.optionalFieldOf("attended", false).forGetter(Pilgrimage::hasAttended)
    ).apply(i, Pilgrimage::new));

    private final UUID pilgrimageId;
    private final UUID routeId;
    private final UUID originVillageId;
    private final UUID destVillageId;
    private UUID principalId;
    private PilgrimState state;
    private double progress;
    private final long dispatchTick;
    private final String faith;
    private long dwellUntilTick;
    private boolean attended;

    private transient boolean isSpawned = false;

    public Pilgrimage(UUID pilgrimageId, UUID routeId, UUID originVillageId,
                      UUID destVillageId, UUID principalId, PilgrimState state,
                      double progress, long dispatchTick,
                      String faith, long dwellUntilTick, boolean attended) {
        this.pilgrimageId    = pilgrimageId;
        this.routeId         = routeId;
        this.originVillageId = originVillageId;
        this.destVillageId   = destVillageId;
        this.principalId     = principalId;
        this.state           = state;
        this.progress        = progress;
        this.dispatchTick    = dispatchTick;
        this.faith           = faith == null ? "" : faith;
        this.dwellUntilTick  = dwellUntilTick;
        this.attended        = attended;
    }

    public static Pilgrimage create(UUID routeId, UUID originVillageId, UUID destVillageId,
                                    UUID principalId, String faith, long currentTick) {
        return new Pilgrimage(UUID.randomUUID(), routeId, originVillageId, destVillageId,
                principalId, PilgrimState.OUTBOUND, 0.0, currentTick, faith, 0L, false);
    }

    // ── Getters / setters ───────────────────────────────────────────────────
    public UUID getPilgrimageId()    { return pilgrimageId; }
    public UUID getRouteId()         { return routeId; }
    public UUID getOriginVillageId() { return originVillageId; }
    public UUID getDestVillageId()   { return destVillageId; }
    public UUID getPrincipalId()     { return principalId; }
    public void setPrincipalId(UUID id) { this.principalId = id; }
    public PilgrimState getState()   { return state; }
    public void setState(PilgrimState s) { this.state = s; }
    public long getDispatchTick()    { return dispatchTick; }
    public String getFaith()         { return faith; }
    public boolean hasAttended()     { return attended; }
    public void setSpawned(boolean s){ this.isSpawned = s; }

    // ── TravellingGroup ─────────────────────────────────────────────────────
    @Override public UUID groupId() { return pilgrimageId; }

    @Override
    public List<BlockPos> getPath(ServerLevel level, VillageSavedData data) {
        TradeRoute route = data.getRouteById(routeId).orElse(null);
        if (route == null) return List.of();
        if (route.hasSegments()) {
            return Caravan.resolveSegmentBlocks(level, route.getSegments());
        }
        if (route.hasGraphPath()) {
            return GraphTradeRouteEstablisher.resolveGraphBlocks(
                    WorldRoadSavedData.get(level).getGraph(),
                    route.getEdgeIds(), route.getRouteStartNodeId());
        }
        return List.of();
    }

    @Override public boolean isReversed() { return state == PilgrimState.RETURNING; }

    @Override public double getProgress() { return progress; }
    @Override public void setProgress(double p) { this.progress = Math.max(0.0, Math.min(1.0, p)); }

    @Override
    public double getSpeedMultiplier(ServerLevel level, VillageSavedData data) {
        return 1.0; // a pilgrim walks at the base rate (no road-quality bonus)
    }

    @Override public boolean isSpawned() { return isSpawned; }

    @Override
    public List<UUID> getEntityIds() {
        return isSpawned && principalId != null ? List.of(principalId) : List.of();
    }

    @Override
    public void onSpawn(ServerLevel level, BlockPos spawnPos, VillageSavedData data) {
        PilgrimageSavedData.get(level).realizePilgrim(this, spawnPos, level, data);
    }

    @Override
    public void onDespawn(ServerLevel level) {
        PilgrimageSavedData.get(level).dehydratePilgrim(this, level);
    }

    @Override public void onTickSpawned(ServerLevel level, VillageSavedData data) {
        tickDwell(level, data, level.getGameTime()); // dwell state machine runs in both states
    }

    @Override public void onTickSimulated(ServerLevel level, VillageSavedData data) {
        tickDwell(level, data, level.getGameTime());
    }

    @Override
    public void onPathComplete(ServerLevel level, VillageSavedData data) {
        // Simulated-side leg completion. OUTBOUND arrival → dwell at the
        // destination (attend), THEN return. RETURNING completion is handled by
        // PilgrimageSavedData.tick (reintegration).
        if (state == PilgrimState.OUTBOUND) {
            arriveAtDestination(level.getGameTime());
        }
    }

    /** OUTBOUND → AT_DESTINATION dwell. Idempotent (only transitions once). */
    public void arriveAtDestination(long now) {
        if (state != PilgrimState.OUTBOUND) return;
        state = PilgrimState.AT_DESTINATION;
        dwellUntilTick = now + DWELL_TICKS;
        progress = 1.0;
    }

    /**
     * The dwell state machine: while AT_DESTINATION, marks {@link #attended} when
     * a grand festival of this pilgrim's faith is active at the destination, and
     * once the dwell elapses begins the RETURNING leg. Runs whether the pilgrim
     * is realized or simulated.
     */
    private void tickDwell(ServerLevel level, VillageSavedData data, long now) {
        if (state != PilgrimState.AT_DESTINATION) return;
        if (!attended && festivalActiveAtDestination(data)) attended = true;
        if (now >= dwellUntilTick) {
            state = PilgrimState.RETURNING;
            progress = 0.0;
        }
    }

    /** True when a grand festival of this pilgrim's faith is currently active at
     *  the destination village (the R3e-2b faith stamp distinguishes faiths). */
    public boolean festivalActiveAtDestination(VillageSavedData data) {
        for (VillageEvent e : data.getActiveEventsForVillage(destVillageId)) {
            if (!e.isActive() || !GRAND_FESTIVALS.contains(e.getType())) continue;
            String evFaith = e.getEventData().get(CeremonyBlessings.FAITH_KEY);
            if (faith.isEmpty() || faith.equals(evFaith) || evFaith == null) return true;
        }
        return false;
    }

    public void onDespawned() {
        this.isSpawned = false;
    }
}
