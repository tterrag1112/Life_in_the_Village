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

import java.util.List;
import java.util.UUID;

/**
 * Religion Rework R3e-3b — a single resident on a religious pilgrimage, modelled
 * as a one-member {@link TravellingGroup}. Mirrors the {@code Caravan} traveller
 * lifecycle (the engine drives realized↔simulated + progress + completion) but
 * carries no trade payload: one principal (the departing adherent), a home
 * village, a same-faith destination village, and the {@link TradeRoute} between
 * them (R3e-3b-1 restricts destinations to route-connected villages so the
 * existing road path + map polyline are reused).
 *
 * <p>Path resolution is identical to {@code Caravan.getPath} (segment or graph
 * route). {@link PilgrimageSavedData} owns realize/dehydrate of the principal
 * entity, the return reintegration, and the boon. Per the approved decision,
 * principal handling copies the caravan machinery verbatim, including its
 * Phase-7c identity limitation (a principal discarded while unobserved is
 * re-spawned fresh) — to be hardened for both caravans and pilgrims separately.</p>
 */
public class Pilgrimage implements TravellingGroup {

    public enum PilgrimState {
        OUTBOUND,   // home → destination
        RETURNING;  // destination → home

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
            Codec.LONG.fieldOf("dispatchTick").forGetter(Pilgrimage::getDispatchTick)
    ).apply(i, Pilgrimage::new));

    private final UUID pilgrimageId;
    private final UUID routeId;
    private final UUID originVillageId;
    private final UUID destVillageId;
    private UUID principalId;
    private PilgrimState state;
    private double progress;        // 0.0 = at the near end of this leg, 1.0 = far end
    private final long dispatchTick;

    private transient boolean isSpawned = false;

    public Pilgrimage(UUID pilgrimageId, UUID routeId, UUID originVillageId,
                      UUID destVillageId, UUID principalId,
                      PilgrimState state, double progress, long dispatchTick) {
        this.pilgrimageId    = pilgrimageId;
        this.routeId         = routeId;
        this.originVillageId = originVillageId;
        this.destVillageId   = destVillageId;
        this.principalId     = principalId;
        this.state           = state;
        this.progress        = progress;
        this.dispatchTick    = dispatchTick;
    }

    public static Pilgrimage create(UUID routeId, UUID originVillageId, UUID destVillageId,
                                    UUID principalId, long currentTick) {
        return new Pilgrimage(UUID.randomUUID(), routeId, originVillageId, destVillageId,
                principalId, PilgrimState.OUTBOUND, 0.0, currentTick);
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
        // PilgrimTravelBehavior drives per-tick walking + progress while realized.
    }

    @Override public void onTickSimulated(ServerLevel level, VillageSavedData data) {
        // Engine advances progress; nothing else needed.
    }

    @Override
    public void onPathComplete(ServerLevel level, VillageSavedData data) {
        // Simulated-side leg completion. OUTBOUND arrival → turn around and head
        // home. RETURNING completion (progress≥1.0) is handled by
        // PilgrimageSavedData.tick (reintegration). Mirrors Caravan.
        if (state == PilgrimState.OUTBOUND) {
            state = PilgrimState.RETURNING;
            progress = 0.0;
        }
    }

    public void onDespawned() {
        this.isSpawned = false;
    }
}
