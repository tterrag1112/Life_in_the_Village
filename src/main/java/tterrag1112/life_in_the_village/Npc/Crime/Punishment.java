package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * What a guilty verdict produces. Spec line 96.
 *
 * @param type             discriminator (FINE / DETENTION / etc.)
 * @param bronzeAmount     used by FINE / RESTITUTION; 0 otherwise
 * @param durationTicks    DETENTION + COMMUNITY_LABOR + OFFICE_BAR
 *                         duration; 0 for one-shot punishments
 * @param confiscatedItem  optional UUID of an
 *                         {@link tterrag1112.life_in_the_village.Items.ConfiscatedStack}-style
 *                         tracking record. v1 doesn't yet ship a real
 *                         tracker; the field is reserved so Phase 4 can
 *                         populate it without changing the codec
 * @param details          free-form description for log / UI
 */
public record Punishment(
        PunishmentType type,
        long bronzeAmount,
        long durationTicks,
        Optional<UUID> confiscatedItem,
        String details
) {
    public Punishment {
        if (type == null) throw new IllegalArgumentException("type required");
        if (bronzeAmount < 0) bronzeAmount = 0;
        if (durationTicks < 0) durationTicks = 0;
        if (confiscatedItem == null) confiscatedItem = Optional.empty();
        if (details == null) details = "";
    }

    public static Punishment of(PunishmentType type) {
        return new Punishment(type, 0L, 0L, Optional.empty(), "");
    }

    public static Punishment fine(long bronze) {
        return new Punishment(PunishmentType.FINE, bronze, 0L, Optional.empty(),
                bronze + " bronze");
    }

    public static Punishment restitution(long bronze) {
        return new Punishment(PunishmentType.RESTITUTION, bronze, 0L, Optional.empty(),
                "restitution of " + bronze + " bronze");
    }

    public static Punishment detention(long days) {
        return new Punishment(PunishmentType.DETENTION, 0L,
                days * 24000L, Optional.empty(), days + " days detention");
    }

    public static Punishment communityLabor(long days) {
        return new Punishment(PunishmentType.COMMUNITY_LABOR, 0L,
                days * 24000L, Optional.empty(), days + " days community labor");
    }

    public static Punishment officeBar(long days) {
        return new Punishment(PunishmentType.OFFICE_BAR, 0L,
                days * 24000L, Optional.empty(), days + " days office-bar");
    }

    public static final Codec<Punishment> CODEC = RecordCodecBuilder.create(i -> i.group(
            PunishmentType.CODEC.fieldOf("type").forGetter(Punishment::type),
            Codec.LONG.optionalFieldOf("bronze", 0L).forGetter(Punishment::bronzeAmount),
            Codec.LONG.optionalFieldOf("durationTicks", 0L).forGetter(Punishment::durationTicks),
            UUIDUtil.CODEC.optionalFieldOf("confiscatedItem").forGetter(Punishment::confiscatedItem),
            Codec.STRING.optionalFieldOf("details", "").forGetter(Punishment::details)
    ).apply(i, Punishment::new));
}
