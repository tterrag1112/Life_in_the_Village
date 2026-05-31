package tterrag1112.life_in_the_village.Village.Planning;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;
import tterrag1112.life_in_the_village.Village.Decoration.Plaza.PlazaRegion;

import java.util.List;
import java.util.Optional;

/**
 * Phase 18: polygon-aware plaza façade.
 *
 * <p>Single-owner wrapper around {@link PlazaRegion} (the polygon-based
 * plaza model) plus the legacy {@code townSquarePos} / {@code townSquareRadius}
 * / {@code civicRingRadius} fields. Recipes register exactly one {@code Plaza}
 * per {@code installPlaza} call.
 *
 * <p>Why a façade instead of pushing more state onto {@link PlazaRegion}:
 * {@link PlazaRegion} is persisted (it has a Codec); this wrapper carries the
 * non-persisted legacy scalar fields alongside it without expanding the
 * persistence surface.
 *
 * <h3>Mutability</h3>
 * Geometry fields ({@link #region}, {@link #townSquarePos}, etc.) are
 * effectively final (the surrounding immutability story matches a record).
 *
 * <h3>HAMLET tier</h3>
 * HAMLETs don't have a {@link PlazaRegion} (no polygon — just a
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Plaza
 * .VillageCenterMarker}). Their {@code Plaza} has {@code region == null}.
 * The legacy field values ({@code townSquarePos = snapped},
 * {@code townSquareRadius = 3}, {@code civicRingRadius = 11}) are populated
 * so downstream consumers read sane values.
 */
public final class Plaza {

    @Nullable private final PlazaRegion region;
    private final BlockPos townSquarePos;
    private final int townSquareRadius;
    private final int civicRingRadius;

    public Plaza(@Nullable PlazaRegion region,
                 BlockPos townSquarePos,
                 int townSquareRadius,
                 int civicRingRadius) {
        this.region            = region;
        this.townSquarePos     = townSquarePos;
        this.townSquareRadius  = townSquareRadius;
        this.civicRingRadius   = civicRingRadius;
    }

    @Nullable public PlazaRegion region() { return region; }
    public BlockPos townSquarePos()       { return townSquarePos; }
    public int      townSquareRadius()    { return townSquareRadius; }
    public int      civicRingRadius()     { return civicRingRadius; }

    /** Convenience alias matching the Phase 18 prompt's vocabulary. */
    public BlockPos centre() { return townSquarePos; }

    /**
     * Returns a new {@link Plaza} with the civic ring radius overridden.
     */
    public Plaza withCivicRingRadius(int newRadius) {
        return new Plaza(region, townSquarePos, townSquareRadius, newRadius);
    }

    // =========================================================================
    // Phase 20a CODEC
    // =========================================================================
    public static final Codec<Plaza> CODEC = RecordCodecBuilder.create(i -> i.group(
            PlazaRegion.CODEC.optionalFieldOf("region")
                    .forGetter(p -> Optional.ofNullable(p.region())),
            BlockPos.CODEC.fieldOf("townSquarePos").forGetter(Plaza::townSquarePos),
            Codec.INT.fieldOf("townSquareRadius").forGetter(Plaza::townSquareRadius),
            Codec.INT.fieldOf("civicRingRadius").forGetter(Plaza::civicRingRadius)
    ).apply(i, (region, pos, sqR, civR) ->
            new Plaza(region.orElse(null), pos, sqR, civR)));
}
