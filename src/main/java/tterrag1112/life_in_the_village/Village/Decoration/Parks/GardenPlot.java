package tterrag1112.life_in_the_village.Village.Decoration.Parks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.UUID;

/**
 * B2.4 — durable record of one reserved park area in a village.
 *
 * <p>Uses the standard content-record pattern: an authoritative map
 * keyed by {@link #plotId} with a denormalised per-village index for
 * fast lookup, persisted via {@code VillageSavedData}. Lifecycle:</p>
 * <ul>
 *   <li>Created by {@code ParkCandidateFinder} after V2 Layer 4's
 *       hub designation; stored on
 *       {@link tterrag1112.life_in_the_village.Networking.VillageSavedData}
 *       via {@code addGardenPlot}.</li>
 *   <li>Rendered by {@code ParkRenderer} after building placement
 *       and the decoration pass — preserves natural features inside
 *       {@link #bounds}, then composes primitives from
 *       {@link GardenStyle}'s piece pool.</li>
 *   <li>Removed when the village is despawned.</li>
 * </ul>
 *
 * @param plotId             stable identity (random UUID at creation)
 * @param villageId          owning village's UUID
 * @param bounds             axis-aligned rectangle (XZ only, Y from
 *                           the heightmap at render time)
 * @param style              chosen {@link GardenStyle}
 * @param preserveBias       resolved preserve-bias for this plot
 *                           (copied off the style at planning time
 *                           so the render decision is stable across
 *                           future style edits)
 * @param preservedFeatures  positions of natural features the
 *                           renderer left in place. Empty pre-render;
 *                           filled by the renderer once the plot
 *                           materialises.
 * @param composedPrimitives primitives the renderer added, with
 *                           their world positions. Empty pre-render.
 * @param createdTick        server tick at which the plot was
 *                           reserved
 */
public record GardenPlot(
        UUID plotId,
        UUID villageId,
        Bounds bounds,
        GardenStyle style,
        double preserveBias,
        List<BlockPos> preservedFeatures,
        List<ComposedPrimitive> composedPrimitives,
        long createdTick) {

    public GardenPlot {
        preservedFeatures   = preservedFeatures   == null ? List.of() : List.copyOf(preservedFeatures);
        composedPrimitives  = composedPrimitives  == null ? List.of() : List.copyOf(composedPrimitives);
    }

    public int width()  { return bounds.maxX() - bounds.minX() + 1; }
    public int length() { return bounds.maxZ() - bounds.minZ() + 1; }

    /** True iff {@code pos}'s XZ projection falls inside the plot's
     *  bounds. */
    public boolean contains(BlockPos pos) {
        return pos != null
                && pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }

    /** Axis-aligned XZ rectangle the plot occupies. */
    public record Bounds(int minX, int minZ, int maxX, int maxZ) {

        public boolean overlaps(Bounds other) {
            if (other == null) return false;
            return minX <= other.maxX && maxX >= other.minX
                    && minZ <= other.maxZ && maxZ >= other.minZ;
        }

        public boolean overlaps(int oMinX, int oMinZ, int oMaxX, int oMaxZ) {
            return minX <= oMaxX && maxX >= oMinX
                    && minZ <= oMaxZ && maxZ >= oMinZ;
        }

        public int width()  { return maxX - minX + 1; }
        public int length() { return maxZ - minZ + 1; }

        public static final Codec<Bounds> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("minX").forGetter(Bounds::minX),
                Codec.INT.fieldOf("minZ").forGetter(Bounds::minZ),
                Codec.INT.fieldOf("maxX").forGetter(Bounds::maxX),
                Codec.INT.fieldOf("maxZ").forGetter(Bounds::maxZ)
        ).apply(i, Bounds::new));
    }

    /** A primitive placement recorded for save/reload determinism. */
    public record ComposedPrimitive(ParkPrimitive primitive, BlockPos pos) {
        public static final Codec<ComposedPrimitive> CODEC = RecordCodecBuilder.create(i -> i.group(
                ParkPrimitive.CODEC.fieldOf("primitive")
                        .forGetter(ComposedPrimitive::primitive),
                BlockPos.CODEC.fieldOf("pos")
                        .forGetter(ComposedPrimitive::pos)
        ).apply(i, ComposedPrimitive::new));
    }

    public static final Codec<GardenPlot> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("plotId").forGetter(GardenPlot::plotId),
            UUIDUtil.CODEC.fieldOf("villageId").forGetter(GardenPlot::villageId),
            Bounds.CODEC.fieldOf("bounds").forGetter(GardenPlot::bounds),
            GardenStyle.CODEC.fieldOf("style").forGetter(GardenPlot::style),
            Codec.DOUBLE.optionalFieldOf("preserveBias", 0.5)
                    .forGetter(GardenPlot::preserveBias),
            BlockPos.CODEC.listOf().optionalFieldOf("preservedFeatures", List.of())
                    .forGetter(GardenPlot::preservedFeatures),
            ComposedPrimitive.CODEC.listOf().optionalFieldOf("composedPrimitives", List.of())
                    .forGetter(GardenPlot::composedPrimitives),
            Codec.LONG.optionalFieldOf("createdTick", 0L).forGetter(GardenPlot::createdTick)
    ).apply(i, GardenPlot::new));
}
