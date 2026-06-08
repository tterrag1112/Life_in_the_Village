package tterrag1112.life_in_the_village.Village.Graveyard;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Religion Rework R5a — one grave in a village {@link Graveyard}: who lies in it,
 * when they died, the grave slot it occupies, and an optional epitaph. A pure
 * data record (the slot {@link BlockPos} is the navigation target for R5b's
 * {@code visit_grave}); any in-world headstone marker is cosmetic + best-effort.
 */
public record Grave(UUID deceasedId, String name, long deathTick, BlockPos slot, String epitaph) {

    public Grave {
        if (name == null) name = "Unknown";
        if (epitaph == null) epitaph = "";
        if (slot == null) slot = BlockPos.ZERO;
    }

    public static final Codec<Grave> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("deceasedId").forGetter(Grave::deceasedId),
            Codec.STRING.fieldOf("name").forGetter(Grave::name),
            Codec.LONG.fieldOf("deathTick").forGetter(Grave::deathTick),
            BlockPos.CODEC.fieldOf("slot").forGetter(Grave::slot),
            Codec.STRING.optionalFieldOf("epitaph", "").forGetter(Grave::epitaph)
    ).apply(i, Grave::new));
}
