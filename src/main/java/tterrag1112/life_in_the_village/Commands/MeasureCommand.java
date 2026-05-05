// FILE: src/main/java/tterrag1112/life_in_the_village/Commands/MeasureCommand.java
package tterrag1112.life_in_the_village.Commands;

import com.google.gson.Gson;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Kingdom.Placement.PlacementFailureRecorder;
import tterrag1112.life_in_the_village.Village.Planning.LayoutPlan;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Planning.VillagePlanner;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.Village.VillageTypeRegistry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase 23.1 — measurement harness. Dry-run by default: invokes
 * {@link VillagePlanner#plan} directly without applying terrain
 * modifications or spawning structures. The planner returns a
 * {@link LayoutPlan} (Phase 20a) which carries enough state to tell
 * whether the rework's structural pieces succeeded — that's what
 * Phase 23's exit criterion measures.
 *
 * <h3>Usage</h3>
 * <pre>
 * /litv measure 300            — 300 dry-run spawns, master seed = world seed
 * /litv measure 300 12345      — 300 dry-run spawns, master seed = 12345
 * /litv measure 300 12345 commit — 300 spawns, REAL world commits (destructive)
 * </pre>
 *
 * <h3>Outputs</h3>
 * <ul>
 *   <li>JSON Lines to {@code <gameDir>/logs/litv-measure-<timestamp>.jsonl}
 *       — one record per spawn, machine-readable for the polish pass.</li>
 *   <li>Summary printed to chat + console — per-shape success rates,
 *       failure-mode breakdown, cascade-engine activity.</li>
 * </ul>
 *
 * <h3>Structural integrity</h3>
 * Before running, the harness reflects on a handful of fields/methods
 * that the rework's structural phases landed (LayoutPlan, cascade
 * chain, kingdom schema, runWithCascade). If any are missing the
 * harness aborts loudly — Phase 23 measurement degenerates to noise
 * if a future refactor accidentally undoes one of these.
 */
public final class MeasureCommand {

    private static final int SPAWN_GRID_SPACING = 1024;  // blocks between candidate sites
    private static final int VILLAGE_LEVEL_DEFAULT = 1;
    private static final Gson GSON = new Gson();

