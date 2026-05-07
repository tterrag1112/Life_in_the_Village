package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * V2 topological sort of building types by {@code requires_present}
 * dependencies.
 *
 * <p>Algorithm (Kahn's, with deterministic tie-breaking):
 * <ol>
 *   <li>Collect distinct types from the input multiplicity list.</li>
 *   <li>Build the dependency graph: for each type T with profile P,
 *       for each {@code D ∈ P.requiresPresent}, add edge {@code D → T}
 *       (D must come before T).</li>
 *   <li>Repeatedly emit zero-indegree nodes; tie-break by
 *       {@link Priority} ordinal then by {@link BuildingType} ordinal.</li>
 *   <li>Re-expand the original multiplicity for the output list.</li>
 * </ol>
 *
 * <p>Detects cycles ({@link IllegalStateException}). V1 deps are a
 * DAG by construction (BAKERY → MILLER, BLACKSMITH → MINE) so this
 * is defensive.
 */
public final class DependencyResolver {

    private DependencyResolver() {}

    /** Salt mixed into the seed so the topo-tie shuffle doesn't share
     *  Random state with the cross-street, selection, or spine
     *  samplers. */
    private static final long TOPO_SHUFFLE_SALT = 0x70_70_50_5F_5F_5FL;

    public static List<BuildingType> topoSort(List<BuildingType> buildings) {
        return topoSort(buildings, (Random) null);
    }

    /** Convenience overload: derive a {@link Random} from the village's
     *  {@code seed} so callers don't have to import the Random class. */
    public static List<BuildingType> topoSort(List<BuildingType> buildings, long seed) {
        return topoSort(buildings, new Random(seed ^ TOPO_SHUFFLE_SALT));
    }

    /** Variation-aware overload. {@code rng} is non-null in production;
     *  ties within the same priority bucket are shuffled so HOUSE etc.
     *  don't always sort to the very end where they're most likely to
     *  drop. Pass {@code null} for the deterministic legacy ordering. */
    public static List<BuildingType> topoSort(List<BuildingType> buildings, Random rng) {
        // Count multiplicities + collect distinct.
        EnumMap<BuildingType, Integer> counts = new EnumMap<>(BuildingType.class);
        for (BuildingType t : buildings) counts.merge(t, 1, Integer::sum);
        if (counts.isEmpty()) return List.of();

        // Build edges restricted to present types — deps on absent types
        // are dropped here (the solver also re-checks with an explicit
        // DEPENDENCY_MISSING reason if a dep was selected but failed
        // placement; here we only filter graph edges).
        Set<BuildingType> present = EnumSet.copyOf(counts.keySet());
        Map<BuildingType, Set<BuildingType>> edges = new HashMap<>();   // dep → dependents
        EnumMap<BuildingType, Integer> indegree = new EnumMap<>(BuildingType.class);
        for (BuildingType t : present) indegree.put(t, 0);

        for (BuildingType t : present) {
            PlacementProfile pp = PlacementDefaults.get(t);
            if (pp == null) continue;
            for (BuildingType dep : pp.requiresPresent()) {
                if (!present.contains(dep)) continue;
                edges.computeIfAbsent(dep, k -> new java.util.HashSet<>()).add(t);
                indegree.merge(t, 1, Integer::sum);
            }
        }

        // Tie-breaker: Priority ordinal, then BuildingType ordinal.
        Comparator<BuildingType> tieBreak = Comparator
                .<BuildingType>comparingInt(t -> {
                    PlacementProfile pp = PlacementDefaults.get(t);
                    return pp != null ? pp.priority().ordinal() : Integer.MAX_VALUE;
                })
                .thenComparingInt(Enum::ordinal);

        // Kahn's: ready = all present types with indegree 0, sorted.
        // Within each priority bucket, shuffle if rng is non-null —
        // that interleaves tied types (HOUSE / FARMHOUSE / ...) across
        // the placement order rather than letting hashmap iteration
        // order cluster them at the end.
        List<BuildingType> initialReady = new ArrayList<>();
        present.stream()
                .filter(t -> indegree.get(t) == 0)
                .sorted(tieBreak)
                .forEach(initialReady::add);
        shuffleTies(initialReady, rng);
        Deque<BuildingType> ready = new ArrayDeque<>(initialReady);

        List<BuildingType> sortedDistinct = new ArrayList<>();
        while (!ready.isEmpty()) {
            BuildingType t = ready.pollFirst();
            sortedDistinct.add(t);
            Set<BuildingType> outgoing = edges.getOrDefault(t, Set.of());
            // Re-sort newly-zero nodes so the global tie-break is stable.
            List<BuildingType> newlyReady = new ArrayList<>();
            for (BuildingType u : outgoing) {
                int newDeg = indegree.merge(u, -1, Integer::sum);
                if (newDeg == 0) newlyReady.add(u);
            }
            newlyReady.sort(tieBreak);
            shuffleTies(newlyReady, rng);
            for (BuildingType u : newlyReady) ready.addLast(u);
        }

        if (sortedDistinct.size() < present.size()) {
            throw new IllegalStateException(
                    "DependencyResolver: cycle detected among "
                            + present + " — sorted only " + sortedDistinct);
        }

        // Re-expand multiplicities.
        List<BuildingType> out = new ArrayList<>(buildings.size());
        for (BuildingType t : sortedDistinct) {
            int n = counts.getOrDefault(t, 0);
            for (int i = 0; i < n; i++) out.add(t);
        }
        return out;
    }

    /** In-place shuffle of contiguous runs that share priority. The
     *  list is assumed pre-sorted by {@code tieBreak} so equal-priority
     *  runs are already adjacent. {@code rng == null} → no-op. */
    private static void shuffleTies(List<BuildingType> ordered, Random rng) {
        if (rng == null || ordered.size() < 2) return;
        int i = 0;
        while (i < ordered.size()) {
            int j = i + 1;
            int prio = priorityOrdinal(ordered.get(i));
            while (j < ordered.size() && priorityOrdinal(ordered.get(j)) == prio) j++;
            if (j - i > 1) Collections.shuffle(ordered.subList(i, j), rng);
            i = j;
        }
    }

    private static int priorityOrdinal(BuildingType t) {
        PlacementProfile pp = PlacementDefaults.get(t);
        return pp != null ? pp.priority().ordinal() : Integer.MAX_VALUE;
    }
}
