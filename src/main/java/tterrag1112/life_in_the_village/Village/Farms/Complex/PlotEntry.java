package tterrag1112.life_in_the_village.Village.Farms.Complex;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Detour A — persisted per-plot connection point within a
 * {@link FarmComplex}'s path graph.
 *
 * <p>Where the spine branches to reach the plot's interior. Two
 * positions:
 * <ul>
 *   <li>{@link #spineAttach} — the point on the spine where the
 *       branch tees off.</li>
 *   <li>{@link #entry} — the point inside the plot where the
 *       branch terminates (typically the plot centroid).</li>
 * </ul>
 *
 * <p>The renderer places a gate / opening in the plot's border
 * fence at the boundary the branch crosses between
 * {@code spineAttach} and {@code entry}; that intersection point
 * is computed render-side, not stored here.
 *
 * @param plotId       the plot this entry belongs to (cross-ref
 *                     to a {@code FarmPlot.id}).
 * @param entry        terminal point of the branch (inside plot).
 * @param spineAttach  point on the spine where the branch starts.
 */
public record PlotEntry(UUID plotId, BlockPos entry, BlockPos spineAttach) {

    private static final Codec<UUID> UUID_STR =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<PlotEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_STR.fieldOf("plotId").forGetter(PlotEntry::plotId),
            BlockPos.CODEC.fieldOf("entry").forGetter(PlotEntry::entry),
            BlockPos.CODEC.fieldOf("spineAttach").forGetter(PlotEntry::spineAttach)
    ).apply(i, PlotEntry::new));
}
