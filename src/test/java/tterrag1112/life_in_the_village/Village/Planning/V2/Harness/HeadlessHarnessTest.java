package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Track E1 — the headless layout harness entry point.
 *
 * <p>Mode dispatch via the {@code harness.mode} system property:
 * <ul>
 *   <li>{@code check} (default) — run the battery, diff vs the
 *       committed baseline, print the full diff table, fail this
 *       test if any gated regression is detected. Non-gating metric
 *       deltas print but do not fail.</li>
 *   <li>{@code record} — run the battery, overwrite the baseline at
 *       {@code harness.baseline}, never fail (unless the planner
 *       crashes).</li>
 * </ul>
 *
 * <p>The committed baseline at {@code harness/baseline.json} is the
 * current system, HOUSE bug and all. Re-recording after a deliberate
 * planner change is the workflow for adjusting the gate; the test
 * failing with a clear delta IS the confirmation prompt that the
 * change moved what was intended.
 *
 * <p>Minecraft's {@code Bootstrap.bootStrap()} is called once in
 * {@code @BeforeAll} so static block / tag references work. The
 * harness does not load a world, generate chunks, or spin up a
 * server — the bootstrap call only seeds the registries the
 * planner's classifier already references inside method bodies.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HeadlessHarnessTest {

    private static final String DEFAULT_BASELINE = "harness/baseline.json";

    @BeforeAll
    public void bootstrapMinecraft() {
        // Mirrors net.minecraft.data.Main's bootstrap sequence.
        // Loads block / item / tag registries without standing up a
        // server or generating any world.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void runBattery() throws Exception {
        String mode = System.getProperty("harness.mode", "check");
        Path baselinePath = Paths.get(
                System.getProperty("harness.baseline", DEFAULT_BASELINE));

        List<Battery.Run> runs = Battery.all();
        List<RunMetrics> current = new ArrayList<>(runs.size());
        long t0 = System.currentTimeMillis();
        for (Battery.Run r : runs) {
            current.add(RunExecutor.execute(r));
        }
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Harness: %d runs in %d ms (%.1f ms/run)%n",
                current.size(), elapsed, elapsed / (double) current.size());

        if ("record".equalsIgnoreCase(mode)) {
            Baseline.write(baselinePath, current);
            System.out.println(Table.render(current, current,
                    new Baseline.DiffResult(List.of(), false)));
            System.out.println("Harness: recorded baseline -> " + baselinePath);
            return;
        }

        // check mode.
        if (!Files.exists(baselinePath)) {
            fail("No baseline at " + baselinePath
                    + " — run with -Dharness.mode=record first.");
        }
        List<RunMetrics> baseline = Baseline.read(baselinePath);
        Baseline.DiffResult diff = Baseline.diff(baseline, current);
        System.out.println(Table.render(current, baseline, diff));

        if (!diff.passed()) {
            fail("Headless harness: " + diff.failures().size()
                    + " gated regression(s) vs baseline. See table above.");
        }
    }
}
