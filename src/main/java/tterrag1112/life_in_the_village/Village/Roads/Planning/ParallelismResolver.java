package tterrag1112.life_in_the_village.Village.Roads.Planning;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.AtlasRouteRouter;
import tterrag1112.life_in_the_village.Village.Roads.Economy.EdgeTierManager;
import tterrag1112.life_in_the_village.Village.Roads.Economy.TierPromotionRules;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Merges one parallel edge pair found by {@link ParallelismDetector}.
 *
 * <h3>Procedure</h3>
 * <ol>
 *   <li>Pre-validate — all indices in bounds, both edges present; abort before
 *       any mutation if anything is wrong.</li>
 *   <li>Select survivor (the edge to keep) vs victim using priority rules.</li>
 *   <li>Split victim at the overlap start and end via
 *       {@link WorldRoadGraph#splitEdgeAtCell} — producing victim_head,
 *       victim_middle, and victim_tail.</li>
 *   <li>Transfer victim_middle's maintainers, max maintenance, and cumulative
 *       traffic to the survivor.</li>
 *   <li>Remove victim_middle from the graph.</li>
 *   <li>Unrealize stub edges (victim_head, victim_tail) so the realizer can
 *       re-place them cleanly on the next pass.</li>
 * </ol>
 *
 * <h3>Survivor selection priority</h3>
 * TRUNK beats CONNECTOR beats LOCAL → more maintainers → longer cell path →
 * lower UUID (deterministic tie-break).
 */
public final class ParallelismResolver {

    private ParallelismResolver() {}

    // =========================================================================
    // Result type
    // =========================================================================

    public record ResolveResult(
            boolean success,
            String failureReason,
            UUID removedEdgeId,
            UUID survivorEdgeId,
            List<UUID> leftoverEdgeIds
    ) {
        static ResolveResult failure(String reason) {
            return new ResolveResult(false, reason, null, null, List.of());
        }

        static ResolveResult success(UUID removed, UUID survivor, List<UUID> leftovers) {
            return new ResolveResult(true, null, removed, survivor, List.copyOf(leftovers));
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public static ResolveResult resolvePair(ServerLevel level,
                                             WorldRoadGraph graph,
                                             ParallelismDetector.ParallelPair pair) {
        // ── 1. Pre-validate ──────────────────────────────────────────────────
        RoadEdge edgeA = graph.getEdge(pair.edgeAId());
        RoadEdge edgeB = graph.getEdge(pair.edgeBId());

        if (edgeA == null) return ResolveResult.failure("edgeA not found: " + pair.edgeAId());
        if (edgeB == null) return ResolveResult.failure("edgeB not found: " + pair.edgeBId());

        if (pair.overlapStartIndexA() < 0 || pair.overlapEndIndexA() >= edgeA.getCellPath().size())
            return ResolveResult.failure("overlapA indices out of bounds for edgeA");
        if (pair.overlapStartIndexB() < 0 || pair.overlapEndIndexB() >= edgeB.getCellPath().size())
            return ResolveResult.failure("overlapB indices out of bounds for edgeB");
        if (pair.overlapStartIndexA() >= pair.overlapEndIndexA())
            return ResolveResult.failure("overlapA start >= end — degenerate pair");
        if (pair.overlapStartIndexB() >= pair.overlapEndIndexB())
            return ResolveResult.failure("overlapB start >= end — degenerate pair");

        // ── 2. Select survivor / victim ──────────────────────────────────────
        RoadEdge survivor = selectSurvivor(edgeA, edgeB);
        RoadEdge victim   = (survivor == edgeA) ? edgeB : edgeA;

        int victimStartIdx = (victim == edgeA) ? pair.overlapStartIndexA() : pair.overlapStartIndexB();
        int victimEndIdx   = (victim == edgeA) ? pair.overlapEndIndexA()   : pair.overlapEndIndexB();

        List<Long> victimCellPath = victim.getCellPath();
        long splitStartKey = victimCellPath.get(victimStartIdx);
        long splitEndKey   = victimCellPath.get(victimEndIdx);

        if (splitStartKey == splitEndKey)
            return ResolveResult.failure("split start and end keys are identical — degenerate pair");

        BlockPos junctionStartPos = surfaceSnap(level,
                AtlasRouteRouter.cellKeyToBlockCenter(splitStartKey));
        BlockPos junctionEndPos   = surfaceSnap(level,
                AtlasRouteRouter.cellKeyToBlockCenter(splitEndKey));

        System.out.println("[ParallelismResolver] Resolving pair"
                + " survivor=" + survivor.getEdgeId().toString().substring(0, 8)
                + " (" + survivor.getTier() + ")"
                + " victim=" + victim.getEdgeId().toString().substring(0, 8)
                + " (" + victim.getTier() + ")"
                + " overlapCells=[" + victimStartIdx + ".." + victimEndIdx + "]");

        // ── 3. Split victim at overlap start → victim_head + victim_rest ─────
        Optional<WorldRoadGraph.SplitResult> split1Opt =
                graph.splitEdgeAtCell(victim.getEdgeId(), splitStartKey, junctionStartPos);
        if (split1Opt.isEmpty()) {
            return ResolveResult.failure("First split failed — splitStartKey not in victim path");
        }
        WorldRoadGraph.SplitResult split1 = split1Opt.get();
        // split1.newEdgeAId() = victim_head  (before overlap)
        // split1.newEdgeBId() = victim_rest  (overlap + tail)

        // ── 4. Split victim_rest at overlap end → victim_middle + victim_tail ─
        Optional<WorldRoadGraph.SplitResult> split2Opt =
                graph.splitEdgeAtCell(split1.newEdgeBId(), splitEndKey, junctionEndPos);
        if (split2Opt.isEmpty()) {
            // Graph is partially mutated — leave as-is, caller will retry next pass
            return ResolveResult.failure("Second split failed — splitEndKey not in victim_rest path");
        }
        WorldRoadGraph.SplitResult split2 = split2Opt.get();
        // split2.newEdgeAId() = victim_middle (the parallel segment — to remove)
        // split2.newEdgeBId() = victim_tail   (after overlap — stub)

        // ── 5. Transfer victim_middle's state to survivor ─────────────────────
        RoadEdge victimMiddle = graph.getEdge(split2.newEdgeAId());
        if (victimMiddle != null) {
            for (UUID mid : victimMiddle.getMaintainerVillageIds()) {
                if (!survivor.getMaintainerVillageIds().contains(mid)) {
                    survivor.getMaintainerVillageIds().add(mid);
                }
            }
            survivor.setMaintenance(
                    Math.max(survivor.getMaintenance(), victimMiddle.getMaintenance()));
            survivor.addTraffic(victimMiddle.getTrafficCounter());
        }

        // Survivor's primitive chain is now stale (endpoints may differ after merge)
        survivor.clearPrimitives();

        // D5 Phase 6d: maintainer list just changed — reconcile effective tier
        {
            VillageSavedData vData = VillageSavedData.get(level);
            List<Village> tierMaintainers = new ArrayList<>();
            for (UUID mid : survivor.getMaintainerVillageIds()) {
                vData.getVillageById(mid).ifPresent(tierMaintainers::add);
            }
            if (!tierMaintainers.isEmpty()) {
                RoadEdge.EdgeTier effective = TierPromotionRules.effectiveTier(tierMaintainers);
                if (effective != survivor.getTier()) {
                    EdgeTierManager.applyTierChange(survivor, effective, level, graph);
                }
            }
        }

        UUID removedId = split2.newEdgeAId();

        // ── 6. Remove victim_middle ───────────────────────────────────────────
        graph.removeEdge(removedId);

        System.out.println("[ParallelismResolver] Removed victim_middle "
                + removedId.toString().substring(0, 8)
                + "; transferred maintainers/maintenance/traffic to survivor");

        // ── 7. Unrealize stubs so the realizer re-places them cleanly ─────────
        List<UUID> leftovers = new ArrayList<>();

        RoadEdge victimHead = graph.getEdge(split1.newEdgeAId());
        RoadEdge victimTail = graph.getEdge(split2.newEdgeBId());

        if (victimHead != null) {
            victimHead.unrealize();
            leftovers.add(victimHead.getEdgeId());
        }
        if (victimTail != null) {
            victimTail.unrealize();
            leftovers.add(victimTail.getEdgeId());
        }

        System.out.println("[ParallelismResolver] Stubs unrealized: head="
                + (victimHead != null ? victimHead.getEdgeId().toString().substring(0, 8) : "none")
                + " tail="
                + (victimTail != null ? victimTail.getEdgeId().toString().substring(0, 8) : "none"));

        return ResolveResult.success(removedId, survivor.getEdgeId(), leftovers);
    }

    // =========================================================================
    // Survivor selection
    // =========================================================================

    /**
     * Returns the edge that should survive the merge.
     * Priority: tier (TRUNK > CONNECTOR > LOCAL) → more maintainers → longer
     * cell path → lower UUID (deterministic tie-break).
     */
    private static RoadEdge selectSurvivor(RoadEdge a, RoadEdge b) {
        int tierCmp = tierRank(a.getTier()) - tierRank(b.getTier());
        if (tierCmp != 0) return (tierCmp < 0) ? a : b; // lower rank = better tier

        int maintCmp = b.getMaintainerVillageIds().size() - a.getMaintainerVillageIds().size();
        if (maintCmp != 0) return (maintCmp < 0) ? a : b; // more maintainers wins

        int lenCmp = b.getCellPath().size() - a.getCellPath().size();
        if (lenCmp != 0) return (lenCmp < 0) ? a : b; // longer path wins

        return (a.getEdgeId().compareTo(b.getEdgeId()) < 0) ? a : b; // lower UUID wins
    }

    /** Lower = better tier. GREAT_ROAD is excluded by the detector; assign it highest rank. */
    private static int tierRank(RoadEdge.EdgeTier tier) {
        return switch (tier) {
            case TRUNK      -> 0;
            case CONNECTOR  -> 1;
            case LOCAL      -> 2;
            case GREAT_ROAD -> 3;
            // Track C3.3 — SEA edges aren't included in the
            // parallelism detector; sentinel rank keeps the switch
            // exhaustive.
            case SEA        -> 4;
        };
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    private static BlockPos surfaceSnap(ServerLevel level, BlockPos pos) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        return pos.atY(y);
    }
}
