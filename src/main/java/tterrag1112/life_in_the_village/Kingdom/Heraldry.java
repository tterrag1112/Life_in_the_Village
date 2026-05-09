package tterrag1112.life_in_the_village.Kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Locale;

/**
 * Track D1 (Phase 0) — minimal heraldry stub stored on each
 * {@link Kingdom}. Carries enough vocabulary to render a basic
 * banner / shield in a future phase but does not commit to a
 * full heraldic library.
 *
 * <h3>Vocabulary</h3>
 * <ul>
 *   <li>{@link Tincture} — seven traditional tinctures (the two
 *       "metals" and five "colours" of basic blazonry).</li>
 *   <li>{@link Charge} — ten common primary charges (a lion, an
 *       eagle, a sun, a tower, etc.).</li>
 *   <li>{@link Layout} — four partition layouts (plain, party
 *       per pale, party per fess, quartered).</li>
 * </ul>
 *
 * <p>{@link HeraldryGenerator} produces a deterministic instance
 * from a {@code (culture, kingdomId, foundingSeed)} triple so
 * the same kingdom always reloads to the same heraldry.
 *
 * <p>D1 stores the record on {@code Kingdom}; rendering, banners,
 * and texture generation are out of scope. D2 / D3 may add
 * supporters, ordinaries, and furs as new fields without
 * breaking save compat (codec uses {@code optionalFieldOf} with
 * defaults).
 */
public record Heraldry(
        Tincture field,
        Tincture chargeColour,
        Charge primaryCharge,
        Layout layout
) {

    public enum Tincture {
        OR,       // gold / yellow
        ARGENT,   // silver / white
        GULES,    // red
        AZURE,    // blue
        SABLE,    // black
        VERT,     // green
        PURPURE;  // purple

        public static final Codec<Tincture> CODEC =
                Codec.STRING.xmap(s -> Tincture.valueOf(s.toUpperCase(Locale.ROOT)),
                        Tincture::name);
    }

    public enum Charge {
        LION,
        EAGLE,
        CROSS,
        SUN,
        MOON,
        CROWN,
        SWORD,
        TOWER,
        FLEUR_DE_LIS,
        OAK;

        public static final Codec<Charge> CODEC =
                Codec.STRING.xmap(s -> Charge.valueOf(s.toUpperCase(Locale.ROOT)),
                        Charge::name);
    }

    public enum Layout {
        /** Single field, no partition. */
        PLAIN,
        /** Vertical split — left and right halves carry different tinctures. */
        PARTY_PER_PALE,
        /** Horizontal split — top and bottom halves carry different tinctures. */
        PARTY_PER_FESS,
        /** Four-quarter division. */
        QUARTERED;

        public static final Codec<Layout> CODEC =
                Codec.STRING.xmap(s -> Layout.valueOf(s.toUpperCase(Locale.ROOT)),
                        Layout::name);
    }

    /** Sentinel returned when a kingdom predates Phase 0 and hasn't been migrated yet. */
    public static final Heraldry UNKNOWN = new Heraldry(
            Tincture.ARGENT, Tincture.SABLE, Charge.CROSS, Layout.PLAIN);

    public static final Codec<Heraldry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Tincture.CODEC.optionalFieldOf("field", Tincture.ARGENT)
                    .forGetter(Heraldry::field),
            Tincture.CODEC.optionalFieldOf("chargeColour", Tincture.SABLE)
                    .forGetter(Heraldry::chargeColour),
            Charge.CODEC.optionalFieldOf("primaryCharge", Charge.CROSS)
                    .forGetter(Heraldry::primaryCharge),
            Layout.CODEC.optionalFieldOf("layout", Layout.PLAIN)
                    .forGetter(Heraldry::layout)
    ).apply(i, Heraldry::new));

    /** Human-readable summary; consumed by the debug command. */
    public String describe() {
        return layout.name() + " " + field.name() + " field, "
                + chargeColour.name() + " " + primaryCharge.name();
    }
}
