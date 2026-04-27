package tterrag1112.life_in_the_village.Village.Decoration.Variants;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Doc 15 §"Color assignment" step 3 — running index of placed-
 * building primary colours used to compute the local-neighbour soft
 * exclusion at sampling time.
 *
 * <p>Built up incrementally during a single {@code VillageSpawner}
 * placement loop: every successful placement that yielded a non-null
 * primary colour gets recorded; every subsequent {@link
 * #colorsWithin(BlockPos, int)} query consults the running list.</p>
 *
 * <p>Backing storage is a plain {@code ArrayList} — fine for the
 * 1–~30 buildings a village ever contains, but linear in placement
 * count. If villages grow into the hundreds (e.g. CITY-tier with
 * dense filler), swap for a chunk-keyed map. Performance note in
 * the task summary.</p>
 *
 * <p>One instance per village placement run — there is no global
 * sharing.</p>
 */
public final class NeighborColorIndex {

    private record Entry(BlockPos centre, DyeColor primary) {}

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Records a placed building's centre + primary colour. {@code
     * primary == null} (no-tint building) is a no-op so neighbours
     * with no colour can't poison the soft exclusion.
     */
    public void add(BlockPos centre, DyeColor primary) {
        if (centre == null || primary == null) return;
        entries.add(new Entry(centre, primary));
    }

    /**
     * Returns the set of primary colours among already-recorded
     * neighbours within {@code radius} blocks (XZ Euclidean distance)
     * of {@code centre}.
     */
    public Set<DyeColor> colorsWithin(BlockPos centre, int radius) {
        if (centre == null || entries.isEmpty()) {
            return EnumSet.noneOf(DyeColor.class);
        }
        long rsq = (long) radius * radius;
        Set<DyeColor> result = EnumSet.noneOf(DyeColor.class);
        for (Entry e : entries) {
            long dx = e.centre.getX() - centre.getX();
            long dz = e.centre.getZ() - centre.getZ();
            if (dx * dx + dz * dz <= rsq) result.add(e.primary);
        }
        return result;
    }

    public int size() { return entries.size(); }
}
