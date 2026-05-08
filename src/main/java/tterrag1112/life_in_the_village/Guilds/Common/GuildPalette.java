package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Track B1 — three-slot colour palette for a guild's identity.
 * Mirrors {@link tterrag1112.life_in_the_village.Village.Decoration.Variants.TintPass.Plan}
 * so a guild's palette can flow directly into the variant resolver's
 * forced-override mechanism.
 *
 * <p>Slots are nullable so partial palettes are valid (a guild might
 * declare a primary colour only and let the variant's natural
 * accent/roof sample as usual).
 */
public record GuildPalette(
        @Nullable DyeColor primaryColor,
        @Nullable DyeColor accentColor,
        @Nullable DyeColor roofColor
) {

    /** Empty palette — equivalent to "no forced colours." */
    public static final GuildPalette NONE = new GuildPalette(null, null, null);

    public static GuildPalette of(DyeColor primary, DyeColor accent, DyeColor roof) {
        return new GuildPalette(primary, accent, roof);
    }

    public boolean isEmpty() {
        return primaryColor == null && accentColor == null && roofColor == null;
    }

    public static final Codec<GuildPalette> CODEC = RecordCodecBuilder.create(i -> i.group(
            dyeCodec().optionalFieldOf("primaryColor")
                    .forGetter(g -> Optional.ofNullable(g.primaryColor)),
            dyeCodec().optionalFieldOf("accentColor")
                    .forGetter(g -> Optional.ofNullable(g.accentColor)),
            dyeCodec().optionalFieldOf("roofColor")
                    .forGetter(g -> Optional.ofNullable(g.roofColor))
    ).apply(i, (primary, accent, roof) -> new GuildPalette(
            primary.orElse(null),
            accent.orElse(null),
            roof.orElse(null))));

    private static Codec<DyeColor> dyeCodec() {
        return Codec.STRING.xmap(
                s -> DyeColor.valueOf(s.toUpperCase(Locale.ROOT)),
                d -> d.getName());
    }
}
