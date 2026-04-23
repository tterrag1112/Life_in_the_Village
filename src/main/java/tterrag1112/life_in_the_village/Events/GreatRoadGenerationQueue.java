package tterrag1112.life_in_the_village.Events;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GraphInvariantValidator;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadNode;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadAnchorSeeder;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadAnchorSeeder.AnchorCandidate;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadTrunkRouter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadTrunkRouter.AnchorPair;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.GreatRoadTrunkRouter.PlannedTrunk;
import tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen.NamedRoadSelector;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Cross-tick task queue for generating the Old Realm great road skeleton at
 * world creation.
 *
 * <h3>Execution model</h3>
 * One task is processed per server tick. Tasks are:
 * <ol>
 *   <li>{@link StepType#SEED_ANCHORS} — run once, populates anchor list and
 *       queues fill+route tasks for every pair.</li>
 *   <li>{@link StepType#FILL_ATLAS_CORRIDOR} — fills the atlas region along
 *       one anchor-pair corridor; retries on subsequent ticks until complete,
 *       then queues a {@link StepType#ROUTE_TRUNK} task.</li>
 *   <li>{@link StepType#ROUTE_TRUNK} — A* routes one pair; queues
 *       {@link StepType#COMMIT_EDGE} on success.</li>
 *   <li>{@link StepType#COMMIT_EDGE} — commits nodes and edge to
 *       {@link WorldRoadGraph}; marks the saved data dirty.</li>
 * </ol>
 *
 * <h3>Idempotence</h3>
 * On server start, if {@code WorldRoadSavedData.isGreatRoadGenerationComplete()}
 * is false, generation is (re-)scheduled. Because {@link GreatRoadAnchorSeeder}
 * is fully deterministic, re-running produces the same anchor positions.
 * {@link CommitEdgeTask} skips pairs whose edge already exists in the graph,
 * so a restart mid-generation resumes cleanly without duplicating edges.
 *
 * <h3>Generation scope</h3>
 * Initial generation covers a 40 000 × 40 000 block region centred on the
 * world origin (x ∈ [−20 000, 20 000], z ∈ [−20 000, 20 000]).
 */
public final class GreatRoadGenerationQueue {

    private GreatRoadGenerationQueue() {}

    // ── Generation region ─────────────────────────────────────────────────────
    static final int GENERATION_REGION_HALF = 20_000;

    // ── Static state (one active generation per JVM session) ─────────────────

    /** Whether we have run the first-tick initialization check. */
    private static boolean initialized = false;
    /** Whether scheduleGeneration has been called (i.e., generation is in flight). */
    private static boolean generationStarted = false;
    /** Whether the NAME_SELECTION task has been queued (prevents double-queuing). */
    private static boolean namingScheduled = false;

    /** The main task queue. Processed one task per tick. */
    private static final Deque<GenTask> taskQueue = new ArrayDeque<>();

    /** Anchors computed during SEED_ANCHORS; null before seeding. */
    private static List<AnchorCandidate> seededAnchors = null;

    // Statistics
    private static int totalExpectedAnchors = 0;
    private static int committedAnchors     = 0;
    private static int totalPairs           = 0;
    private static int completedTrunks      = 0;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Enqueues generation work for a world. Called once per world (or on
     * server restart when the flag is still false).
     */
    public static void scheduleGeneration(ServerLevel level, long worldSeed) {
        taskQueue.clear();
        seededAnchors        = null;
        totalExpectedAnchors = 0;
        committedAnchors     = 0;
        totalPairs           = 0;
        completedTrunks      = 0;
        generationStarted    = true;
        namingScheduled      = false;

        taskQueue.add(new SeedAnchorsTask(worldSeed, GENERATION_REGION_HALF));
        System.out.println("[GreatRoadGen] Scheduled generation for seed " + worldSeed
                + " (region ±" + GENERATION_REGION_HALF + " blocks)");
    }

    /**
     * Processes one step from the queue. Must be called every server tick by
     * {@code GreatRoadGenerationTickSystem}.
     */
    public static void tick(ServerLevel level) {
        // One-time per-session initialization
        if (!initialized) {
            initialized = true;
            WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
            if (!roadData.isGreatRoadGenerationComplete()) {
                System.out.println("[GreatRoadGen] Great-road generation not complete — scheduling.");
                scheduleGeneration(level, level.getSeed());
            }
        }

        if (!generationStarted) return;
        if (isComplete(level)) return;

        // If the queue is empty, all tasks have been processed
        if (taskQueue.isEmpty()) {
            onGenerationComplete(level);
            return;
        }

        GenTask task = taskQueue.peek();
        boolean done = task.process(level);
        if (done) taskQueue.poll();
    }

    /** @return true once {@code WorldRoadSavedData.isGreatRoadGenerationComplete()} is set. */
    public static boolean isComplete(ServerLevel level) {
        return WorldRoadSavedData.get(level).isGreatRoadGenerationComplete();
    }

    /**
     * Runs all remaining tasks synchronously (for tests and
     * {@code force_complete_generation} debug command). May cause lag on large
     * regions.
     *
     * @return the number of task-processing steps executed
     */
    public static int forceComplete(ServerLevel level) {
        if (!generationStarted) {
            WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
            if (!roadData.isGreatRoadGenerationComplete()) {
                scheduleGeneration(level, level.getSeed());
            }
        }
        int steps = 0;
        while (!isComplete(level) && steps < 100_000) {
            if (taskQueue.isEmpty()) {
                onGenerationComplete(level); // queues NAME_SELECTION if not yet scheduled
                if (taskQueue.isEmpty()) break;
            }
            GenTask task = taskQueue.peek();
            boolean done = task.process(level);
            if (done) taskQueue.poll();
            steps++;
        }
        return steps;
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /** Returns the last set of seeded anchors, or null if not yet seeded. */
    public static List<AnchorCandidate> getSeededAnchors() { return seededAnchors; }

    public static int getRemainingTaskCount()  { return taskQueue.size(); }
    public static int getCompletedTrunks()     { return completedTrunks; }
    public static int getTotalPairs()          { return totalPairs; }
    public static int getTotalExpectedAnchors() { return totalExpectedAnchors; }
    public static int getCommittedAnchors()    { return committedAnchors; }

    /** Snapshot of step-type counts in the remaining queue, for status reporting. */
    public static Map<StepType, Integer> getQueueSnapshot() {
        Map<StepType, Integer> counts = new EnumMap<>(StepType.class);
        for (GenTask t : taskQueue) {
            counts.merge(t.stepType(), 1, Integer::sum);
        }
        return counts;
    }

    // =========================================================================
    // Step type enum
    // =========================================================================

    public enum StepType {
        SEED_ANCHORS,
        FILL_ATLAS_CORRIDOR,
        ROUTE_TRUNK,
        COMMIT_EDGE,
        NAME_SELECTION
    }

    // =========================================================================
    // Task interface
    // =========================================================================

    private interface GenTask {
        StepType stepType();
        /**
         * Process one tick of this task.
         * @return true when this task is complete and should be removed from the queue
         */
        boolean process(ServerLevel level);
    }

    // =========================================================================
    // SEED_ANCHORS task
    // =========================================================================

    private static final class SeedAnchorsTask implements GenTask {
        private final long worldSeed;
        private final int  halfRegion;

        SeedAnchorsTask(long worldSeed, int halfRegion) {
            this.worldSeed  = worldSeed;
            this.halfRegion = halfRegion;
        }

        @Override public StepType stepType() { return StepType.SEED_ANCHORS; }

        @Override
        public boolean process(ServerLevel level) {
            WorldAtlas atlas = WorldAtlas.get(level);

            seededAnchors = GreatRoadAnchorSeeder.seedAnchors(
                    worldSeed,
                    -halfRegion, -halfRegion, halfRegion, halfRegion,
                    level, atlas);

            totalExpectedAnchors = seededAnchors.size();
            System.out.println("[GreatRoadGen] Seeded " + totalExpectedAnchors
                    + " anchors (axis="
                    + GreatRoadAnchorSeeder.getDominantAxisName(
                            GreatRoadAnchorSeeder.getDominantAxisIndex(worldSeed))
                    + ").");

            // Determine pairs and queue FILL tasks for each
            List<AnchorPair> pairs = GreatRoadTrunkRouter.determinePairs(seededAnchors, worldSeed);
            totalPairs = pairs.size();
            System.out.println("[GreatRoadGen] Determined " + totalPairs + " pairs to route.");

            for (AnchorPair pair : pairs) {
                taskQueue.add(new FillAtlasCorridorTask(pair.a(), pair.b(), worldSeed));
            }

            return true; // single-tick step
        }
    }

    // =========================================================================
    // FILL_ATLAS_CORRIDOR task
    // =========================================================================

    private static final class FillAtlasCorridorTask implements GenTask {
        private final AnchorCandidate a;
        private final AnchorCandidate b;
        private final long worldSeed;

        FillAtlasCorridorTask(AnchorCandidate a, AnchorCandidate b, long worldSeed) {
            this.a         = a;
            this.b         = b;
            this.worldSeed = worldSeed;
        }

        @Override public StepType stepType() { return StepType.FILL_ATLAS_CORRIDOR; }

        @Override
        public boolean process(ServerLevel level) {
            WorldAtlas atlas = WorldAtlas.get(level);
            BlockPos posA = a.position();
            BlockPos posB = b.position();

            int mx = (posA.getX() + posB.getX()) / 2;
            int mz = (posA.getZ() + posB.getZ()) / 2;
            long dx = (long)(posB.getX() - posA.getX());
            long dz = (long)(posB.getZ() - posA.getZ());
            int halfDist = (int)(Math.sqrt((double)(dx * dx + dz * dz)) / 2.0);
            int radius   = Math.min(halfDist + 500, 8_000);

            boolean filled = atlas.ensureRegionFilled(level, mx, mz, radius, 50_000_000L);
            if (filled) {
                // Atlas is ready — queue the routing step at the end of the queue
                taskQueue.add(new RouteTrunkTask(a, b, worldSeed));
            }
            return filled;
        }
    }

    // =========================================================================
    // ROUTE_TRUNK task
    // =========================================================================

    private static final class RouteTrunkTask implements GenTask {
        private final AnchorCandidate a;
        private final AnchorCandidate b;
        private final long worldSeed;

        RouteTrunkTask(AnchorCandidate a, AnchorCandidate b, long worldSeed) {
            this.a         = a;
            this.b         = b;
            this.worldSeed = worldSeed;
        }

        @Override public StepType stepType() { return StepType.ROUTE_TRUNK; }

        @Override
        public boolean process(ServerLevel level) {
            WorldAtlas atlas = WorldAtlas.get(level);

            PlannedTrunk trunk = GreatRoadTrunkRouter.routePair(a, b, atlas, worldSeed);

            if (trunk != null) {
                taskQueue.add(new CommitEdgeTask(trunk));
            } else {
                // Route failed — anchor may become an orphan; log and continue
                System.out.println("[GreatRoadGen] Warning: failed to route "
                        + a.position().toShortString() + " → " + b.position().toShortString()
                        + ". Anchors remain in graph; no trunk edge committed.");
                completedTrunks++;
            }
            return true; // one A* per tick
        }
    }

    // =========================================================================
    // COMMIT_EDGE task
    // =========================================================================

    private static final class CommitEdgeTask implements GenTask {
        private final PlannedTrunk trunk;

        CommitEdgeTask(PlannedTrunk trunk) { this.trunk = trunk; }

        @Override public StepType stepType() { return StepType.COMMIT_EDGE; }

        @Override
        public boolean process(ServerLevel level) {
            WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
            WorldRoadGraph     graph    = roadData.getGraph();

            RoadNode nodeA = findOrCreateAnchorNode(graph, trunk.anchorA());
            RoadNode nodeB = findOrCreateAnchorNode(graph, trunk.anchorB());

            // Idempotence: skip if a GREAT_ROAD edge between these nodes already exists
            for (RoadEdge ex : graph.allEdges()) {
                if (ex.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;
                if ((ex.getNodeAId().equals(nodeA.nodeId())
                        && ex.getNodeBId().equals(nodeB.nodeId()))
                    || (ex.getNodeAId().equals(nodeB.nodeId())
                        && ex.getNodeBId().equals(nodeA.nodeId()))) {
                    System.out.println("[GreatRoadGen] Trunk already committed — skipping.");
                    completedTrunks++;
                    return true;
                }
            }

            // Seeded meander profile — stable visual character across re-realizations
            long meanderSeed = hashTrunk(trunk.anchorA().position(),
                                         trunk.anchorB().position(),
                                         level.getSeed());
            RoadEdge.MeanderProfile meander = new RoadEdge.MeanderProfile(
                    12.0f, 0.08f, meanderSeed);

            RoadEdge edge = RoadEdge.create(
                    nodeA.nodeId(), nodeB.nodeId(),
                    trunk.cellPath(), RoadEdge.EdgeTier.GREAT_ROAD, meander);

            // Invariant 3 — great roads never decay; start at 100 and stay there
            edge.setMaintenance(100);
            // Invariant 4 — no maintainer; maintainerVillageIds is empty by default

            graph.addEdge(edge);
            roadData.markDirty();
            completedTrunks++;

            System.out.println("[GreatRoadGen] Trunk committed: "
                    + trunk.anchorA().position().toShortString() + " → "
                    + trunk.anchorB().position().toShortString()
                    + " [" + trunk.cellPath().size() + " cells"
                    + (trunk.onDominantAxis() ? ", AXIS" : "") + "]");

            return true;
        }

        /** Finds an existing GREAT_ROAD_ANCHOR within 100 blocks, or creates one. */
        private RoadNode findOrCreateAnchorNode(WorldRoadGraph graph, AnchorCandidate anchor) {
            BlockPos target = anchor.position();
            for (RoadNode node : graph.allNodes()) {
                if (node.type() != RoadNode.NodeType.GREAT_ROAD_ANCHOR) continue;
                long dx = node.position().getX() - target.getX();
                long dz = node.position().getZ() - target.getZ();
                if (dx * dx + dz * dz < 100L * 100L) return node;
            }
            RoadNode node = new RoadNode(UUID.randomUUID(), target,
                    RoadNode.NodeType.GREAT_ROAD_ANCHOR,
                    Optional.empty()); // Old Realm — no kingdom affinity
            graph.addNode(node);
            committedAnchors++;
            return node;
        }
    }

    // =========================================================================
    // NAME_SELECTION task
    // =========================================================================

    private static final class NameSelectionTask implements GenTask {
        @Override public StepType stepType() { return StepType.NAME_SELECTION; }

        @Override
        public boolean process(ServerLevel level) {
            WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
            WorldRoadGraph     graph    = roadData.getGraph();
            long worldSeed = level.getSeed();

            List<UUID> toName = NamedRoadSelector.selectEdgesToName(graph);
            NamedRoadSelector.nameSelectedEdges(graph, toName, worldSeed);

            System.out.println("[GreatRoadGen] Named " + toName.size() + " great road(s).");

            roadData.setGreatRoadGenerationComplete(true);
            roadData.markDirty();

            System.out.println("[GreatRoadGen] Generation complete: "
                    + committedAnchors + " anchors, "
                    + completedTrunks + " trunks committed"
                    + " (" + (totalPairs - completedTrunks) + " pairs failed or skipped).");
            return true;
        }
    }

    // =========================================================================
    // Post-generation
    // =========================================================================

    private static void onGenerationComplete(ServerLevel level) {
        if (namingScheduled) return;
        namingScheduled = true;

        WorldRoadSavedData roadData = WorldRoadSavedData.get(level);
        WorldRoadGraph     graph    = roadData.getGraph();

        verifyGreatRoads(graph);

        // Run general invariant validator before naming
        List<String> warnings = GraphInvariantValidator.validate(graph);
        for (String w : warnings) {
            System.out.println("[GreatRoadGen] Invariant: " + w);
        }

        System.out.println("[GreatRoadGen] All trunks committed ("
                + committedAnchors + " anchors, "
                + completedTrunks + " trunks). Scheduling name selection.");

        taskQueue.add(new NameSelectionTask());
    }

    /** Spot-checks great-road invariants (3 and 4) after generation. */
    private static void verifyGreatRoads(WorldRoadGraph graph) {
        int issues = 0;
        for (RoadEdge edge : graph.allEdges()) {
            if (edge.getTier() != RoadEdge.EdgeTier.GREAT_ROAD) continue;
            if (!edge.getMaintainerVillageIds().isEmpty()) {
                System.out.println("[GreatRoadGen] WARN invariant 4: edge "
                        + edge.getEdgeId().toString().substring(0, 8)
                        + " has maintainer(s)");
                issues++;
            }
            if (edge.getMaintenance() != 100) {
                System.out.println("[GreatRoadGen] WARN invariant 3: edge "
                        + edge.getEdgeId().toString().substring(0, 8)
                        + " maintenance=" + edge.getMaintenance() + " (expected 100)");
                issues++;
            }
        }
        for (RoadNode node : graph.allNodes()) {
            if (node.type() != RoadNode.NodeType.GREAT_ROAD_ANCHOR) continue;
            if (node.kingdomAffinity().isPresent()) {
                System.out.println("[GreatRoadGen] WARN invariant 4: anchor "
                        + node.nodeId().toString().substring(0, 8)
                        + " has kingdom affinity");
                issues++;
            }
        }
        if (issues == 0) {
            System.out.println("[GreatRoadGen] Great-road invariants: PASS");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Stable hash for a trunk's meander seed — order-independent so the same
     * road always gets the same seed regardless of which endpoint is A vs B.
     */
    private static long hashTrunk(BlockPos posA, BlockPos posB, long worldSeed) {
        long ha = (long) posA.getX() * 73856093L ^ (long) posA.getZ() * 19349663L;
        long hb = (long) posB.getX() * 73856093L ^ (long) posB.getZ() * 19349663L;
        return (ha ^ hb) * 6364136223846793005L + worldSeed;
    }
}
