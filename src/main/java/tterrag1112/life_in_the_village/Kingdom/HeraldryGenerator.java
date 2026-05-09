package tterrag1112.life_in_the_village.Kingdom;

import tterrag1112.life_in_the_village.Cultures.Culture;

import java.util.UUID;

/**
 * Track D1 (Phase 0) — deterministic generator for a
 * {@link Heraldry} instance from a {@code (culture, kingdomId,
 * foundingSeed)} triple.
 *
 * <h3>Determinism</h3>
 * The same input produces the same output across every load and
 * every server restart. Kingdom records persist the resulting
 * heraldry directly (codec on {@link Kingdom}), so the generator
 * is only consulted at kingdom-founding and during the membership
 * migration's heraldry back-fill for pre-Phase-0 kingdoms.
 *
 * <h3>Hashing</h3>
 * A simple xorshift over the concatenated input bits picks four
 * indices into the {@link Heraldry.Tincture}, {@link Heraldry.Charge},
 * and {@link Heraldry.Layout} value lists. Field and charge
 * tinctures are constrained to be different so a "GULES on GULES"
 * blazon never appears.
 *
 * <h3>Culture influence</h3>
 * Culture id seeds the hash but does not bias the output today —
 * D3 is the natural place to add culture-specific charge weights
 * (e.g. highmarch favours LION + SWORD; silkwood favours OAK +
 * MOON; tidereach favours SUN + FLEUR_DE_LIS).
 */
public final class HeraldryGenerator {

    private HeraldryGenerator() {}

    /**
     * Produces a deterministic {@link Heraldry} for the given
     * inputs. {@code foundingSeed} is typically the kingdom's
     * founding tick or a related stable scalar; pass 0L for
     * back-filled pre-Phase-0 kingdoms.
     */
    public static Heraldry generate(Culture culture, UUID kingdomId, long foundingSeed) {
        long mix = mix(
                kingdomId.getMostSignificantBits(),
                kingdomId.getLeastSignificantBits(),
                foundingSeed,
                cultureSeed(culture));

        Heraldry.Tincture[] tinctures = Heraldry.Tincture.values();
        Heraldry.Charge[]   charges   = Heraldry.Charge.values();
        Heraldry.Layout[]   layouts   = Heraldry.Layout.values();

        Heraldry.Tincture field        = tinctures[(int) Math.floorMod(mix, tinctures.length)];
        mix = step(mix);
        Heraldry.Tincture chargeColour = tinctures[(int) Math.floorMod(mix, tinctures.length)];
        // Avoid same-tincture-on-same-tincture results.
        if (chargeColour == field) {
            chargeColour = tinctures[(int) Math.floorMod(mix + 3, tinctures.length)];
            if (chargeColour == field) {
                // Two fixed-point values sharing the same bucket — pick
                // a deterministic alternate by walking forward.
                int idx = (field.ordinal() + 1) % tinctures.length;
                chargeColour = tinctures[idx];
            }
        }
        mix = step(mix);
        Heraldry.Charge charge = charges[(int) Math.floorMod(mix, charges.length)];
        mix = step(mix);
        Heraldry.Layout layout = layouts[(int) Math.floorMod(mix, layouts.length)];

        return new Heraldry(field, chargeColour, charge, layout);
    }

    // =========================================================================
    // Hash helpers — xorshift64 mixer
    // =========================================================================

    private static long mix(long a, long b, long c, long d) {
        long x = a;
        x = step(x ^ b);
        x = step(x ^ c);
        x = step(x ^ d);
        return x;
    }

    private static long step(long x) {
        // xorshift64 step.
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    private static long cultureSeed(Culture c) {
        return c == null || c.id() == null ? 0L : c.id().hashCode();
    }

    // =========================================================================
    // Track D3.2a — per-house heraldry
    // =========================================================================

    /**
     * Track D3.2a — produces deterministic {@link Heraldry} for a noble
     * dynasty seeded from {@code (houseId, founderUuid)}. House heraldry
     * is independent of culture seed so a single kingdom can carry
     * visually distinct house arms even when all houses share its
     * culture; it remains stable across reloads because the inputs are.
     *
     * <p>Same constraint as {@link #generate}: field and charge tinctures
     * are forced to differ.
     */
    public static Heraldry forHouse(java.util.UUID houseId, java.util.UUID founderUuid) {
        long mix = mix(
                houseId.getMostSignificantBits(),
                houseId.getLeastSignificantBits(),
                founderUuid == null ? 0L : founderUuid.getMostSignificantBits(),
                founderUuid == null ? 0L : founderUuid.getLeastSignificantBits());

        Heraldry.Tincture[] tinctures = Heraldry.Tincture.values();
        Heraldry.Charge[]   charges   = Heraldry.Charge.values();
        Heraldry.Layout[]   layouts   = Heraldry.Layout.values();

        Heraldry.Tincture field        = tinctures[(int) Math.floorMod(mix, tinctures.length)];
        mix = step(mix);
        Heraldry.Tincture chargeColour = tinctures[(int) Math.floorMod(mix, tinctures.length)];
        if (chargeColour == field) {
            int idx = (field.ordinal() + 1) % tinctures.length;
            chargeColour = tinctures[idx];
        }
        mix = step(mix);
        Heraldry.Charge charge = charges[(int) Math.floorMod(mix, charges.length)];
        mix = step(mix);
        Heraldry.Layout layout = layouts[(int) Math.floorMod(mix, layouts.length)];

        return new Heraldry(field, chargeColour, charge, layout);
    }
}