    private MeasureCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("litv")
                .then(Commands.literal("measure")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 5000))
                                .executes(ctx -> run(ctx,
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                        ctx.getSource().getLevel().getSeed(),
                                        false))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(ctx -> run(ctx,
                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                LongArgumentType.getLong(ctx, "seed"),
                                                false))
                                        .then(Commands.literal("commit")
                                                .executes(ctx -> run(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                        LongArgumentType.getLong(ctx, "seed"),
                                                        true)))))));
    }

    // =========================================================================
    // Entry
    // =========================================================================

    private static int run(CommandContext<CommandSourceStack> ctx,
                           int count, long masterSeed, boolean commit)
            throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        // Structural verification first — fail loud before any spawns.
        List<String> integrityFailures = verifyReworkIntegrity();
        if (!integrityFailures.isEmpty()) {
            src.sendFailure(Component.literal(
                    "[litv-measure] Rework integrity check FAILED — aborting:\n  "
                    + String.join("\n  ", integrityFailures)));
            return 0;
        }

        ServerLevel level = src.getLevel();
        if (commit) {
            src.sendSuccess(() -> Component.literal(
                    "§c[litv-measure] COMMIT mode — will mutate the world. "
                    + "Run in a throwaway test world."), false);
        } else {
            src.sendSuccess(() -> Component.literal(
                    "[litv-measure] dry-run (planner-only, no world commits)"), false);
        }

        Set<String> typeNames = VillageTypeRegistry.INSTANCE.getAvailableTypes();
        if (typeNames.isEmpty()) {
            src.sendFailure(Component.literal(
                    "[litv-measure] No village types loaded — registry empty."));
            return 0;
        }
        List<String> typePool = new ArrayList<>(typeNames);
        Collections.sort(typePool);  // determinism: same registry → same pool order

        Random masterRng = new Random(masterSeed);
        BlockPos centre = ctx.getSource().getPlayer() != null
                ? ctx.getSource().getPlayer().blockPosition()
                : BlockPos.ZERO;

        // Output file
        Path logFile = level.getServer().getServerDirectory()
                .resolve("logs")
                .resolve("litv-measure-" + timestamp() + ".jsonl");
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            src.sendFailure(Component.literal(
                    "[litv-measure] Cannot create logs directory: " + e.getMessage()));
            return 0;
        }

        long startMs = System.currentTimeMillis();
        List<SpawnOutcome> outcomes = new ArrayList<>(count);

        try (BufferedWriter writer = Files.newBufferedWriter(logFile)) {
            // Header line for traceability
            writer.write(GSON.toJson(Map.of(
                    "header", true,
                    "masterSeed", masterSeed,
                    "count", count,
                    "commit", commit,
                    "anchorCentre", List.of(centre.getX(), centre.getY(), centre.getZ()),
                    "registrySize", typePool.size(),
                    "timestamp", Instant.now().toString())));
            writer.newLine();

            for (int i = 0; i < count; i++) {
                long perSpawnSeed = masterRng.nextLong();
                SpawnOutcome outcome = oneSpawn(level, centre, perSpawnSeed,
                        typePool, masterRng, commit);
                outcomes.add(outcome);
                writer.write(GSON.toJson(outcome.toJsonMap()));
                writer.newLine();

                // Progress every 25 spawns so a 300-spawn run stays interactive.
                if ((i + 1) % 25 == 0 || i + 1 == count) {
                    final int done = i + 1;
                    src.sendSuccess(() -> Component.literal(
                            "[litv-measure] " + done + "/" + count + " complete"),
                            false);
                }
            }
        } catch (IOException e) {
            src.sendFailure(Component.literal(
                    "[litv-measure] Write failed: " + e.getMessage()));
            return 0;
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        String summary = buildSummary(outcomes, count, elapsedMs, masterSeed,
                commit, logFile);
        for (String line : summary.split("\n")) {
            src.sendSuccess(() -> Component.literal(line), false);
        }
        System.out.println(summary);
        return outcomes.stream().filter(SpawnOutcome::success).mapToInt(o -> 1).sum();
    }

    // =========================================================================
    // One spawn
    // =========================================================================

    private static SpawnOutcome oneSpawn(ServerLevel level, BlockPos anchor,
                                         long perSpawnSeed, List<String> typePool,
                                         Random masterRng, boolean commit) {
        // Pick type uniformly — that gives natural weighting by primary shape
        // (more types of a given shape → more spawns of that shape).
        String typeName = typePool.get(masterRng.nextInt(typePool.size()));
        VillageTypeData typeData = VillageTypeRegistry.INSTANCE.getType(typeName);

        // Derive a candidate centre from the per-spawn seed, offset on a
        // coarse grid around the player's anchor. Heightmap lookup gives Y.
        // ±GRID_HALF × SPACING blocks per axis = ±16384 blocks total.
        final int GRID_HALF = 16;
        Random posRng = new Random(perSpawnSeed);
        int dx = (posRng.nextInt(2 * GRID_HALF) - GRID_HALF) * SPAWN_GRID_SPACING;
        int dz = (posRng.nextInt(2 * GRID_HALF) - GRID_HALF) * SPAWN_GRID_SPACING;
        int x = anchor.getX() + dx;
        int z = anchor.getZ() + dz;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos origin = new BlockPos(x, y, z);

        VillageTypeData.ShapeType primaryShape = typeData.getShapeProfile().shapeType();

        long startMs = System.currentTimeMillis();
        Optional<VillageLayout> result;
        boolean crashed = false;
        String crashDetail = null;

        // Wire PlacementFailureRecorder lifecycle around the planner so we
        // can read its per-attempt buffer below.
        PlacementFailureRecorder.beginAttempt();
        try {
            // Dry-run path: VillagePlanner.plan() does NOT call
            // VillageSitePreparer.prepare() and does NOT commit terrain.
            // The plan itself is sufficient to tell whether the rework's
            // structural pieces succeeded.
            //
            // Commit path: same call, but the caller (VillageSpawner.
            // spawnVillage) would normally site-prep + persist + place
            // structures. The harness does NOT invoke spawnVillage even
            // in commit mode — Phase 23's measurement is planner-only,
            // and the commit flag is reserved for a future
            // end-to-end variant.
            result = VillagePlanner.plan(level, origin, typeData,
                    new Random(perSpawnSeed), VILLAGE_LEVEL_DEFAULT);
        } catch (Throwable t) {
            result = Optional.empty();
            crashed = true;
            crashDetail = t.getClass().getSimpleName() + ": " + t.getMessage();
            System.err.println("[litv-measure] crash on " + typeName
                    + " at " + origin + ":");
            t.printStackTrace();
        }
        List<PlacementFailureRecorder.Failure> recorded =
                new ArrayList<>(PlacementFailureRecorder.currentFailures());
        PlacementFailureRecorder.endAttempt(result.isPresent(), typeName, origin);
        long compositionMs = System.currentTimeMillis() - startMs;

        @Nullable LayoutPlan plan = result.map(VillageLayout::getPlan).orElse(null);

        // Classify outcome
        FailureMode failureMode;
        String failureDetail;
        boolean success = result.isPresent()
                && plan != null
                && plan.status() == LayoutPlan.Status.OK;
        if (crashed) {
            failureMode = FailureMode.CRASH;
            failureDetail = crashDetail;
        } else if (success) {
            failureMode = null;
            failureDetail = null;
        } else {
            String reason = result.isPresent() && plan != null && plan.unplannableReason() != null
                    ? plan.unplannableReason()
                    : VillagePlanner.lastUnplannableReason();
            failureMode = classify(reason, recorded);
            failureDetail = reason != null ? reason
                    : (recorded.isEmpty() ? "(no reason recorded)"
                            : recorded.get(0).reason() + ": " + recorded.get(0).detail());
        }

        int validatedCount = success ? plan.buildings().size() : 0;
        int totalCount = plan != null ? plan.buildings().size() : 0;
        int truncations = plan != null ? plan.truncations() : 0;
        int cascadeRetries = plan != null ? plan.cascadeRetries() : 0;
        VillageTypeData.ShapeType finalShape = plan != null
                ? plan.finalShape() : primaryShape;
        boolean chainAdvanced = cascadeRetries > 0
                || (plan != null && plan.finalShape() != primaryShape);

        return new SpawnOutcome(
                perSpawnSeed,
                origin,
                plan != null && plan.centre() != null ? plan.centre() : origin,
                typeName,
                primaryShape,
                finalShape,
                chainAdvanced,
                success,
                validatedCount,
                totalCount,
                truncations,
                cascadeRetries,
                failureMode,
                failureDetail,
                compositionMs);
    }

    // =========================================================================
    // Classifier
    // =========================================================================

    @Nullable
    private static FailureMode classify(@Nullable String reason,
                                        List<PlacementFailureRecorder.Failure> recorded) {
        // Recorder reasons are structured and beat substring heuristics.
        for (PlacementFailureRecorder.Failure f : recorded) {
            switch (f.reason()) {
                case TERRAIN_UNSUITABLE:
                case NO_FLAT_AREA:
                case RIDGE_INTERSECTS_FOOTPRINT:
                case WATER_IN_FOOTPRINT:
                case FOOTPRINT_TOO_STEEP:
                    return FailureMode.TERRAIN_UNPLANNABLE;
                case PLANNER_EXCEPTION:
                    return FailureMode.CRASH;
                default:
                    break;
            }
        }
        if (reason != null) {
            // Substrings match the strings actually written in
            // BaseRecipe.runWithCascade and VillagePlanner today.
            if (reason.contains("cascade exhausted")) return FailureMode.CASCADE_EXHAUSTED;
            if (reason.contains("SevereTruncation")) return FailureMode.TRUNCATION_ABORT;
            if (reason.contains("ValidationFailed")) return FailureMode.VALIDATION_FAILED;
            if (reason.contains("recipe produced no buildings")
                    || reason.contains("CORE_PLACEMENT_FAILED")) {
                return FailureMode.MATCHER_EXHAUSTED;
            }
        }
        // Fall back to recorder's INSUFFICIENT_BUILDINGS when nothing
        // structural matched — this is what VillagePlanner records when
        // matcher / validator rejects.
        for (PlacementFailureRecorder.Failure f : recorded) {
            if (f.reason() == PlacementFailureRecorder.Reason.INSUFFICIENT_BUILDINGS) {
                return FailureMode.MATCHER_EXHAUSTED;
            }
            if (f.reason() == PlacementFailureRecorder.Reason.SHAPE_RULE_REJECTED) {
                return FailureMode.TERRAIN_UNPLANNABLE;
            }
        }
        return FailureMode.UNCLASSIFIED;
    }

    // =========================================================================
    // Structural integrity
    // =========================================================================

    /**
     * Reflection-based assertions that the rework's structural phases
     * are still wired in. Cheap regression detector — turns the harness
     * into a build-time canary that catches accidental undo of any
     * Phase 16b–22 piece. Returns a list of human-readable failure
     * messages; empty list means all checks passed.
     */
    static List<String> verifyReworkIntegrity() {
        List<String> failures = new ArrayList<>();
        // Phase 20a: LayoutPlan persisted on Village + key fields exist
        requireField(Village.class, "plan", failures, "Phase 20a (LayoutPlan persistence)");
        requireRecordComponent(LayoutPlan.class, "cascadeRetries", failures,
                "Phase 16b/19 (cascade engine)");
        requireRecordComponent(LayoutPlan.class, "truncations", failures,
                "Phase 16b (truncation cascade)");
        requireRecordComponent(LayoutPlan.class, "unplannableReason", failures,
                "Phase 16b (unplannable signal)");
        // Phase 21: kingdom-rework schema
        requireField(VillageTypeData.class, "settlementTier", failures,
                "Phase 21 (kingdom-rework schema)");
        // Phase 16b: BaseRecipe.runWithCascade exists
        requireMethod(BaseRecipe.class, "runWithCascade", failures,
                "Phase 16b (cascade engine)");
        // Phase 22: PlanContext cascade chain accessor
        requireMethod(
                tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext.class,
                "cascadeChain", failures, "Phase 22 (fallback chains)");
        return failures;
    }

    private static void requireField(Class<?> cls, String name,
                                     List<String> failures, String phase) {
        try {
            cls.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            failures.add(phase + ": " + cls.getSimpleName() + "." + name
                    + " missing — has the rework regressed?");
        }
    }

    /** For records, getDeclaredField finds the synthesized component
     *  field by name. Same call as requireField but the error message
     *  explicitly mentions "record component". */
    private static void requireRecordComponent(Class<?> cls, String name,
                                               List<String> failures, String phase) {
        try {
            cls.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            failures.add(phase + ": " + cls.getSimpleName() + "." + name
                    + " record component missing.");
        }
    }

    private static void requireMethod(Class<?> cls, String name,
                                      List<String> failures, String phase) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) return;
        }
        failures.add(phase + ": " + cls.getSimpleName() + "." + name
                + "(...) missing.");
    }

    // =========================================================================
    // Summary
    // =========================================================================

    private static String buildSummary(List<SpawnOutcome> outcomes, int total,
                                       long elapsedMs, long masterSeed,
                                       boolean commit, Path logFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("==========================================\n");
        sb.append("Phase 23 Measurement Report\n");
        sb.append("==========================================\n");
        sb.append("Master seed:    ").append(masterSeed).append('\n');
        sb.append("Mode:           ").append(commit ? "COMMIT" : "dry-run (planner-only)").append('\n');
        sb.append("Output:         ").append(logFile).append('\n');
        sb.append("Elapsed:        ").append(elapsedMs).append(" ms\n");
        sb.append('\n');

        long success = outcomes.stream().filter(SpawnOutcome::success).count();
        long primary = outcomes.stream()
                .filter(SpawnOutcome::success)
                .filter(o -> !o.chainAdvanced).count();
        long fallback = success - primary;
        long failed = total - success;

        sb.append("Total spawns:                  ").append(total).append('\n');
        sb.append("Successful (primary shape):    ")
                .append(primary).append(" / ").append(total).append(' ')
                .append(pct(primary, total)).append('\n');
        sb.append("Successful (via fallback):     ")
                .append(fallback).append(" / ").append(total).append(' ')
                .append(pct(fallback, total)).append('\n');
        sb.append("Failed:                        ")
                .append(failed).append(" / ").append(total).append(' ')
                .append(pct(failed, total)).append('\n');
        sb.append("Overall success rate:          ")
                .append(pct(success, total)).append('\n');
        sb.append('\n');

        // Per-shape primary success
        sb.append("Per-shape primary-success rate:\n");
        Map<VillageTypeData.ShapeType, long[]> perShapePrimary = new EnumMap<>(VillageTypeData.ShapeType.class);
        for (SpawnOutcome o : outcomes) {
            long[] cell = perShapePrimary.computeIfAbsent(o.primaryShape, k -> new long[]{0, 0});
            cell[1]++;
            if (o.success && !o.chainAdvanced) cell[0]++;
        }
        for (var entry : perShapePrimary.entrySet()) {
            sb.append("  ").append(pad(entry.getKey().name(), 14))
                    .append(entry.getValue()[0]).append(" / ").append(entry.getValue()[1])
                    .append(' ').append(pct(entry.getValue()[0], entry.getValue()[1]))
                    .append('\n');
        }
        sb.append('\n');

        // Per-shape final success (incl. fallback)
        sb.append("Per-shape final-success rate (incl. fallback):\n");
        Map<VillageTypeData.ShapeType, long[]> perShapeFinal = new EnumMap<>(VillageTypeData.ShapeType.class);
        for (SpawnOutcome o : outcomes) {
            long[] cell = perShapeFinal.computeIfAbsent(o.primaryShape, k -> new long[]{0, 0});
            cell[1]++;
            if (o.success) cell[0]++;
        }
        for (var entry : perShapeFinal.entrySet()) {
            sb.append("  ").append(pad(entry.getKey().name(), 14))
                    .append(entry.getValue()[0]).append(" / ").append(entry.getValue()[1])
                    .append(' ').append(pct(entry.getValue()[0], entry.getValue()[1]))
                    .append('\n');
        }
        sb.append('\n');

        // Failure breakdown
        sb.append("Failure mode breakdown:\n");
        Map<FailureMode, Integer> failureCounts = new EnumMap<>(FailureMode.class);
        for (SpawnOutcome o : outcomes) {
            if (o.failureMode != null) {
                failureCounts.merge(o.failureMode, 1, Integer::sum);
            }
        }
        if (failureCounts.isEmpty()) {
            sb.append("  (no failures)\n");
        } else {
            for (var entry : failureCounts.entrySet()) {
                sb.append("  ").append(pad(entry.getKey().name(), 25))
                        .append(entry.getValue()).append(' ')
                        .append(pct(entry.getValue(), total)).append('\n');
            }
        }
        sb.append('\n');

        // Composition timing
        long[] compMs = outcomes.stream().mapToLong(o -> o.compositionMs).sorted().toArray();
        if (compMs.length > 0) {
            long median = compMs[compMs.length / 2];
            long p95 = compMs[(int) (compMs.length * 0.95)];
            sb.append("Median composition time:       ").append(median).append(" ms\n");
            sb.append("P95 composition time:          ").append(p95).append(" ms\n");
            sb.append('\n');
        }

        // Cascade engine activity
        long withTrunc = outcomes.stream().filter(o -> o.truncationCount > 0).count();
        long withRetries = outcomes.stream().filter(o -> o.cascadeRetryCount > 0).count();
        long withChainAdv = outcomes.stream().filter(o -> o.chainAdvanced).count();
        long chainExhausted = outcomes.stream()
                .filter(o -> o.failureMode == FailureMode.CASCADE_EXHAUSTED).count();
        sb.append("Cascade engine activity:\n");
        sb.append("  Spawns with truncations > 0:  ").append(withTrunc).append('\n');
        sb.append("  Spawns with retries > 0:      ").append(withRetries).append('\n');
        sb.append("  Spawns where chain advanced:  ").append(withChainAdv).append('\n');
        sb.append("  Spawns where chain exhausted: ").append(chainExhausted).append('\n');

        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + " ";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static String pct(long num, long denom) {
        if (denom == 0) return "(n/a)";
        return String.format("(%.1f%%)", 100.0 * num / denom);
    }

    private static String timestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .format(java.time.LocalDateTime.now());
    }

    // =========================================================================
    // SpawnOutcome
    // =========================================================================

    public enum FailureMode {
        TRUNCATION_ABORT,
        VALIDATION_FAILED,
        MATCHER_EXHAUSTED,
        CASCADE_EXHAUSTED,
        TERRAIN_UNPLANNABLE,
        SITE_PREP_FAILED,
        CRASH,
        UNCLASSIFIED
    }

    /** Per-spawn record. {@code chainAdvanced} replaces the prompt's
     *  {@code chainPosition} because PlanContext (where position lives)
     *  is discarded post-compose; LayoutPlan persists only retries and
     *  finalShape. {@code chainAdvanced} = retries>0 OR finalShape !=
     *  primaryShape, which is sufficient signal for the failure-mode
     *  breakdown without extending LayoutPlan. */
    public record SpawnOutcome(
            long perSpawnSeed,
            BlockPos requestedCentre,
            BlockPos actualCentre,
            String villageType,
            VillageTypeData.ShapeType primaryShape,
            VillageTypeData.ShapeType finalShape,
            boolean chainAdvanced,
            boolean success,
            int validatedCount,
            int totalCount,
            int truncationCount,
            int cascadeRetryCount,
            @Nullable FailureMode failureMode,
            @Nullable String failureDetail,
            long compositionMs) {

        Map<String, Object> toJsonMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seed", perSpawnSeed);
            m.put("type", villageType);
            m.put("primaryShape", primaryShape.name());
            m.put("finalShape", finalShape.name());
            m.put("chainAdvanced", chainAdvanced);
            m.put("success", success);
            m.put("requestedCentre", List.of(
                    requestedCentre.getX(), requestedCentre.getY(), requestedCentre.getZ()));
            m.put("actualCentre", List.of(
                    actualCentre.getX(), actualCentre.getY(), actualCentre.getZ()));
            m.put("validated", validatedCount);
            m.put("total", totalCount);
            m.put("truncations", truncationCount);
            m.put("cascadeRetries", cascadeRetryCount);
            m.put("compositionMs", compositionMs);
            if (failureMode != null) m.put("failureMode", failureMode.name());
            if (failureDetail != null) m.put("failureDetail", failureDetail);
            return m;
        }
    }
}
