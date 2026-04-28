package tterrag1112.life_in_the_village.Village.Decoration.Plaza;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * Doc 04 §"Tier-based sizing" — HAMLETs don't get a plaza polygon.
 * Instead they register a single {@code VillageCenterMarker} at the
 * place a polygon would have gone, with just enough metadata for a
 * future "well + signpost" decoration to be placed.
 *
 * <p>HAMLETs and full plazas are queried separately from
 * {@link tterrag1112.life_in_the_village.Village.Planning.Primitives
 * .PlanContext}: tier-aware code asks "do you have a plaza? else,
 * a center marker?" — both can coexist on a village (e.g. a HAMLET
 * that grew into a TOWN keeps the marker until the polygon
 * generator runs at the new tier).</p>
 *
 * <p>Codec stable; persisted on Village alongside the plaza list.</p>
 */
public record VillageCenterMarker(
        BlockPos pos,
        int floorY,
        String culture
) {
    public VillageCenterMarker {
        if (pos == null) throw new IllegalArgumentException("pos");
        if (culture == null || culture.isBlank()) culture = "default";
    }

    public static final Codec<VillageCenterMarker> CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockPos.CODEC.fieldOf("pos")
                    .forGetter(VillageCenterMarker::pos),
            Codec.INT.fieldOf("floorY")
                    .forGetter(VillageCenterMarker::floorY),
            Codec.STRING.optionalFieldOf("culture", "default")
                    .forGetter(VillageCenterMarker::culture)
    ).apply(i, VillageCenterMarker::new));
}
