package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer1.SyntheticTerrainSource;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer2.ViabilityTier;

import java.util.ArrayList;
import java.util.List;

/**
 * Track E1 — the fixed terrain × forced (tier, inclination) battery
 * the harness runs every invocation. 9 shapes × 4 configs = 36 runs;
 * seeds folded into the configs (one fixed seed each) so the battery
 * is deterministic and small.
 *
 * <p>The four configs span the topology and tier range that matters:
 * <ul>
 *   <li>CITY / AGRICULTURAL — the HOUSE-bug reproducer (biggest
 *       composition, most HOUSE, the canary).</li>
 *   <li>TOWN / INDUSTRIAL — resource-strategy path; exercises
 *       cliff/forest anchors on CLIFF/RIVER/MIXED.</li>
 *   <li>HAMLET / AGRICULTURAL — small-village path; the regression
 *       floor.</li>
 *   <li>TOWN / CIVIC — ANGERDORF / civic-core path; different
 *       topology than the other three.</li>
 * </ul>
 *
 * <p>If a (terrain, config) combination is nonsensical (e.g. CITY on
 * a tiny BASIN), the run aborts naturally — that's a valid measured
 * outcome and the drop histogram captures it. The harness does NOT
 * filter combinations.
 */
public final class Battery {

    public record Config(String label, ViabilityTier tier, Inclination inclination, long seed) {}

    public static final List<Config> CONFIGS = List.of(
            new Config("CITY/AGRI",   ViabilityTier.CITY,   Inclination.AGRICULTURAL, 1001L),
            new Config("TOWN/INDU",   ViabilityTier.TOWN,   Inclination.INDUSTRIAL,   1002L),
            new Config("HAMLET/AGRI", ViabilityTier.HAMLET, Inclination.AGRICULTURAL, 1003L),
            new Config("TOWN/CIVIC",  ViabilityTier.TOWN,   Inclination.CIVIC,        1004L)
    );

    public static final List<SyntheticTerrainSource.Shape> SHAPES = List.of(
            SyntheticTerrainSource.Shape.FLAT,
            SyntheticTerrainSource.Shape.GENTLE_SLOPE,
            SyntheticTerrainSource.Shape.STEEP_SLOPE,
            SyntheticTerrainSource.Shape.VALLEY,
            SyntheticTerrainSource.Shape.RIDGE,
            SyntheticTerrainSource.Shape.CLIFF,
            SyntheticTerrainSource.Shape.RIVER,
            SyntheticTerrainSource.Shape.BASIN,
            SyntheticTerrainSource.Shape.MIXED
    );

    public record Run(SyntheticTerrainSource.Shape shape, Config config) {
        public String label() { return shape.name() + "/" + config.label(); }
    }

    public static List<Run> all() {
        List<Run> out = new ArrayList<>(SHAPES.size() * CONFIGS.size());
        for (SyntheticTerrainSource.Shape s : SHAPES) {
            for (Config c : CONFIGS) {
                out.add(new Run(s, c));
            }
        }
        return out;
    }

    private Battery() {}
}
