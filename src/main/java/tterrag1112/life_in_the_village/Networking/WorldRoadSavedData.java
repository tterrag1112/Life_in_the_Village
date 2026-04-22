package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Village.Roads.Economy.VillageUpkeepLedger;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GraphInvariantValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <h3>Migration flag</h3>
 * {@code migrated} is {@code false} on a fresh world. Once
 * {@link tterrag1112.life_in_the_village.Village.Roads.Graph.TradeRoadMigration}
 * runs successfully it sets the flag to {@code true} and calls {@link #markDirty}
 * so the flag is persisted. Subsequent world loads skip migration immediately.
 */
public class WorldRoadSavedData extends SavedData {

    // ── Codec ────────────────────────────────────────────────────────────────

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    private record Snapshot(WorldRoadGraph graph, boolean migrated,
                             Map<UUID, VillageUpkeepLedger> ledgers) {
        static final Codec<Snapshot> CODEC = RecordCodecBuilder.create(i -> i.group(
                WorldRoadGraph.CODEC.fieldOf("graph")
                        .forGetter(Snapshot::graph),
                Codec.BOOL.optionalFieldOf("migrated", false)
                        .forGetter(Snapshot::migrated),
                Codec.unboundedMap(UUID_CODEC, VillageUpkeepLedger.CODEC)
                        .optionalFieldOf("upkeepLedgers", new HashMap<>())
                        .forGetter(Snapshot::ledgers)
        ).apply(i, Snapshot::new));
    }

    public static final Codec<WorldRoadSavedData> CODEC = Snapshot.CODEC.xmap(
            snap -> {
                WorldRoadSavedData data = new WorldRoadSavedData(
                        snap.graph(), snap.migrated(), new HashMap<>(snap.ledgers()));
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
            data -> new Snapshot(data.graph, data.migrated, new HashMap<>(data.ledgers))
    );

    public static final SavedDataType<WorldRoadSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_road_graph",
            WorldRoadSavedData::new,
            CODEC
    );

    // ── State ────────────────────────────────────────────────────────────────

    private final WorldRoadGraph graph;
    private boolean migrated;
    /** Village UUID → upkeep ledger. Populated lazily on first upkeep cycle. */
    private final Map<UUID, VillageUpkeepLedger> ledgers;

    // ── Constructors ─────────────────────────────────────────────────────────

    /** Default constructor — creates an empty graph for a fresh world. */
    public WorldRoadSavedData() {
        this.graph    = new WorldRoadGraph();
        this.migrated = false;
        this.ledgers  = new HashMap<>();
    }

    private WorldRoadSavedData(WorldRoadGraph graph, boolean migrated,
                                Map<UUID, VillageUpkeepLedger> ledgers) {
        this.graph    = graph;
        this.migrated = migrated;
        this.ledgers  = ledgers;
    }

    // ── Accessor ─────────────────────────────────────────────────────────────

    public static WorldRoadSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Getters / setters ────────────────────────────────────────────────────

    public WorldRoadGraph getGraph() { return graph; }

    public boolean isMigrated() { return migrated; }

    public void setMigrated(boolean migrated) { this.migrated = migrated; }

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

    /** Exposes {@link SavedData#setDirty()} to external callers. */
    public void markDirty() { setDirty(); }
}
