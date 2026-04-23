package tterrag1112.life_in_the_village.Village.Roads.Graph;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.RoadPrimitive;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter;

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
 * Copying block paths from legacy {@link tterrag1112.life_in_the_village.Village.Economy.Trade.TradeRoad}s
 * during migration pre-populates this field for already-realized roads.
 *
 * <h3>Meander profile</h3>
 * Seeded once at planning time. Re-realization uses the same seed so a given
 * edge always has the same visual character regardless of how many times it is
 * re-placed. Legacy migrated roads receive amplitude=0, frequency=0 (flat
 * profile); they will be re-realized under the new system in Phase 5.
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
        LOCAL;

        public static final Codec<EdgeTier> CODEC =
                Codec.STRING.xmap(EdgeTier::valueOf, EdgeTier::name);
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

    public static final Codec<RoadEdge> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("edgeId")
                    .forGetter(e -> e.edgeId),
            UUID_CODEC.fieldOf("nodeAId")
                    .forGetter(e -> e.nodeAId),
            UUID_CODEC.fieldOf("nodeBId")
                    .forGetter(e -> e.nodeBId),
            Codec.LONG.listOf()
                    .optionalFieldOf("cellPath", new ArrayList<>())
                    .forGetter(e -> e.cellPath),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("blockPath", new ArrayList<>())
                    .forGetter(e -> e.blockPath),
            EdgeTier.CODEC.fieldOf("tier")
                    .forGetter(e -> e.tier),
            MeanderProfile.CODEC.fieldOf("meanderProfile")
                    .forGetter(e -> e.meanderProfile),
            Codec.INT.optionalFieldOf("maintenance", 100)
                    .forGetter(e -> e.maintenance),
            Codec.LONG.optionalFieldOf("trafficCounter", 0L)
                    .forGetter(e -> e.trafficCounter),
            UUID_CODEC.listOf()
                    .optionalFieldOf("maintainerVillageIds", new ArrayList<>())
                    .forGetter(e -> new ArrayList<>(e.maintainerVillageIds)),
            Codec.LONG.listOf()
                    .optionalFieldOf("staleCells", new ArrayList<>())
                    .forGetter(e -> new ArrayList<>(e.staleCells)),
            Codec.BOOL.optionalFieldOf("realized", false)
                    .forGetter(e -> e.realized),
            RoadPrimitive.CODEC.listOf()
                    .optionalFieldOf("primitives", new ArrayList<>())
                    .forGetter(e -> e.primitives != null ? e.primitives : new ArrayList<>()),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("decorationPositions", new ArrayList<>())
                    .forGetter(e -> e.decorationPositions),
            Codec.STRING.optionalFieldOf("roadName")
                    .forGetter(e -> e.roadName),
            GreatRoadCharacter.CODEC.optionalFieldOf("character")
                    .forGetter(e -> e.character)
    ).apply(i, RoadEdge::fromCodec));

    private static RoadEdge fromCodec(
            UUID edgeId, UUID nodeAId, UUID nodeBId,
            List<Long> cellPath, List<BlockPos> blockPath,
            EdgeTier tier, MeanderProfile meanderProfile,
            int maintenance, long trafficCounter,
            List<UUID> maintainerVillageIds, List<Long> staleList,
            boolean realized, List<RoadPrimitive> primitives,
            List<BlockPos> decorationPositions,
            Optional<String> roadName,
            Optional<GreatRoadCharacter> character) {
        RoadEdge e = new RoadEdge(
                edgeId, nodeAId, nodeBId,
                new ArrayList<>(cellPath), new ArrayList<>(blockPath),
                tier, meanderProfile,
                maintenance, trafficCounter,
                new ArrayList<>(maintainerVillageIds), new HashSet<>(staleList),
                realized);
        e.primitives = primitives.isEmpty() ? null : new ArrayList<>(primitives);
        e.decorationPositions = new ArrayList<>(decorationPositions);
        e.roadName = roadName;
        e.character = character;
        return e;
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
}
