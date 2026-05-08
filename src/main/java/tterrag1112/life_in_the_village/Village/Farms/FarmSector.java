package tterrag1112.life_in_the_village.Village.Farms;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.List;
import java.util.UUID;

/**
 * B2.5 — durable record of one farm sector reserved for a village.
 *
 * <p>Mirrors the {@code AdjunctPlot} / {@code GardenPlot} pattern:
 * an authoritative map keyed by {@link #sectorId}, with a denormalised
 * per-village index for fast lookup, persisted via {@code
 * VillageSavedData}.</p>
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>Created by {@code FarmSectorPlanner} after parks have been
 *       reserved (so farms claim leftover arable land that parks
 *       didn't take).</li>
 *   <li>Plots inside the sector are stamped via standard
 *       {@code FarmPlot} records (extended with {@code sectorId} +
 *       polygon bounds in B2.5).</li>
 *   <li>Rendered by {@code FarmSectorRenderer} during
 *       {@code runDownstream} after the building placement / parks
 *       passes — perimeter fence, inter-plot paths, scarecrows,
 *       tool shed NBT placements.</li>
 * </ul>
 *
 * @param sectorId          stable UUID
 * @param villageId         owning village
 * @param bounds            terrain-following polygon (XZ; Y resolved
 *                          per column at render time). Per the B2.5
 *                          decision the polygon is a 4-vertex
 *                          terrain-trimmed rectangle, but the
 *                          {@link Polygon} type supports arbitrary
 *                          simple polygons should later phases
 *                          loosen the rule.
 * @param plotIds           {@code FarmPlot} ids that landed inside
 *                          this sector. Allocated 1..N per farmhouse
 *                          per the per-tier rule.
 * @param farmhouseIds      farmhouse buildings served by this sector
 * @param toolShedPositions positions where tool sheds were placed
 *                          (or attempted) at sector edges. May be
 *                          empty if the sector's perimeter doesn't
 *                          afford a shed footprint.
 * @param createdTick       server tick at reservation time
 */
public record FarmSector(
        UUID sectorId,
        UUID villageId,
        Polygon bounds,
        List<UUID> plotIds,
        List<UUID> farmhouseIds,
        List<BlockPos> toolShedPositions,
        long createdTick) {

    public FarmSector {
        plotIds            = plotIds            == null ? List.of() : List.copyOf(plotIds);
        farmhouseIds       = farmhouseIds       == null ? List.of() : List.copyOf(farmhouseIds);
        toolShedPositions  = toolShedPositions  == null ? List.of() : List.copyOf(toolShedPositions);
    }

    /** Convenience: sector area approximation via the polygon's
     *  bounding-box, useful for plot-count budgeting. */
    public int approximateArea() {
        if (bounds == null || bounds.vertices().isEmpty()) return 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos v : bounds.vertices()) {
            minX = Math.min(minX, v.getX());
            maxX = Math.max(maxX, v.getX());
            minZ = Math.min(minZ, v.getZ());
            maxZ = Math.max(maxZ, v.getZ());
        }
        return Math.max(0, maxX - minX + 1) * Math.max(0, maxZ - minZ + 1);
    }

    public static final Codec<FarmSector> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("sectorId").forGetter(FarmSector::sectorId),
            UUIDUtil.CODEC.fieldOf("villageId").forGetter(FarmSector::villageId),
            BlockPos.CODEC.listOf().fieldOf("vertices")
                    .xmap(Polygon::new, Polygon::vertices)
                    .forGetter(FarmSector::bounds),
            UUIDUtil.CODEC.listOf().optionalFieldOf("plotIds", List.of())
                    .forGetter(FarmSector::plotIds),
            UUIDUtil.CODEC.listOf().optionalFieldOf("farmhouseIds", List.of())
                    .forGetter(FarmSector::farmhouseIds),
            BlockPos.CODEC.listOf().optionalFieldOf("toolShedPositions", List.of())
                    .forGetter(FarmSector::toolShedPositions),
            Codec.LONG.optionalFieldOf("createdTick", 0L)
                    .forGetter(FarmSector::createdTick)
    ).apply(i, FarmSector::new));
}
