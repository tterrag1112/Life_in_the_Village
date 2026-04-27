package tterrag1112.life_in_the_village.Npc.Visitor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Phase 4 doc 29 line 52 — one stop on a visitor's plan. The
 * {@link VisitorState} field is a list of these; the
 * {@link VisitorGoal} walks them in order.
 *
 * @param buildingId            target building UUID. The goal looks
 *                              up the building's origin via
 *                              {@code Building.getShape().getOrigin()}
 *                              for the walk target.
 * @param activity              what to do once arrived
 * @param expectedDurationTicks how long to dwell — keeps the
 *                              visitor anchored to the spot until
 *                              the timer expires.
 */
public record VisitorItinerary(
        UUID buildingId,
        Activity activity,
        int expectedDurationTicks
) {
    public VisitorItinerary {
        if (buildingId == null) throw new IllegalArgumentException("buildingId required");
        if (activity == null) throw new IllegalArgumentException("activity required");
        if (expectedDurationTicks < 0) expectedDurationTicks = 0;
    }

    public static final Codec<VisitorItinerary> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("buildingId").forGetter(VisitorItinerary::buildingId),
            Activity.CODEC.fieldOf("activity").forGetter(VisitorItinerary::activity),
            Codec.INT.optionalFieldOf("expectedDurationTicks", 1200)
                    .forGetter(VisitorItinerary::expectedDurationTicks)
    ).apply(i, VisitorItinerary::new));
}
