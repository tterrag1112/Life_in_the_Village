package tterrag1112.life_in_the_village.Village.Roads.Events;

import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 10 — static registry of {@link RoadEventType}s. Populated at mod init.
 *
 * <p>Lookup is by {@code typeId}. Persistence references types by typeId so
 * that re-registration after a code change picks up the new factory on the
 * next world load. Unknown typeIds in saved data are skipped at load (logged
 * once); they don't crash the world.
 */
public final class RoadEventRegistry {

    private RoadEventRegistry() {}

    private static final Map<String, RoadEventType> TYPES = new LinkedHashMap<>();

    /**
     * Registers an event type. If a type with the same {@code typeId} is
     * already registered, it is replaced (last write wins — useful during
     * dev iteration; a startup-time conflict log line is emitted).
     */
    public static void register(RoadEventType type) {
        if (TYPES.containsKey(type.typeId())) {
            System.out.println("[RoadEventRegistry] replacing existing type '" + type.typeId() + "'");
        }
        TYPES.put(type.typeId(), type);
    }

    public static Optional<RoadEventType> get(String typeId) {
        if (typeId == null) return Optional.empty();
        return Optional.ofNullable(TYPES.get(typeId));
    }

    public static Map<String, RoadEventType> all() {
        return Collections.unmodifiableMap(TYPES);
    }

    /**
     * @return all types whose {@link RoadEventType#category()} matches.
     */
    public static List<RoadEventType> byCategory(RoadEventType.EventCategory cat) {
        List<RoadEventType> out = new ArrayList<>();
        for (RoadEventType t : TYPES.values()) {
            if (t.category() == cat) out.add(t);
        }
        return out;
    }

    /**
     * @return all types whose tier filter includes {@code edge}'s tier.
     *         Biome filter is checked at site time (per-position), not here.
     */
    public static List<RoadEventType> applicableTo(RoadEdge edge) {
        List<RoadEventType> out = new ArrayList<>();
        for (RoadEventType t : TYPES.values()) {
            if (!t.applicableTiers().contains(edge.getTier())) continue;
            // Node-only categories don't get edge placement.
            if (t.category() == RoadEventType.EventCategory.JUNCTION) continue;
            out.add(t);
        }
        return out;
    }

    /**
     * @return all types eligible to place at the given node. Currently the
     *         {@link RoadEventType.EventCategory#JUNCTION} and
     *         {@link RoadEventType.EventCategory#LANDMARK} categories may
     *         attach to nodes; tier filtering is skipped because nodes don't
     *         carry a tier directly.
     */
    public static List<RoadEventType> applicableTo(RoadNode node) {
        List<RoadEventType> out = new ArrayList<>();
        for (RoadEventType t : TYPES.values()) {
            if (t.category() == RoadEventType.EventCategory.JUNCTION
             || t.category() == RoadEventType.EventCategory.LANDMARK) {
                out.add(t);
            }
        }
        return out;
    }

    public static List<RoadEventType> worldUniqueTypes() {
        List<RoadEventType> out = new ArrayList<>();
        for (RoadEventType t : TYPES.values()) {
            if (t.worldUnique()) out.add(t);
        }
        return out;
    }
}
