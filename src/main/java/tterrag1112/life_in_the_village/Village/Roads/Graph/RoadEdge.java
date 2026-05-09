package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter;
import tterrag1112.life_in_the_village.Village.Roads.Lifecycle.DeadEdgeState;
import tterrag1112.life_in_the_village.Village.Roads.Lighting.RoadLightingProfile;

import java.util.*;

/**
 * A directed-pair edge in the world road graph connecting two {@link RoadNode}s.
 *
 * <p>This is a class (not a record) because several fields are mutable over
 * the edge's lifetime: maintenance decays, traffic accumulates, realized blocks
 * are populated lazily, and stale cells are flagged when terrain changes.
 *
 * <h3>Immutable identity vs mutable state</h3>
 * {@code edgeId}, {@code nodeAId}, {@code nodeBId}, and {@code cellPath} are
 * final after construction. Everything else can change.
 *
 * <h3>Realization</h3>
 * {@code blockPath} is empty until the realiser runs (Phase 5). The
 * {@code realized} flag tracks whether blocks have ever been placed.
 *
 * <h3>Meander profile</h3>
 * Seeded once at planning time. Re-realization uses the same seed so a given
 * edge always has the same visual character regardless of how many times it is
 * re-placed.
 */
public class RoadEdge {

    public enum EdgeTier {
        /** Old Realm great road. No maintainer. No decay. */
        GREAT_ROAD,
        /** Major inter-kingdom trunk. Usually kingdom-maintained. */
        TRUNK,
        /** Village-to-network connector. Village-maintained. */
        CONNECTOR,
        /** Internal village arm or minor spur. */
        LOCAL,
        /**
         * Track C3.3 — open-water route between two coastal villages'
         * dock buildings. cellPath holds atlas cell-keys identical to
         * land tiers but the cells are ocean / coastal instead of land.
         * No block path is realised — boat caravans interpolate cell
         * centres at sea level. EdgeRealizer, decoration, lighting,
         * shelters, and material resolution all skip SEA edges.
         */
        SEA;

        public static final Codec<EdgeTier> CODEC =
                Codec.STRING.xmap(EdgeTier::valueOf, EdgeTier::name);

        /** True for SEA edges only. */
        public boolean isWater() { return this == SEA; }
    }

