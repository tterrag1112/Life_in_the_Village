package tterrag1112.life_in_the_village.Village.Roster;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Phase 6.3.3.d — one slot in a building roster. Either currently
 * realized as a world entity ({@link Realized}, carries the entity
 * UUID) or held abstractly ({@link Simulated}, no entity exists).
 *
 * <p>Adult flag follows the Minecraft baby/adult distinction —
 * sufficient resolution for animal husbandry. {@link #birthTick} marks
 * when the slot first existed; growth check (baby → adult) compares
 * against the roster definition's growthPeriodTicks.
 */
public sealed interface RosterSlot permits RosterSlot.Realized, RosterSlot.Simulated {

    boolean isAdult();
    long birthTick();
    String kind();

    /** Whether this slot currently has a world-realized entity. */
    default boolean isRealized() { return this instanceof Realized; }

    /** Returns the entity UUID if realized, else null. */
    default UUID entityUuidOrNull() {
        return this instanceof Realized r ? r.entityUuid() : null;
    }

    record Realized(UUID entityUuid, boolean isAdult, long birthTick)
            implements RosterSlot {
        public static final MapCodec<Realized> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("entityUuid").forGetter(Realized::entityUuid),
                Codec.BOOL.fieldOf("isAdult").forGetter(Realized::isAdult),
                Codec.LONG.fieldOf("birthTick").forGetter(Realized::birthTick)
        ).apply(i, Realized::new));
        @Override public String kind() { return "realized"; }
    }

    record Simulated(boolean isAdult, long birthTick) implements RosterSlot {
        public static final MapCodec<Simulated> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.BOOL.fieldOf("isAdult").forGetter(Simulated::isAdult),
                Codec.LONG.fieldOf("birthTick").forGetter(Simulated::birthTick)
        ).apply(i, Simulated::new));
        @Override public String kind() { return "simulated"; }
    }

    Codec<RosterSlot> CODEC = Codec.STRING.dispatch("kind",
            RosterSlot::kind,
            kind -> switch (kind) {
                case "realized"  -> Realized.CODEC;
                case "simulated" -> Simulated.CODEC;
                default -> throw new IllegalArgumentException(
                        "Unknown RosterSlot kind: " + kind);
            });
}
