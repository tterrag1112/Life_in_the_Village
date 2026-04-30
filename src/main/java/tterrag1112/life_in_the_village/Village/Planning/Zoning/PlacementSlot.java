// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/PlacementSlot.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A placement opportunity emitted by a layout primitive during
 * {@code compose()}. The {@code PlacementMatcher} consumes these after
 * all primitives have emitted, matching buildings against slots.
 *
 * <p>Slots are mutable only in the sense of being consumed: once the
 * matcher commits a building to a slot, the slot is removed from the
 * pool. A slot whose {@code tryCommitBuilding} terrain-resolution
 * fails is also burned (not retried), since terrain problems are
 * positional, not building-specific.
 *
 * <p>Phase 6 added {@code feedingEdgeId}, {@code footprintBudgetW},
 * {@code footprintBudgetL}, {@code forcedRotation}, and
 * {@code terrainPenalty}. Fix-up Phase A added {@code maxDriftBlocks}.
 * The legacy 5-arg constructor stays valid; see
 * {@link #PlacementSlot(BlockPos, List, Set, int, int)}.
 *
 * @param pos             ideal target position; terrain resolution may nudge it
 * @param feedingRoad     road centerline used to choose rotation; may be null
 * @param feedingEdgeId   RoadGraph edge id, -1 if none
 * @param tags            all tags this slot advertises
 * @param footprintBudgetW max along-road footprint (width)
 * @param footprintBudgetL max across-road footprint (length)
 * @param forcedRotation  nullable; null = rotation-flexible
 * @param qualityScore    layout's own ranking among peers with the same tag
 *                        class (higher = better); typically 0–100
 * @param terrainPenalty  0 = flat; matcher subtracts from score (Phase 10)
 * @param maxDriftBlocks  perturbation bound: tryCommitWithRetries will
 *                        never commit at a position more than this many
 *                        blocks from {@code pos}. Default 6.
 */
public record PlacementSlot(BlockPos pos,
                            List<BlockPos> feedingRoad,
                            int feedingEdgeId,
                            Set<SlotTag> tags,
                            int footprintBudgetW,
                            int footprintBudgetL,
                            Rotation forcedRotation,
                            int qualityScore,
                            int terrainPenalty,
                            int maxDriftBlocks) {
    public PlacementSlot {
        tags = tags == null ? EnumSet.noneOf(SlotTag.class)
                : EnumSet.copyOf(tags);
    }

    /** Backward-compat 9-arg constructor. Defaults {@code maxDriftBlocks=6}. */
    public PlacementSlot(BlockPos pos, List<BlockPos> feedingRoad,
                         int feedingEdgeId, Set<SlotTag> tags,
                         int footprintBudgetW, int footprintBudgetL,
                         Rotation forcedRotation, int qualityScore,
                         int terrainPenalty) {
        this(pos, feedingRoad, feedingEdgeId, tags,
             footprintBudgetW, footprintBudgetL,
             forcedRotation, qualityScore, terrainPenalty, 6);
    }

    /** Backward-compat 5-arg constructor for code not yet migrated.
     *  Defaults {@code maxDriftBlocks=6}. */
    public PlacementSlot(BlockPos pos, List<BlockPos> feedingRoad,
                         Set<SlotTag> tags, int footprintBudget,
                         int qualityScore) {
        this(pos, feedingRoad, -1, tags,
             footprintBudget, footprintBudget,
             null, qualityScore, 0, 6);
    }

    public boolean hasAll(Set<SlotTag> required) {
        return tags.containsAll(required);
    }

    public int preferredTagHits(Set<SlotTag> preferred) {
        int n = 0;
        for (SlotTag t : preferred) if (tags.contains(t)) n++;
        return n;
    }

    /** Compatibility: returns the larger of W and L for legacy callers
     *  that expected a single footprintBudget value. */
    public int footprintBudget() {
        return Math.max(footprintBudgetW, footprintBudgetL);
    }
}
