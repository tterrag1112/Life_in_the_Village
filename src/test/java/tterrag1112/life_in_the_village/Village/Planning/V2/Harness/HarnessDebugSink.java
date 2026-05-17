package tterrag1112.life_in_the_village.Village.Planning.V2.Harness;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.DropReason;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Track E1 — harness debug file fan-out (tooling-only, behavior-neutral).
 *
 * <p>When the battery runs, this sink optionally attaches a Log4j2
 * appender to the {@code PhasedPlanner} logger so its per-attempt
 * candidate / placed / dropped lines can be captured per-config and
 * written to {@code build/harness-debug/<TERRAIN>_<CONFIG>.txt}
 * instead of dumping ~3000 lines to stdout. Always writes a HEADER
 * block from the in-memory run result (so files are useful even when
 * {@code -Dharness.debug.candidates} is off); the CANDIDATE TABLE
 * section is populated only when the candidate counter logs are
 * actually being emitted by the planner.
 *
 * <p>If the appender wiring fails for any reason (different log
 * backend, locked configuration, missing class), the sink falls back
 * to HEADER-only output — the battery never fails because of debug
 * tooling. With additivity disabled on the planner logger, its lines
 * are sunk to our buffer instead of the test runner's console so
 * stdout stays at "battery table + DIFF" (matching the in-prompt
 * contract).
 *
 * <p>Single-threaded by design: the harness runs configs sequentially
 * on one JUnit worker, so a shared {@link #buffer} is safe with a
 * minimal {@code synchronized} guard for the appender thread.
 */
public final class HarnessDebugSink {

    /** Real FQCN of the planner logger
     *  ({@code LoggerFactory.getLogger(PhasedPlanner.class)} on
     *  PhasedPlanner.java:79). The console abbreviates this to
     *  {@code tt.li.Vi.Pl.V2.La.PhasedPlanner}. */
    private static final String PLANNER_LOGGER_NAME =
            "tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.PhasedPlanner";

    private static final String OUT_DIR = "build/harness-debug";

    private final List<String> buffer = Collections.synchronizedList(new ArrayList<>());

    private CaptureAppender appender;
    private LoggerConfig addedLoggerConfig;
    private LoggerConfig priorLoggerConfig;
    private boolean addedLogger;
    private boolean priorAdditivity;

    private Path outDir;
    private boolean captureWorking;
    private String wireFailureReason;

    /** Resolve output dir, optionally clean prior files, attach the
     *  capture appender. Never throws — caller can call
     *  {@link #drainAndWrite} regardless. */
    public void start() {
        try {
            outDir = Paths.get(OUT_DIR).toAbsolutePath();
            Files.createDirectories(outDir);
            try (var stream = Files.list(outDir)) {
                stream.forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ignored) {}
                });
            }
        } catch (Exception e) {
            // Output dir setup failed — there's nothing useful we can
            // do; leave outDir null and let writes no-op.
            outDir = null;
            System.err.println("[harness-debug] could not prep output dir: " + e);
            return;
        }
        try {
            attachAppender();
            captureWorking = true;
        } catch (Throwable t) {
            captureWorking = false;
            wireFailureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            System.err.println("[harness-debug] log capture unavailable, "
                    + "files will be HEADER-only: " + wireFailureReason);
        }
    }

    private void attachAppender() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();

        appender = new CaptureAppender("HarnessDebugCapture", buffer);
        appender.start();
        cfg.addAppender(appender);

        LoggerConfig existing = cfg.getLoggerConfig(PLANNER_LOGGER_NAME);
        if (existing != null && PLANNER_LOGGER_NAME.equals(existing.getName())) {
            priorLoggerConfig = existing;
            priorAdditivity = existing.isAdditive();
            existing.addAppender(appender, Level.ALL, null);
            existing.setAdditive(false);
        } else {
            // No dedicated config for the planner logger — synthesise
            // one inheriting the nearest ancestor's level so we don't
            // accidentally silence INFO emissions.
            Level inheritedLevel = existing != null ? existing.getLevel() : Level.INFO;
            LoggerConfig fresh = new LoggerConfig(
                    PLANNER_LOGGER_NAME, inheritedLevel, /*additive*/ false);
            fresh.addAppender(appender, Level.ALL, null);
            cfg.addLogger(PLANNER_LOGGER_NAME, fresh);
            addedLogger = true;
            addedLoggerConfig = fresh;
        }
        ctx.updateLoggers();
    }

    private void detachAppender() {
        if (appender == null) return;
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            if (addedLogger && addedLoggerConfig != null) {
                addedLoggerConfig.removeAppender(appender.getName());
                cfg.removeLogger(PLANNER_LOGGER_NAME);
            } else if (priorLoggerConfig != null) {
                priorLoggerConfig.removeAppender(appender.getName());
                priorLoggerConfig.setAdditive(priorAdditivity);
            }
            appender.stop();
            ctx.updateLoggers();
        } catch (Throwable t) {
            System.err.println("[harness-debug] detach failed (non-fatal): " + t);
        }
    }

    /** Slice the buffer for one config's planner run and write its
     *  per-config file. Always writes the HEADER, populates the
     *  CANDIDATE TABLE only when capture is working. */
    public void drainAndWrite(Battery.Run run, RunExecutor.ExecResult result) {
        if (outDir == null) return;
        List<String> slice = sliceForRun();
        String fileName = run.shape().name() + "_"
                + run.config().label().replace('/', '_') + ".txt";
        Path file = outDir.resolve(fileName);
        String content = render(run, result, slice);
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            System.err.println("[harness-debug] write failed for "
                    + file + ": " + e);
        }
    }

    /** Take buffered lines between the last "PhasedPlanner.run:"
     *  and the following "PhasedPlanner.run done"; clear the
     *  buffer for the next run. */
    private List<String> sliceForRun() {
        List<String> snapshot;
        synchronized (buffer) {
            snapshot = new ArrayList<>(buffer);
            buffer.clear();
        }
        int start = -1;
        int end = snapshot.size();
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            String line = snapshot.get(i);
            if (start < 0 && line.startsWith("PhasedPlanner.run done")) end = i + 1;
            if (line.startsWith("PhasedPlanner.run:")) { start = i; break; }
        }
        if (start < 0) return List.of();
        return snapshot.subList(start, Math.min(end, snapshot.size()));
    }

    public void finish(String tableText) {
        if (outDir != null) {
            try {
                Files.writeString(outDir.resolve("_SUMMARY.txt"), tableText);
            } catch (IOException e) {
                System.err.println("[harness-debug] _SUMMARY write failed: " + e);
            }
        }
        detachAppender();
        if (outDir != null) {
            System.out.println("Harness debug: " + outDir);
        }
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    private String render(Battery.Run run,
                          RunExecutor.ExecResult result,
                          List<String> lines) {
        StringBuilder out = new StringBuilder();
        RunMetrics m = result.metrics();
        RunExecutor.RunContext c = result.context();

        out.append("=== HEADER ===\n");
        out.append("terrain      = ").append(run.shape().name()).append('\n');
        out.append("config       = ").append(run.config().label()).append('\n');
        out.append("seed         = ").append(run.config().seed()).append('\n');
        out.append("strategy     = ").append(c.strategyId()).append('\n');
        out.append("network      = ").append(c.networkNodes()).append(" nodes, ")
                .append(c.networkEdges()).append(" edges, ")
                .append(c.primaryBindings()).append(" primaryBindings\n");
        out.append("spine        = ").append(c.spineSegmentCount())
                .append(" segments, totalLength=")
                .append(String.format("%.1f", c.spineTotalLength())).append('\n');
        out.append("placed/req   = ").append(m.placed()).append("/").append(m.requested())
                .append("  dropped=").append(m.dropped())
                .append("  aborted=").append(m.aborted()).append('\n');
        out.append("selection    = ").append(formatTypeMap(m.requestedPerType())).append('\n');
        out.append("placedCounts = ").append(formatTypeMap(m.placedPerType())).append('\n');
        out.append("dropHist     = ").append(formatDropHist(m.dropHistogram())).append('\n');

        out.append("\n=== CANDIDATE TABLE ===\n");
        if (!captureWorking) {
            out.append("candidate counters: not captured (").append(
                    wireFailureReason != null
                            ? "log capture wiring failed: " + wireFailureReason
                            : "run with JAVA_TOOL_OPTIONS=-Dharness.debug.candidates=true")
                    .append(")\n");
            return out.toString();
        }
        List<TypeRow> rows = aggregate(lines);
        boolean anyCandidateLines = rows.stream().anyMatch(r -> r.attempts > 0);
        if (!anyCandidateLines) {
            out.append("candidate counters: not captured (run with "
                    + "JAVA_TOOL_OPTIONS=-Dharness.debug.candidates=true)\n");
            return out.toString();
        }
        out.append(String.format(
                "%-14s %-6s %-5s %-4s %-4s %-4s %-8s %-22s %-4s %-10s%n",
                "TYPE", "phase", "found", "att", "plc", "drp",
                "cellsScan", "rej[res/cor/scr/adj]",
                "acc", "res@1..res@N"));
        for (TypeRow r : rows) {
            out.append(String.format(
                    "%-14s %-6s %-5s %-4d %-4d %-4d %-8d %-22s %-4d %d..%d%n",
                    r.type.name(),
                    r.phase == null ? "-" : r.phase,
                    r.foundationSeen ? (r.foundationTrue ? "T" : "F") : "-",
                    r.attempts,
                    r.placedCount,
                    r.droppedCount,
                    r.firstCellsScanned,
                    "[" + r.rejReservation + "/" + r.rejCorridor + "/"
                            + r.rejScore + "/" + r.rejAdjunct + "]",
                    r.totalAccepted,
                    r.firstReservations,
                    r.lastReservations));
        }
        return out.toString();
    }

    private static String formatTypeMap(Map<BuildingType, Integer> map) {
        if (map == null || map.isEmpty()) return "{}";
        Map<BuildingType, Integer> sorted = new TreeMap<>(map);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : sorted.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey().name()).append("=").append(e.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }

    private static String formatDropHist(Map<DropReason, Integer> hist) {
        if (hist == null || hist.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : hist.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey().name()).append("=").append(e.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }

    // =========================================================================
    // Aggregation
    // =========================================================================

    /** Compiled once for the slice parse. The planner format string is
     *  {@code "candidates type={} foundation={} cellsScanned={} "
     *  + "rejected[reservation={} corridor={} score={} adjunct={}] "
     *  + "accepted={} reservations={}"} — see PhasedPlanner.java:809. */
    private static final Pattern CAND_LINE = Pattern.compile(
            "candidates type=(\\S+) foundation=(\\S+) cellsScanned=(\\d+) "
                    + "rejected\\[reservation=(\\d+) corridor=(\\d+) "
                    + "score=(\\d+) adjunct=(\\d+)\\] accepted=(\\d+) "
                    + "reservations=(\\d+)");
    /** PhasedPlanner.java:611. */
    private static final Pattern PLACED_LINE = Pattern.compile(
            "placed (\\S+): phase=(\\S+)");
    /** PhasedPlanner.java:588. */
    private static final Pattern DROPPED_LINE = Pattern.compile(
            "dropped (\\S+): \\S+ phase=(\\S+)");

    private static final class TypeRow {
        final BuildingType type;
        String phase;
        boolean foundationSeen;
        boolean foundationTrue;
        int attempts;
        int placedCount;
        int droppedCount;
        int firstCellsScanned;
        int rejReservation;
        int rejCorridor;
        int rejScore;
        int rejAdjunct;
        int totalAccepted;
        int firstReservations = -1;
        int lastReservations = -1;
        TypeRow(BuildingType t) { this.type = t; }
    }

    private static List<TypeRow> aggregate(List<String> lines) {
        Map<BuildingType, TypeRow> rows = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher mc = CAND_LINE.matcher(line);
            if (mc.find()) {
                BuildingType t = parseType(mc.group(1));
                if (t == null) continue;
                TypeRow row = rows.computeIfAbsent(t, TypeRow::new);
                row.attempts++;
                if (!row.foundationSeen) {
                    row.foundationSeen = true;
                    row.foundationTrue = Boolean.parseBoolean(mc.group(2));
                    row.firstCellsScanned = Integer.parseInt(mc.group(3));
                    row.firstReservations = Integer.parseInt(mc.group(9));
                }
                row.rejReservation += Integer.parseInt(mc.group(4));
                row.rejCorridor += Integer.parseInt(mc.group(5));
                row.rejScore += Integer.parseInt(mc.group(6));
                row.rejAdjunct += Integer.parseInt(mc.group(7));
                row.totalAccepted += Integer.parseInt(mc.group(8));
                row.lastReservations = Integer.parseInt(mc.group(9));
                continue;
            }
            Matcher mp = PLACED_LINE.matcher(line);
            if (mp.find()) {
                BuildingType t = parseType(mp.group(1));
                if (t == null) continue;
                TypeRow row = rows.computeIfAbsent(t, TypeRow::new);
                row.placedCount++;
                if (row.phase == null) row.phase = mp.group(2);
                continue;
            }
            Matcher md = DROPPED_LINE.matcher(line);
            if (md.find()) {
                BuildingType t = parseType(md.group(1));
                if (t == null) continue;
                TypeRow row = rows.computeIfAbsent(t, TypeRow::new);
                row.droppedCount++;
                if (row.phase == null) row.phase = md.group(2);
            }
        }
        return new ArrayList<>(rows.values());
    }

    private static BuildingType parseType(String name) {
        try { return BuildingType.valueOf(name); }
        catch (IllegalArgumentException e) { return null; }
    }

    // =========================================================================
    // Capture appender
    // =========================================================================

    /** Minimal Log4j2 appender — stores formatted messages in the
     *  shared buffer. Layout-free (we don't need timestamps; the
     *  per-config slice is delimited by message markers). */
    private static final class CaptureAppender extends AbstractAppender {
        private final List<String> sink;

        CaptureAppender(String name, List<String> sink) {
            super(name, null, null, /*ignoreExceptions*/ true, Property.EMPTY_ARRAY);
            this.sink = sink;
        }

        @Override
        public void append(LogEvent event) {
            try {
                sink.add(event.getMessage().getFormattedMessage());
            } catch (Throwable ignored) {
                // Never let a logging hiccup escape into planner code.
            }
        }
    }
}
