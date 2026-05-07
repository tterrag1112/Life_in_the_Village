package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import java.util.List;
import java.util.Optional;

/**
 * V2 Layer 4 → Layer 5 hand-off. Carries the spine, the per-building
 * connectors, and the optional ring. Layer 5 resolves the materials
 * and runs the existing decoration pipeline against the
 * primitives.
 */
public record RoadNetwork(
        Road spine,
        List<Road> connectors,
        Optional<Road> ring) {
}