    /**
     * Stable noise parameters for this edge's block-path meander. Seeded once
     * at planning time so re-realization always produces the same visual shape.
     */
    public record MeanderProfile(float amplitude, float frequency, long seed) {
        public static final Codec<MeanderProfile> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("amplitude").forGetter(MeanderProfile::amplitude),
                Codec.FLOAT.fieldOf("frequency").forGetter(MeanderProfile::frequency),
                Codec.LONG.fieldOf("seed").forGetter(MeanderProfile::seed)
        ).apply(i, MeanderProfile::new));
    }

    // ── Codec ────────────────────────────────────────────────────────────────

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    /**
     * Carrier record for the 16 base fields of {@link RoadEdge}.
     * Used internally to keep each {@code MapCodec} group within DataFixerUpper's
     * 16-field arity limit while preserving a single, flat JSON object on disk.
     */
    private record BaseTuple(
            UUID edgeId, UUID nodeAId, UUID nodeBId,
            List<Long> cellPath, List<BlockPos> blockPath,
            EdgeTier tier, MeanderProfile meanderProfile,
            int maintenance, long trafficCounter,
            List<UUID> maintainerVillageIds, List<Long> staleCells,
            boolean realized, List<RoadPrimitive> primitives,
            List<BlockPos> decorationPositions,
            Optional<String> roadName,
            Optional<GreatRoadCharacter> character) {}

    /**
     * Carrier record for the optional Phase 7g+ extension fields. Bundled into
     * a separate {@code MapCodec} so the combined codec stays under the 16-field
     * arity ceiling. Both fields are merged into the same top-level JSON object
     * via {@link Codec#mapPair}, so the on-disk shape is unchanged from a flat
     * 18-field codec.
     */
    private record ExtrasTuple(
            Optional<RoadLightingProfile> lightingOverride,
            Optional<DeadEdgeState> deadState,
            List<UUID> eventIds) {}

    private static final MapCodec<BaseTuple> BASE_MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            UUID_CODEC.fieldOf("edgeId")
                    .forGetter(BaseTuple::edgeId),
            UUID_CODEC.fieldOf("nodeAId")
                    .forGetter(BaseTuple::nodeAId),
            UUID_CODEC.fieldOf("nodeBId")
                    .forGetter(BaseTuple::nodeBId),
            Codec.LONG.listOf()
                    .optionalFieldOf("cellPath", new ArrayList<>())
                    .forGetter(BaseTuple::cellPath),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("blockPath", new ArrayList<>())
                    .forGetter(BaseTuple::blockPath),
            EdgeTier.CODEC.fieldOf("tier")
                    .forGetter(BaseTuple::tier),
            MeanderProfile.CODEC.fieldOf("meanderProfile")
                    .forGetter(BaseTuple::meanderProfile),
            Codec.INT.optionalFieldOf("maintenance", 100)
                    .forGetter(BaseTuple::maintenance),
            Codec.LONG.optionalFieldOf("trafficCounter", 0L)
                    .forGetter(BaseTuple::trafficCounter),
            UUID_CODEC.listOf()
                    .optionalFieldOf("maintainerVillageIds", new ArrayList<>())
                    .forGetter(BaseTuple::maintainerVillageIds),
            Codec.LONG.listOf()
                    .optionalFieldOf("staleCells", new ArrayList<>())
                    .forGetter(BaseTuple::staleCells),
            Codec.BOOL.optionalFieldOf("realized", false)
                    .forGetter(BaseTuple::realized),
            RoadPrimitive.CODEC.listOf()
                    .optionalFieldOf("primitives", new ArrayList<>())
                    .forGetter(BaseTuple::primitives),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("decorationPositions", new ArrayList<>())
                    .forGetter(BaseTuple::decorationPositions),
            Codec.STRING.optionalFieldOf("roadName")
                    .forGetter(BaseTuple::roadName),
            GreatRoadCharacter.CODEC.optionalFieldOf("character")
                    .forGetter(BaseTuple::character)
    ).apply(i, BaseTuple::new));

    private static final MapCodec<ExtrasTuple> EXTRAS_MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            RoadLightingProfile.CODEC.optionalFieldOf("lightingOverride")
                    .forGetter(ExtrasTuple::lightingOverride),
            DeadEdgeState.CODEC.optionalFieldOf("deadState")
                    .forGetter(ExtrasTuple::deadState),
            UUID_CODEC.listOf()
                    .optionalFieldOf("eventIds", new ArrayList<>())
                    .forGetter(ExtrasTuple::eventIds)
    ).apply(i, ExtrasTuple::new));

    public static final Codec<RoadEdge> CODEC =
            Codec.mapPair(BASE_MAP_CODEC, EXTRAS_MAP_CODEC).codec().xmap(
                    pair -> fromCodec(pair.getFirst(), pair.getSecond()),
                    edge -> Pair.of(toBaseTuple(edge), toExtrasTuple(edge)));

    private static RoadEdge fromCodec(BaseTuple base, ExtrasTuple extras) {
        RoadEdge e = new RoadEdge(
                base.edgeId(), base.nodeAId(), base.nodeBId(),
                new ArrayList<>(base.cellPath()), new ArrayList<>(base.blockPath()),
                base.tier(), base.meanderProfile(),
                base.maintenance(), base.trafficCounter(),
                new ArrayList<>(base.maintainerVillageIds()), new HashSet<>(base.staleCells()),
                base.realized());
        e.primitives = base.primitives().isEmpty() ? null : new ArrayList<>(base.primitives());
        e.decorationPositions = new ArrayList<>(base.decorationPositions());
        e.roadName = base.roadName();
        e.character = base.character();
        e.lightingOverride = extras.lightingOverride();
        e.deadState = extras.deadState();
        e.eventIds = new ArrayList<>(extras.eventIds());
        return e;
    }

    private static BaseTuple toBaseTuple(RoadEdge e) {
        return new BaseTuple(
                e.edgeId, e.nodeAId, e.nodeBId,
                e.cellPath, e.blockPath,
                e.tier, e.meanderProfile,
                e.maintenance, e.trafficCounter,
                new ArrayList<>(e.maintainerVillageIds), new ArrayList<>(e.staleCells),
                e.realized,
                e.primitives != null ? e.primitives : new ArrayList<>(),
                e.decorationPositions,
                e.roadName, e.character);
    }

    private static ExtrasTuple toExtrasTuple(RoadEdge e) {
        return new ExtrasTuple(e.lightingOverride, e.deadState, new ArrayList<>(e.eventIds));
    }

    // ── Fields ───────────────────────────────────────────────────────────────

    final UUID edgeId;
    final UUID nodeAId;
    final UUID nodeBId;
    final List<Long> cellPath;

    List<BlockPos> blockPath;
    EdgeTier tier;
    MeanderProfile meanderProfile;
    int maintenance;
    long trafficCounter;
    List<UUID> maintainerVillageIds;
    Set<Long> staleCells;
    boolean realized;
    List<RoadPrimitive> primitives;
    /** Block positions of all placed decoration blocks along this edge (persistent). */
    List<BlockPos> decorationPositions;

    /** Optional name for named great roads. Empty for all non-named edges. */
    Optional<String> roadName = Optional.empty();

    /** Character record for GREAT_ROAD edges. Empty for all other tiers. */
    Optional<GreatRoadCharacter> character = Optional.empty();

    /**
     * Per-edge lighting override, set via {@code /liv road debug force_lighting}.
     * When empty (default), lighting is derived from tier + culture at realisation
     * time; palette resolution stays non-persistent. When present, this profile is
     * used verbatim instead.
     */
    Optional<RoadLightingProfile> lightingOverride = Optional.empty();

    /**
     * Phase 9 dead-edge state. Empty for living edges. Set when the last
     * maintainer village dies; never cleared (death is permanent).
     * GREAT_ROAD edges never have this set (invariant 3).
     */
    Optional<DeadEdgeState> deadState = Optional.empty();

    /**
     * Phase 10 — UUIDs of road events ({@link
     * tterrag1112.life_in_the_village.Village.Roads.Events.RoadEvent}) attached
     * to this edge. Mutated by the event realiser and lifecycle system.
     */
    List<UUID> eventIds = new ArrayList<>();

    /**
     * Transient flag: maintenance crossed a Phase 6b band boundary this upkeep
     * cycle and the overgrowth decoration should be refreshed when the edge is
     * next in player range. Not persisted — cleared when decoration runs.
     */
    boolean needsDecorationRefresh = false;

    // ── Constructors ─────────────────────────────────────────────────────────

    /** Full constructor — used by codec deserialization and internally. */
    private RoadEdge(UUID edgeId, UUID nodeAId, UUID nodeBId,
                     List<Long> cellPath, List<BlockPos> blockPath,
                     EdgeTier tier, MeanderProfile meanderProfile,
                     int maintenance, long trafficCounter,
                     List<UUID> maintainerVillageIds, Set<Long> staleCells,
                     boolean realized) {
        this.edgeId              = edgeId;
        this.nodeAId             = nodeAId;
        this.nodeBId             = nodeBId;
        this.cellPath            = cellPath;
        this.blockPath           = blockPath;
        this.tier                = tier;
        this.meanderProfile      = meanderProfile;
        this.maintenance         = maintenance;
        this.trafficCounter      = trafficCounter;
        this.maintainerVillageIds = maintainerVillageIds;
        this.staleCells          = staleCells;
        this.realized            = realized;
        this.decorationPositions = new ArrayList<>();
    }

    /** Convenience constructor for new edges that have not yet been realized. */
    public RoadEdge(UUID edgeId, UUID nodeAId, UUID nodeBId,
                    List<Long> cellPath, EdgeTier tier, MeanderProfile meanderProfile) {
        this(edgeId, nodeAId, nodeBId, new ArrayList<>(cellPath), new ArrayList<>(),
                tier, meanderProfile, 100, 0L, new ArrayList<>(), new HashSet<>(), false);
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static RoadEdge create(UUID nodeAId, UUID nodeBId,
                                  List<Long> cellPath, EdgeTier tier,
                                  MeanderProfile meanderProfile) {
        return new RoadEdge(UUID.randomUUID(), nodeAId, nodeBId, cellPath, tier, meanderProfile);
    }

    // ── Mutators ─────────────────────────────────────────────────────────────

    public void setMaintenance(int maintenance) {
        this.maintenance = Math.max(0, Math.min(100, maintenance));
    }

    public void incrementTraffic() { this.trafficCounter++; }

    public void addTraffic(long amount) { this.trafficCounter += amount; }

    public void unrealize() {
        blockPath.clear();
        realized = false;
        primitives = null;
    }

    public void markCellStale(long cellKey) { staleCells.add(cellKey); }

    public void clearStaleness() { staleCells.clear(); }

    public void markRealized(List<BlockPos> blocks) {
        blockPath.clear();
        blockPath.addAll(blocks);
        realized = true;
    }

    public void setTier(EdgeTier tier) { this.tier = tier; }

    public void setMeanderProfile(MeanderProfile profile) { this.meanderProfile = profile; }

    public void setPrimitives(List<RoadPrimitive> primitives) {
        this.primitives = primitives != null && !primitives.isEmpty()
                ? new ArrayList<>(primitives) : null;
    }

    public void clearPrimitives() { this.primitives = null; }

    // ── Getters ──────────────────────────────────────────────────────────────

    public UUID getEdgeId()                      { return edgeId; }
    public UUID getNodeAId()                     { return nodeAId; }
    public UUID getNodeBId()                     { return nodeBId; }
    public List<Long> getCellPath()              { return cellPath; }
    public List<BlockPos> getBlockPath()         { return blockPath; }
    public EdgeTier getTier()                    { return tier; }
    public MeanderProfile getMeanderProfile()    { return meanderProfile; }
    public int getMaintenance()                  { return maintenance; }
    public long getTrafficCounter()              { return trafficCounter; }
    public List<UUID> getMaintainerVillageIds()  { return maintainerVillageIds; }
    public Set<Long> getStaleCells()             { return staleCells; }
    public boolean isRealized()                  { return realized; }
    public List<RoadPrimitive> getPrimitives()   { return primitives != null ? primitives : List.of(); }
    public boolean hasPrimitives()               { return primitives != null && !primitives.isEmpty(); }

    public List<BlockPos> getDecorationPositions() { return decorationPositions; }
    public void addDecorationPosition(BlockPos pos) { decorationPositions.add(pos.immutable()); }
    public void clearDecorationPositions()          { decorationPositions.clear(); }
    public boolean hasDecorations()                 { return !decorationPositions.isEmpty(); }

    public boolean isNeedsDecorationRefresh()              { return needsDecorationRefresh; }
    public void setNeedsDecorationRefresh(boolean refresh) { this.needsDecorationRefresh = refresh; }

    public Optional<String> getRoadName()     { return roadName; }
    public void setRoadName(String name)      { this.roadName = Optional.of(name); }
    public void clearRoadName()               { this.roadName = Optional.empty(); }

    public Optional<GreatRoadCharacter> getCharacter()             { return character; }
    public void setCharacter(Optional<GreatRoadCharacter> c)       { this.character = c; }
    public Optional<GreatRoadCharacter.CharacterTag> getCharacterTag() {
        return character.map(GreatRoadCharacter::tag);
    }

    public Optional<RoadLightingProfile> getLightingOverride()       { return lightingOverride; }
    public void setLightingOverride(RoadLightingProfile p)           { this.lightingOverride = Optional.of(p); }
    public void clearLightingOverride()                              { this.lightingOverride = Optional.empty(); }

    // ── Event attachments (Phase 10) ─────────────────────────────────────────

    public List<UUID> getEventIds()                                  { return eventIds; }
    public void addEventId(UUID id)                                  { eventIds.add(id); }
    public boolean removeEventId(UUID id)                            { return eventIds.remove(id); }
    public void clearEventIds()                                      { eventIds.clear(); }

    // ── Dead-edge state (Phase 9) ────────────────────────────────────────────

    public Optional<DeadEdgeState> getDeadState()                    { return deadState; }
    public boolean isDead()                                          { return deadState.isPresent(); }

    /**
     * Computes the current death phase. Returns {@code null} if the edge is
     * not dead. Callers that just need the phase should null-check or use
     * {@link #isDead()} first.
     */
    public DeadEdgeState.DeathPhase deathPhase(long currentTick) {
        return deadState.map(s -> s.phaseAtTick(currentTick)).orElse(null);
    }

    /**
     * Marks this edge dead at the given tick. No-op for GREAT_ROAD edges
     * (invariant 3) and idempotent — already-dead edges keep their original
     * markings.
     */
    public void markDead(long tick) {
        if (tier == EdgeTier.GREAT_ROAD) return;
        if (deadState.isPresent()) return;
        this.deadState = Optional.of(DeadEdgeState.markedAt(tick));
    }

    /** Replace the dead state (used by the {@code force_death_phase} debug command). */
    public void setDeadState(DeadEdgeState state) {
        this.deadState = Optional.ofNullable(state);
    }
}
