package tterrag1112.life_in_the_village.Village.Farms.Complex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BorderStyleId;

import java.util.List;
import java.util.UUID;

/**
 * Detour A — persisted per-plot border style assignment within a
 * {@link FarmComplex}.
 *
 * <p>Carries the plot's "primary" style (used for boundary edges
 * the plot owns alone) plus a list of {@link EdgeStyle} entries
 * for edges where the per-edge tiebreak picked a different style
 * (typically when a neighbour plot owns the shared edge).
 *
 * <p>The empty {@link #edgeStyles} case means every edge uses
 * {@link #primary} — the common case for a perimeter-only plot.
 *
 * @param plotId      cross-ref to a {@code FarmPlot.id}.
 * @param primary     the plot's chosen style from the spec's pool.
 * @param edgeStyles  per-edge overrides; absent entries fall back
 *                    to {@link #primary}.
 */
public record PlotBorders(UUID plotId,
                           BorderStyleId primary,
                           List<EdgeStyle> edgeStyles) {

    public PlotBorders {
        edgeStyles = edgeStyles == null ? List.of() : List.copyOf(edgeStyles);
    }

    private static final Codec<UUID> UUID_STR =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);
    private static final Codec<BorderStyleId> STYLE_CODEC =
            Codec.STRING.xmap(BorderStyleId::valueOf, BorderStyleId::name);

    public static final Codec<PlotBorders> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_STR.fieldOf("plotId").forGetter(PlotBorders::plotId),
            STYLE_CODEC.fieldOf("primary").forGetter(PlotBorders::primary),
            EdgeStyle.CODEC.listOf()
                    .optionalFieldOf("edgeStyles", List.of())
                    .forGetter(PlotBorders::edgeStyles)
    ).apply(i, PlotBorders::new));

    /** Per-edge style override. {@code edgeIndex} is the plot
     *  polygon's start-vertex index for that edge. */
    public record EdgeStyle(int edgeIndex, BorderStyleId style) {

        public static final Codec<EdgeStyle> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("edgeIndex").forGetter(EdgeStyle::edgeIndex),
                STYLE_CODEC.fieldOf("style").forGetter(EdgeStyle::style)
        ).apply(i, EdgeStyle::new));
    }
}
