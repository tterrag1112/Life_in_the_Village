package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Village.Roads.Decoration.ShelterInstance;
import tterrag1112.life_in_the_village.Village.Roads.Economy.VillageUpkeepLedger;
import tterrag1112.life_in_the_village.Village.Roads.Events.RoadEvent;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GraphInvariantValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persists the {@link WorldRoadGraph} for the overworld alongside the village atlas.
 *
 * <h3>Load sequence</h3>
 * <ol>
 *   <li>NBT decoded via {@link #CODEC} → calls {@link #fromCodec}</li>
 *   <li>{@link WorldRoadGraph#fromCodec} deserializes nodes + edges and rebuilds
 *       the transient spatial index.</li>
 *   <li>{@link GraphInvariantValidator#validate} runs; any warnings are printed
 *       with the {@code [RoadGraph Validator]} prefix. No exception is thrown.</li>
 * </ol>
 */
public class WorldRoadSavedData extends SavedData {

    // =========================================================================
    // TollGateRecord — toll gate node metadata + revenue tracking
    // =========================================================================

    /**
     * Persisted record for a single toll gate node.
     *
     * @param nodeId        graph node UUID
     * @param kingdomId     the kingdom that collects this toll
     * @param tier          edge tier at the gate (for fee calculation)
     * @param revenueBronze cumulative bronze collected at this gate
     */
    public record TollGateRecord(UUID nodeId, UUID kingdomId,
                                  RoadEdge.EdgeTier tier, long revenueBronze) {

        private static final Codec<UUID> UUID_CODEC_LOCAL =
                Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<TollGateRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUID_CODEC_LOCAL.fieldOf("nodeId")
                        .forGetter(TollGateRecord::nodeId),
                UUID_CODEC_LOCAL.fieldOf("kingdomId")
                        .forGetter(TollGateRecord::kingdomId),
                RoadEdge.EdgeTier.CODEC.optionalFieldOf("tier", RoadEdge.EdgeTier.TRUNK)
                        .forGetter(TollGateRecord::tier),
                Codec.LONG.optionalFieldOf("revenueBronze", 0L)
                        .forGetter(TollGateRecord::revenueBronze)
        ).apply(i, TollGateRecord::new));

        /** Returns a new record with the given amount added to the revenue total. */
        public TollGateRecord withAddedRevenue(long bronze) {
            return new TollGateRecord(nodeId, kingdomId, tier, revenueBronze + bronze);
        }
    }

    // =========================================================================
    // Codec
    // =========================================================================

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private record Snapshot(WorldRoadGraph graph,
                             Map<UUID, VillageUpkeepLedger> ledgers,
                             boolean greatRoadGenerationComplete,
                             Map<UUID, List<ShelterInstance>> edgeShelters,
                             Map<UUID, TollGateRecord> tollGates,
                             Map<UUID, RoadEvent> events,
                             List<String> worldUniqueTypesPlaced) {
        static final Codec<Snapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                WorldRoadGraph.CODEC.fieldOf("graph")
                        .forGetter(Snapshot::graph),
                Codec.unboundedMap(UUID_CODEC, VillageUpkeepLedger.CODEC)
                        .optionalFieldOf("upkeepLedgers", new HashMap<>())
                        .forGetter(Snapshot::ledgers),
                Codec.BOOL.optionalFieldOf("greatRoadGenerationComplete", false)
                        .forGetter(Snapshot::greatRoadGenerationComplete),
                Codec.unboundedMap(UUID_CODEC, ShelterInstance.CODEC.listOf())
                        .optionalFieldOf("edgeShelters", new HashMap<>())
                        .forGetter(Snapshot::edgeShelters),
                Codec.unboundedMap(UUID_CODEC, TollGateRecord.CODEC)
                        .optionalFieldOf("tollGates", new HashMap<>())
                        .forGetter(Snapshot::tollGates),
                Codec.unboundedMap(UUID_CODEC, RoadEvent.CODEC)
                        .optionalFieldOf("events", new HashMap<>())
                        .forGetter(Snapshot::events),
                Codec.STRING.listOf()
                        .optionalFieldOf("worldUniqueTypesPlaced", new ArrayList<>())
                        .forGetter(Snapshot::worldUniqueTypesPlaced)
        ).apply(i, Snapshot::new));
    }

    public static final Codec<WorldRoadSavedData> CODEC = Snapshot.CODEC.xmap(
            snap -> {
                WorldRoadSavedData data = new WorldRoadSavedData(
                        snap.graph(), new HashMap<>(snap.ledgers()),
                        snap.greatRoadGenerationComplete(), new HashMap<>(snap.edgeShelters()),
                        new HashMap<>(snap.tollGates()),
                        new HashMap<>(snap.events()),
                        new HashSet<>(snap.worldUniqueTypesPlaced()));
                List<String> warnings = GraphInvariantValidator.validate(snap.graph());
                for (String w : warnings) {
                    System.out.println("[RoadGraph Validator] " + w);
                }
                if (warnings.isEmpty()) {
                    System.out.println("[RoadGraph Validator] Graph OK — "
                            + snap.graph().allNodes().size() + " nodes, "
                            + snap.graph().allEdges().size() + " edges.");
                }
                return data;
            },
            data -> new Snapshot(data.graph, new HashMap<>(data.ledgers),
                    data.greatRoadGenerationComplete, new HashMap<>(data.edgeShelters),
                    new HashMap<>(data.tollGates),
                    new HashMap<>(data.events),
                    new ArrayList<>(data.worldUniqueTypesPlaced))
    );

    public static final SavedDataType<WorldRoadSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_road_graph",
            WorldRoadSavedData::new,
            CODEC
    );

    // =========================================================================
    // State
    // =========================================================================

    private final WorldRoadGraph graph;
    /** Village UUID → upkeep ledger. Populated lazily on first upkeep cycle. */
    private final Map<UUID, VillageUpkeepLedger> ledgers;
    private boolean greatRoadGenerationComplete;
    /** Edge UUID → list of placed shelters along that edge. */
    private final Map<UUID, List<ShelterInstance>> edgeShelters;
    /** Node UUID → toll gate record (kingdom + cumulative revenue). */
    private final Map<UUID, TollGateRecord> tollGates;
    /** Phase 10 — event UUID → road event record. */
    private final Map<UUID, RoadEvent> events;
    /** Phase 10 — typeIds of world-unique events that have already been placed. */
    private final Set<String> worldUniqueTypesPlaced;

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Default constructor — creates an empty graph for a fresh world. */
    public WorldRoadSavedData() {
        this.graph                       = new WorldRoadGraph();
        this.ledgers                     = new HashMap<>();
        this.greatRoadGenerationComplete = false;
        this.edgeShelters                = new HashMap<>();
        this.tollGates                   = new HashMap<>();
        this.events                      = new HashMap<>();
        this.worldUniqueTypesPlaced      = new HashSet<>();
    }

    private WorldRoadSavedData(WorldRoadGraph graph,
                                Map<UUID, VillageUpkeepLedger> ledgers,
                                boolean greatRoadGenerationComplete,
                                Map<UUID, List<ShelterInstance>> edgeShelters,
                                Map<UUID, TollGateRecord> tollGates,
                                Map<UUID, RoadEvent> events,
                                Set<String> worldUniqueTypesPlaced) {
        this.graph                       = graph;
        this.ledgers                     = ledgers;
        this.greatRoadGenerationComplete = greatRoadGenerationComplete;
        this.edgeShelters                = edgeShelters;
        this.tollGates                   = tollGates;
        this.events                      = events;
        this.worldUniqueTypesPlaced      = worldUniqueTypesPlaced;
    }

    // =========================================================================
    // Accessor
    // =========================================================================

    public static WorldRoadSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // =========================================================================
    // Getters — graph
    // =========================================================================

    public WorldRoadGraph getGraph() { return graph; }

    // =========================================================================
    // Upkeep ledgers
    // =========================================================================

    /** Returns the ledger for {@code villageId}, creating it if absent. */
    public VillageUpkeepLedger getOrCreateLedger(UUID villageId) {
        return ledgers.computeIfAbsent(villageId, k -> new VillageUpkeepLedger());
    }

    /** Returns the ledger for {@code villageId}, or {@code null} if none recorded yet. */
    public VillageUpkeepLedger getLedger(UUID villageId) {
        return ledgers.get(villageId);
    }

    /** Unmodifiable view of all ledgers. */
    public Map<UUID, VillageUpkeepLedger> getLedgers() {
        return java.util.Collections.unmodifiableMap(ledgers);
    }

    // =========================================================================
    // Great road generation flag
    // =========================================================================

    public boolean isGreatRoadGenerationComplete() { return greatRoadGenerationComplete; }

    public void setGreatRoadGenerationComplete(boolean complete) {
        this.greatRoadGenerationComplete = complete;
    }

    // =========================================================================
    // Shelter instances (Phase 8c)
    // =========================================================================

    /** Returns the shelter list for {@code edgeId}, or an empty list if none placed yet. */
    public List<ShelterInstance> getShelters(UUID edgeId) {
        return edgeShelters.getOrDefault(edgeId, List.of());
    }

    /** Returns the shelter list for {@code edgeId}, creating a mutable list if absent. */
    public List<ShelterInstance> getOrCreateShelters(UUID edgeId) {
        return edgeShelters.computeIfAbsent(edgeId, k -> new ArrayList<>());
    }

    /** Replaces the shelter list for {@code edgeId}. */
    public void setShelters(UUID edgeId, List<ShelterInstance> shelters) {
        if (shelters == null || shelters.isEmpty()) {
            edgeShelters.remove(edgeId);
        } else {
            edgeShelters.put(edgeId, new ArrayList<>(shelters));
        }
    }

    /** Removes all shelters recorded for {@code edgeId}. */
    public void clearShelters(UUID edgeId) {
        edgeShelters.remove(edgeId);
    }

    /** Unmodifiable view of all edge shelter entries. */
    public Map<UUID, List<ShelterInstance>> getAllEdgeShelters() {
        return Collections.unmodifiableMap(edgeShelters);
    }

    // =========================================================================
    // Toll gate records (Phase 8d)
    // =========================================================================

    /** Returns the toll gate record for {@code nodeId}, if one was registered. */
    public Optional<TollGateRecord> getTollGate(UUID nodeId) {
        return Optional.ofNullable(tollGates.get(nodeId));
    }

    /**
     * Registers a toll gate node. Overwrites any previous record for this node.
     *
     * @param nodeId    graph node UUID
     * @param kingdomId collecting kingdom
     * @param tier      edge tier at the gate (used for fee calculation)
     */
    public void registerTollGate(UUID nodeId, UUID kingdomId, RoadEdge.EdgeTier tier) {
        tollGates.put(nodeId, new TollGateRecord(nodeId, kingdomId, tier, 0L));
    }

    /**
     * Adds {@code bronze} to the cumulative revenue for {@code nodeId}.
     * No-op if the node is not registered.
     */
    public void addTollRevenue(UUID nodeId, long bronze) {
        TollGateRecord rec = tollGates.get(nodeId);
        if (rec != null) {
            tollGates.put(nodeId, rec.withAddedRevenue(bronze));
        }
    }

    /** Removes the toll gate record for {@code nodeId}. */
    public void removeTollGate(UUID nodeId) {
        tollGates.remove(nodeId);
    }

    /** Unmodifiable view of all toll gate records, keyed by node UUID. */
    public Map<UUID, TollGateRecord> getAllTollGates() {
        return Collections.unmodifiableMap(tollGates);
    }

    // =========================================================================
    // Road events (Phase 10)
    // =========================================================================

    public Optional<RoadEvent> getEvent(UUID eventId) {
        return Optional.ofNullable(events.get(eventId));
    }

    public void registerEvent(RoadEvent event) {
        events.put(event.eventId(), event);
    }

    /** Replaces an existing event in-place (e.g. after editing a property). */
    public void updateEvent(RoadEvent event) {
        events.put(event.eventId(), event);
    }

    public void removeEvent(UUID eventId) {
        events.remove(eventId);
    }

    public List<RoadEvent> allEvents() {
        return new ArrayList<>(events.values());
    }

    public List<RoadEvent> eventsForEdge(UUID edgeId) {
        List<RoadEvent> out = new ArrayList<>();
        for (RoadEvent e : events.values()) {
            if (e.containingEdgeId().filter(edgeId::equals).isPresent()) out.add(e);
        }
        return out;
    }

    public List<RoadEvent> eventsForNode(UUID nodeId) {
        List<RoadEvent> out = new ArrayList<>();
        for (RoadEvent e : events.values()) {
            if (e.containingNodeId().filter(nodeId::equals).isPresent()) out.add(e);
        }
        return out;
    }

    public boolean isWorldUniquePlaced(String typeId) {
        return worldUniqueTypesPlaced.contains(typeId);
    }

    public void markWorldUniquePlaced(String typeId) {
        worldUniqueTypesPlaced.add(typeId);
    }

    public void unmarkWorldUniquePlaced(String typeId) {
        worldUniqueTypesPlaced.remove(typeId);
    }

    public Set<String> getWorldUniqueTypesPlaced() {
        return Collections.unmodifiableSet(worldUniqueTypesPlaced);
    }

    // =========================================================================
    // Dirty flag
    // =========================================================================

    /** Exposes {@link SavedData#setDirty()} to external callers. */
    public void markDirty() { setDirty(); }
}
