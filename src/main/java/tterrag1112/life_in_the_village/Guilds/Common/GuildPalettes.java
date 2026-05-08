package tterrag1112.life_in_the_village.Guilds.Common;

import net.minecraft.world.item.DyeColor;

import java.util.EnumMap;
import java.util.Map;

/**
 * Track B1 — per-{@link GuildType} default colour table. Single
 * source of truth for guild-hall identity tints; {@link AbstractGuild}
 * canonicalizes a null colour field to the matching entry here.
 *
 * <p>Mirrors the per-type-data pattern {@link GuildType} already uses
 * for {@code memberProfessions}: a single static EnumMap, declared
 * close to the type, no scattered hardcoded colours elsewhere in the
 * codebase. P0a-12 (forced colour overrides for guild halls) reads
 * from {@link AbstractGuild} which reads from this table; the
 * variant resolver receives the resolved palette.
 */
public final class GuildPalettes {

    private static final Map<GuildType, GuildPalette> DEFAULTS =
            new EnumMap<>(GuildType.class);

    static {
        // ADVENTURER — adventurer's hall: red and black, the classic
        // "tavern with quest board" identity.
        DEFAULTS.put(GuildType.ADVENTURER, new GuildPalette(
                DyeColor.RED, DyeColor.BLACK, DyeColor.GRAY));

        // CRAFTSMEN — anvil and forge: steel-grey primary, deep red
        // accent, near-black roof.
        DEFAULTS.put(GuildType.CRAFTSMEN, new GuildPalette(
                DyeColor.GRAY, DyeColor.RED, DyeColor.BLACK));

        // MERCHANTS — gold and blue: classic merchant heraldry.
        DEFAULTS.put(GuildType.MERCHANTS, new GuildPalette(
                DyeColor.BLUE, DyeColor.YELLOW, DyeColor.BROWN));

        // AGRICULTURAL — earth tones: brown primary, green accent,
        // straw-coloured roof.
        DEFAULTS.put(GuildType.AGRICULTURAL, new GuildPalette(
                DyeColor.BROWN, DyeColor.GREEN, DyeColor.YELLOW));

        // RELIGIOUS — white primary (sacred), purple accent, no roof
        // tint (classic stone temple roof reads neutral).
        DEFAULTS.put(GuildType.RELIGIOUS, new GuildPalette(
                DyeColor.WHITE, DyeColor.PURPLE, null));

        // SCHOLARLY — blue primary (academic), white accent, dark
        // roof.
        DEFAULTS.put(GuildType.SCHOLARLY, new GuildPalette(
                DyeColor.LIGHT_BLUE, DyeColor.WHITE, DyeColor.GRAY));
    }

    private GuildPalettes() {}

    /**
     * Returns the default palette for {@code type}. Always non-null:
     * a {@code GuildType} that lacks an entry returns
     * {@link GuildPalette#NONE} so callers don't have to guard.
     */
    public static GuildPalette forType(GuildType type) {
        if (type == null) return GuildPalette.NONE;
        return DEFAULTS.getOrDefault(type, GuildPalette.NONE);
    }
}
