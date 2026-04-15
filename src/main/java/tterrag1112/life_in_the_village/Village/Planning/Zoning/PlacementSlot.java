// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Zoning/PlacementSlot.java
package tterrag1112.life_in_the_village.Village.Planning.Zoning;

import net.minecraft.core.BlockPos;

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
 * @param pos             ideal target position; terrain resolution may nudge it
 * @param feedingRoad     road centerline used to choose rotation; may be null
 * @param tags            all tags this slot advertises
 * @param footprintBudget max footprint (largest of width/length) this slot
 *                        can accommodate — matcher filters candidates by this
 * @param qualityScore    layout's own ranking among peers with the same tag
 *                        class (higher = better); typically 0–100
 */
public record PlacementSlot(BlockPos pos,
                            List<BlockPos> feedingRoad,
                            Set<SlotTag> tags,
                            int footprintBudget,
                            int qualityScore) {
    public PlacementSlot {
        tags = tags == null ? EnumSet.noneOf(SlotTag.class)
                : EnumSet.copyOf(tags);
    }

    public boolean hasAll(Set<SlotTag> required) {
        return tags.containsAll(required);
    }

    public int preferredTagHits(Set<SlotTag> preferred) {
        int n = 0;
        for (SlotTag t : preferred) if (tags.contains(t)) n++;
        return n;
    }
}