package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Utilities.Geometry.Polygon;
import tterrag1112.life_in_the_village.Village.Planning.V2.Layer4.RoadFormality;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Doc 04 §"Core concepts" — a plaza's persisted geometry. The
 * polygon-based plaza model treats a plaza as a road-network region
 * whose interior the road realiser fills with paving palette,
 * matching the surrounding road palette so the plaza reads as
 * continuous with the road network rather than as a stamped
 * structure.
 *
 * <p>Prompt 16 ships this record as scaffolding — nothing populates
 * it yet. Prompt 17 implements the polygon generator and registers
 * regions on {@code PlanContext} / {@code Village}; prompt 18 wires
 * civic-building placement to PLAZA_ADJACENT slots derived from the
 * polygon edges.</p>
 *
 * @param plazaId            stable UUID for cross-system reference
 * @param purpose            CIVIC / MARKET / RELIGIOUS_COURTYARD —
 *                           drives content (e.g. MARKET reserves a
 *                           vendor sub-region)
 * @param shape              CIRCLE / SQUARE / LINEAR / IRREGULAR —
 *                           layout-derived; debug + downstream code
 *                           may special-case
 * @param footprint          the plaza outline in the XZ plane
 * @param centroid           geometric center (snapped Y matches
 *                           {@link #floorY})
 * @param floorY             one Y for the whole plaza floor
 * @param connectedRoadIds   road segment IDs whose endpoints fall
 *                           inside the polygon — the road realiser
 *                           treats those segments as plaza-internal
 *                           in prompt 17/18
 * @param orientationRadians 0 for cardinal-aligned shapes,
 *                           &ne;0 for rotated (e.g. 45°-square,
 *                           ridge-aligned LINEAR plaza)
 * @param formality          plan-time {@link RoadFormality} at the
 *                           plaza centroid (the density-profile zone,
 *                           same machinery as street paint) — the
 *                           paver keys its treatment on this so the
 *                           plaza matches the surrounding streets.
 *                           ORGANIC for pre-feature saves.
 */
public record PlazaRegion(
        UUID plazaId,
        PlazaPurpose purpose,
        PlazaShape shape,
        Polygon footprint,
        BlockPos centroid,
        int floorY,
        Set<UUID> connectedRoadIds,
        float orientationRadians,
        RoadFormality formality
) {
    public PlazaRegion {
        if (plazaId == null) throw new IllegalArgumentException("plazaId");
        if (purpose == null) throw new IllegalArgumentException("purpose");
        if (shape == null)   throw new IllegalArgumentException("shape");
        if (footprint == null) throw new IllegalArgumentException("footprint");
        if (centroid == null)  throw new IllegalArgumentException("centroid");
        connectedRoadIds = connectedRoadIds == null
                ? Set.of() : Set.copyOf(connectedRoadIds);
        formality = formality == null ? RoadFormality.ORGANIC : formality;
    }

    public static final Codec<PlazaRegion> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("plazaId")
                    .forGetter(PlazaRegion::plazaId),
            PlazaPurpose.CODEC.fieldOf("purpose")
                    .forGetter(PlazaRegion::purpose),
            PlazaShape.CODEC.fieldOf("shape")
                    .forGetter(PlazaRegion::shape),
            Polygon.CODEC.fieldOf("footprint")
                    .forGetter(PlazaRegion::footprint),
            BlockPos.CODEC.fieldOf("centroid")
                    .forGetter(PlazaRegion::centroid),
            Codec.INT.fieldOf("floorY")
                    .forGetter(PlazaRegion::floorY),
            UUIDUtil.CODEC.listOf().optionalFieldOf("connectedRoadIds", List.of())
                    .forGetter(p -> List.copyOf(p.connectedRoadIds)),
            Codec.FLOAT.optionalFieldOf("orientationRadians", 0f)
                    .forGetter(PlazaRegion::orientationRadians),
            RoadFormality.CODEC.optionalFieldOf("formality", RoadFormality.ORGANIC)
                    .forGetter(PlazaRegion::formality)
    ).apply(i, (id, pu, sh, ft, c, y, ids, o, f) -> new PlazaRegion(
            id, pu, sh, ft, c, y,
            ids.stream().collect(Collectors.toUnmodifiableSet()),
            o, f)));
}
