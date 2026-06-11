package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.InclinationProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Track E1 — composition determinism guard.
 *
 * <p>Two structural assertions that fail loudly if the
 * {@code (siteContext, planSeed) → selection} function reverts to
 * being non-deterministic across JVM starts:
 *
 * <ol>
 *   <li>{@link InclinationProfile#buildings()} must iterate in
 *       {@link BuildingType#ordinal()} order. Pre-fix the
 *       selection-driving collection (then {@code baseCounts()}, now
 *       {@code buildings()}) was a {@code Map.copyOf} /
 *       {@code Set.copyOf}-backed {@code ImmutableCollections} view
 *       whose iteration is salted by a per-JVM-startup random; that
 *       fed {@link
 *       tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.BuildingSelector}'s
 *       shared {@code Random} draws with a different per-type order
 *       each run.</li>
 *   <li>Running the battery twice in the same JVM session must
 *       produce byte-identical per-config {@code requestedPerType}
 *       maps. Catches stateful leaks (static Random, mutable
 *       singleton state). Cross-JVM determinism is enforced by
 *       structural assertion 1.</li>
 * </ol>
 *
 * <p>Bootstrap-dependent (the planner touches Minecraft registries
 * via static initializers); reuses the harness's bootstrap pattern.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SelectionDeterminismTest {

    @BeforeAll
    public void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void inclinationProfileIteratesInOrdinalOrder() {
        // For every inclination, the selection-driving building set must
        // iterate in BuildingType.ordinal() order. Pre-fix this failed
        // sometimes (and always varied across JVM starts) because
        // Map.copyOf returns an ImmutableCollections.MapN whose
        // iteration is salt-randomized.
        //
        // District-era drift (2026-06): InclinationProfile's old
        // baseCounts() Map was replaced by the buildings() Set — the
        // record's compact constructor copies it into an EnumSet
        // explicitly "for deterministic ordinal iteration". That Set is
        // now the collection whose iteration order seeds the selector's
        // per-type Random draws, so the determinism invariant moved onto
        // buildings() and is asserted here.
        for (Inclination inc : Inclination.values()) {
            InclinationProfile p = InclinationProfile.forInclination(inc);
            List<BuildingType> iterOrder = new ArrayList<>(p.buildings());
            List<BuildingType> sortedOrder = new ArrayList<>(iterOrder);
            sortedOrder.sort(Comparator.comparingInt(Enum::ordinal));
            assertEquals(sortedOrder, iterOrder,
                    "InclinationProfile.buildings must iterate in "
                    + "BuildingType.ordinal() order for "
                    + inc + " (selection composition determinism)");
        }
    }

    @Test
    public void batteryTwiceProducesIdenticalSelection() {
        // Within one JVM session, running the battery twice on the
        // same seeds must produce identical requestedPerType maps
        // for every config. Catches stateful leaks and lingering
        // shared mutable singletons. Cross-JVM determinism is
        // enforced by the structural ordinal-order invariant above;
        // this test catches the in-process regressions.
        List<Battery.Run> runs = Battery.all();
        List<Map<BuildingType, Integer>> first = new ArrayList<>(runs.size());
        List<Map<BuildingType, Integer>> second = new ArrayList<>(runs.size());
        for (Battery.Run r : runs) {
            first.add(snapshot(RunExecutor.execute(r).metrics().requestedPerType()));
        }
        for (Battery.Run r : runs) {
            second.add(snapshot(RunExecutor.execute(r).metrics().requestedPerType()));
        }
        for (int i = 0; i < runs.size(); i++) {
            assertEquals(first.get(i), second.get(i),
                    "selection differed across two in-JVM runs for "
                    + runs.get(i).label() + " (in-JVM determinism)");
        }
    }

    private static Map<BuildingType, Integer> snapshot(Map<BuildingType, Integer> in) {
        Map<BuildingType, Integer> copy = new EnumMap<>(BuildingType.class);
        copy.putAll(in);
        return copy;
    }
}
