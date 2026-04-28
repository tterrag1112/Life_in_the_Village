package tterrag1112.life_in_the_village.Village.Decoration.Adjunct;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.Rotation;

import java.util.UUID;

/**
 * Doc 02 §"Data structures" — record of one placed
 * {@link AdjunctPlotType} on a parent {@link
 * tterrag1112.life_in_the_village.Village.Building}.
 *
 * <p>Persisted on {@code VillageSavedData} alongside
 * {@code FarmPlot}. Lifecycle:</p>
 * <ul>
 *   <li>Created by {@link AdjunctPlotPlacer#tryPlace} during the
 *       realiser pass between building foundation and the
 *       decoration pass.</li>
 *   <li>Stored under {@code adjunctPlots} keyed by
 *       {@link #plotId}; denormalised by
 *       {@code parentBuildingId} for fast per-building lookup.</li>
 *   <li>Removed when the parent building is removed via
 *       {@code VillageSavedData.removeBuilding}'s cleanup hook.</li>
 * </ul>
 *
 * @param plotId            stable identity (random UUID at creation)
 * @param parentBuildingId  owning building's UUID
 * @param type              what kind of plot this is
 * @param origin            footprint centre, at the building's
 *                          floor Y (placer takes the median of
 *                          the sampled heightmap)
 * @param halfWidthX        half-extent along the parent's local X
 *                          axis (matches the parent's facing)
 * @param halfLengthZ       half-extent along the parent's local Z
 *                          axis
 * @param facingFromParent  which side of the parent the plot
 *                          succeeded on — useful for downstream
 *                          decoration emission and NPC pathing
 * @param rotation          matches the parent's rotation (or the
 *                          rotation the plot was placed with if a
 *                          future override is added)
 */
public record AdjunctPlot(
        UUID plotId,
        UUID parentBuildingId,
        AdjunctPlotType type,
        BlockPos origin,
        int halfWidthX,
        int halfLengthZ,
        Direction facingFromParent,
        Rotation rotation) {

    /** Full footprint width in blocks (always odd: 2 × half + 1). */
    public int width()  { return 2 * halfWidthX + 1; }
    /** Full footprint length in blocks. */
    public int length() { return 2 * halfLengthZ + 1; }

    /** True iff {@code pos} is inside the plot's XZ footprint. */
    public boolean contains(BlockPos pos) {
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();
        return Math.abs(dx) <= halfWidthX && Math.abs(dz) <= halfLengthZ;
    }

    /** True iff this plot's footprint and {@code other}'s overlap
     *  in XZ (Y is ignored). Used by the placer's overlap probe
     *  and the decoration emitter's slot-skip pass. */
    public boolean overlaps(AdjunctPlot other) {
        if (other == null) return false;
        int dx = Math.abs(origin.getX() - other.origin.getX());
        int dz = Math.abs(origin.getZ() - other.origin.getZ());
        return dx <= halfWidthX + other.halfWidthX
                && dz <= halfLengthZ + other.halfLengthZ;
    }

    // ── Codec ────────────────────────────────────────────────────────────

    /** Doc 00 codec convention: enum fields via
     *  {@link StringRepresentable}-backed codec; rotation via the
     *  same {@code Codec.STRING.xmap} pattern Building uses. */
    public static final Codec<Rotation> ROTATION_CODEC =
            Codec.STRING.xmap(Rotation::valueOf, Rotation::name);

    public static final Codec<AdjunctPlot> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("plotId")
                            .forGetter(AdjunctPlot::plotId),
                    UUIDUtil.CODEC.fieldOf("parentBuildingId")
                            .forGetter(AdjunctPlot::parentBuildingId),
                    AdjunctPlotType.CODEC.fieldOf("type")
                            .forGetter(AdjunctPlot::type),
                    BlockPos.CODEC.fieldOf("origin")
                            .forGetter(AdjunctPlot::origin),
                    Codec.INT.fieldOf("halfWidthX")
                            .forGetter(AdjunctPlot::halfWidthX),
                    Codec.INT.fieldOf("halfLengthZ")
                            .forGetter(AdjunctPlot::halfLengthZ),
                    Direction.CODEC.fieldOf("facingFromParent")
                            .forGetter(AdjunctPlot::facingFromParent),
                    ROTATION_CODEC.fieldOf("rotation")
                            .forGetter(AdjunctPlot::rotation)
            ).apply(instance, AdjunctPlot::new));
}
