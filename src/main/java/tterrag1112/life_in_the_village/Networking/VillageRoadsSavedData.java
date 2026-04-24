package tterrag1112.life_in_the_village.Networking;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import tterrag1112.life_in_the_village.Village.Roads.Graph.VillageRoadGraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists all village-internal {@link VillageRoadGraph}s for a dimension.
 *
 * <p>This saved-data is separate from {@link VillageSavedData} to keep
 * concerns clean: village simulation data (buildings, NPCs, economy) lives
 * in one file; road topology lives here.
 *
 * <h3>Key</h3>
 * {@code litv_village_roads} — distinct from {@code life_in_the_village_buildings}
 * and {@code life_in_the_village_road_graph}.
 *
 * <h3>Migration</h3>
 * Existing worlds have no {@code litv_village_roads} file.  On first load
 * a fresh instance is created and the caller bootstraps one empty graph per
 * existing village (see {@link #bootstrapFromVillageSavedData}).
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>Village created → {@link #getOrCreate(UUID)}</li>
 *   <li>Village destroyed → {@link #removeGraph(UUID)}</li>
 * </ul>
 * Both calls must be followed by {@link #setDirty()}.  The callers (village
 * realisation, command handlers) hold a reference to the level and can
 * retrieve this instance via {@link #get(ServerLevel)}.
 */
public final class VillageRoadsSavedData extends SavedData {

    // =========================================================================
    // Codec
    // =========================================================================

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    /**
     * Map codec: village UUID (string) → graph Wire form.
     * The graph's villageId is reconstructed from the map key on decode.
     */
    private static final Codec<Map<UUID, VillageRoadGraph>> GRAPHS_CODEC =
            Codec.unboundedMap(UUID_CODEC, VillageRoadGraph.Wire.CODEC)
                    .xmap(
                            wireMap -> {
                                Map<UUID, VillageRoadGraph> result = new HashMap<>();
                                wireMap.forEach((id, wire) ->
                                        result.put(id, VillageRoadGraph.fromWire(id, wire)));
                                return result;
                            },
                            graphMap -> {
                                Map<UUID, VillageRoadGraph.Wire> result = new HashMap<>();
                                graphMap.forEach((id, graph) -> result.put(id, graph.toWire()));
                                return result;
                            }
                    );

    public static final Codec<VillageRoadsSavedData> CODEC =
            RecordCodecBuilder.<VillageRoadsSavedData>create(i -> i.group(
                    GRAPHS_CODEC.optionalFieldOf("graphs", new HashMap<>())
                            .forGetter(d -> new HashMap<>(d.graphsByVillage))
            ).apply(i, maps -> {
                VillageRoadsSavedData data = new VillageRoadsSavedData();
                data.graphsByVillage.putAll(maps);
                return data;
            }));

    public static final SavedDataType<VillageRoadsSavedData> TYPE = new SavedDataType<>(
            "litv_village_roads",
            VillageRoadsSavedData::new,
            CODEC
    );

    // =========================================================================
    // State
    // =========================================================================

    private final Map<UUID, VillageRoadGraph> graphsByVillage = new HashMap<>();

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Default constructor — fresh world, no graphs yet. */
    public VillageRoadsSavedData() {}

    // =========================================================================
    // Accessor
    // =========================================================================

    public static VillageRoadsSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // =========================================================================
    // Graph lifecycle
    // =========================================================================

    /**
     * Returns the graph for {@code villageId} if it exists.
     */
    public Optional<VillageRoadGraph> getGraph(UUID villageId) {
        return Optional.ofNullable(graphsByVillage.get(villageId));
    }

    /**
     * Returns the graph for {@code villageId}, creating an empty one if absent.
     * Marks dirty if a new graph was created.
     */
    public VillageRoadGraph getOrCreate(UUID villageId) {
        return graphsByVillage.computeIfAbsent(villageId, id -> {
            setDirty();
            return new VillageRoadGraph(id);
        });
    }

    /**
     * Removes the graph for {@code villageId}. Marks dirty if the graph was present.
     *
     * @return true if a graph was removed
     */
    public boolean removeGraph(UUID villageId) {
        if (graphsByVillage.remove(villageId) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    // =========================================================================
    // Bulk queries
    // =========================================================================

    public List<VillageRoadGraph> allGraphs() {
        return List.copyOf(graphsByVillage.values());
    }

    public int graphCount() { return graphsByVillage.size(); }

    // =========================================================================
    // Migration helper
    // =========================================================================

    /**
     * Ensures every village in {@code villageData} has an empty graph.
     * Called once after first load on a world that previously had no
     * {@code litv_village_roads} file (i.e., all graphs were absent).
     * Safe to call repeatedly — existing graphs are not overwritten.
     *
     * @return the number of graphs created
     */
    public int bootstrapFromVillageSavedData(VillageSavedData villageData) {
        int created = 0;
        for (tterrag1112.life_in_the_village.Village.Village village
                : villageData.getAllVillages()) {
            if (!graphsByVillage.containsKey(village.getId())) {
                graphsByVillage.put(village.getId(), new VillageRoadGraph(village.getId()));
                created++;
            }
        }
        if (created > 0) setDirty();
        return created;
    }

    // =========================================================================
    // Invariant validation
    // =========================================================================

    /**
     * Runs {@link VillageRoadGraph#validateInvariants()} on every graph.
     *
     * @return map of villageId → violation list; only villages with violations
     *         are present in the result; empty map means everything is valid
     */
    public Map<UUID, List<String>> validateAll() {
        Map<UUID, List<String>> result = new HashMap<>();
        for (Map.Entry<UUID, VillageRoadGraph> entry : graphsByVillage.entrySet()) {
            List<String> violations = entry.getValue().validateInvariants();
            if (!violations.isEmpty()) {
                result.put(entry.getKey(), violations);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
