package tterrag1112.life_in_the_village.Village.Planning.V2.Layer3;

import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public static List<BuildingType> topoSort(List<BuildingType> buildings) {
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
        Deque<BuildingType> ready = new ArrayDeque<>();
        present.stream()
                .filter(t -> indegree.get(t) == 0)
                .sorted(tieBreak)
                .forEach(ready::addLast);

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
}
