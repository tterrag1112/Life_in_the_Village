package tterrag1112.life_in_the_village.Village.Farms.Complex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Detour A — persisted farm complex record.
 *
 * <p>Owned by a single farmhouse (1:1 today; a future "shared
 * complex" use-case would relax that to N:1). Wraps the
 * planning-layer {@link
 * tterrag1112.life_in_the_village.Village.Planning.V2.Layer3.ComplexRegion}
 * envelope's polygon, the BSP-subdivided plot references (the
 * plot data lives in {@code FarmPlot} records — single source of
 * truth for plot geometry + crop), the path graph, the optional
 * tool shed location, the per-plot border styles, and the
 * gate-position list.
 *
 * <p>Persistence shape: 9 top-level fields, all round-trippable.
 * Optional fields use {@code optionalFieldOf}; nullable
 * {@link #toolShedPosition} round-trips through
 * {@code Optional<BlockPos>}.
 *
 * <p>The plot list is by ID only — the {@code FarmPlot} records
 * themselves live in {@code VillageSavedData.farmPlots} and
 * point back via their {@code complexId} field. Stage 5 wires
 * the back-pointer; Stage 3 only ships the forward references.
 *
 * @param id                stable complex id; key in
 *                          {@code VillageSavedData.farmComplexes}.
 * @param villageId         owning village.
 * @param farmhouseId       parent farmhouse.
 * @param region            outer polygon (irregular flood-fill).
 * @param plotIds           plot {@code FarmPlot.id}s in BSP-output
 *                          order (matches each plot's polygon).
 * @param pathSegments      spine + branches (ordered: spine first,
 *                          then branches in plot order).
 * @param plotEntries       per-plot connection points; one per
 *                          plot, paired by {@link PlotEntry#plotId}.
 * @param toolShedPosition  null until placed (some tiers skip).
 * @param plotBorders       per-plot border style assignments.
 * @param gatePositions     where paths cross plot perimeters;
 *                          populated by the renderer at spawn
 *                          time, may be empty until then.
 */
public record FarmComplex(
        UUID id,
        UUID villageId,
        UUID farmhouseId,
        Polygon region,
        List<UUID> plotIds,
        List<PathSegment> pathSegments,
        List<PlotEntry> plotEntries,
        BlockPos toolShedPosition,
        List<PlotBorders> plotBorders,
        List<BlockPos> gatePositions) {

    public FarmComplex {
        if (id == null)         throw new IllegalArgumentException("FarmComplex.id required");
        if (villageId == null)  throw new IllegalArgumentException("FarmComplex.villageId required");
        if (farmhouseId == null) throw new IllegalArgumentException("FarmComplex.farmhouseId required");
        if (region == null)     throw new IllegalArgumentException("FarmComplex.region required");
        plotIds        = plotIds        == null ? List.of() : List.copyOf(plotIds);
        pathSegments   = pathSegments   == null ? List.of() : List.copyOf(pathSegments);
        plotEntries    = plotEntries    == null ? List.of() : List.copyOf(plotEntries);
        plotBorders    = plotBorders    == null ? List.of() : List.copyOf(plotBorders);
        gatePositions  = gatePositions  == null ? List.of() : List.copyOf(gatePositions);
        // toolShedPosition nullable; no copy needed.
    }

    private static final Codec<UUID> UUID_STR =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<FarmComplex> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_STR.fieldOf("id").forGetter(FarmComplex::id),
            UUID_STR.fieldOf("villageId").forGetter(FarmComplex::villageId),
            UUID_STR.fieldOf("farmhouseId").forGetter(FarmComplex::farmhouseId),
            Polygon.CODEC.fieldOf("region").forGetter(FarmComplex::region),
            UUID_STR.listOf()
                    .optionalFieldOf("plotIds", List.of())
                    .forGetter(FarmComplex::plotIds),
            PathSegment.CODEC.listOf()
                    .optionalFieldOf("pathSegments", List.of())
                    .forGetter(FarmComplex::pathSegments),
            PlotEntry.CODEC.listOf()
                    .optionalFieldOf("plotEntries", List.of())
                    .forGetter(FarmComplex::plotEntries),
            BlockPos.CODEC.optionalFieldOf("toolShedPosition")
                    .forGetter(c -> Optional.ofNullable(c.toolShedPosition())),
            PlotBorders.CODEC.listOf()
                    .optionalFieldOf("plotBorders", List.of())
                    .forGetter(FarmComplex::plotBorders),
            BlockPos.CODEC.listOf()
                    .optionalFieldOf("gatePositions", List.of())
                    .forGetter(FarmComplex::gatePositions)
    ).apply(i, (id, villageId, farmhouseId, region, plotIds, pathSegments,
                 plotEntries, toolShed, plotBorders, gatePositions) ->
            new FarmComplex(id, villageId, farmhouseId, region, plotIds,
                    pathSegments, plotEntries, toolShed.orElse(null),
                    plotBorders, gatePositions)));
}
