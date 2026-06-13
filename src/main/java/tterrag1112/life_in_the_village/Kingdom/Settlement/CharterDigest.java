package tterrag1112.life_in_the_village.Kingdom.Settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

/**
 * Track C1 — a thin stage-0 resource snapshot estimate for a
 * {@link SettlementCharter}, derived from the target {@link AtlasCell}
 * plus the charter's size band. Lets downstream kingdom systems
 * (economy, population, trade) operate on charters whether realized or
 * not — the design's "every downstream system operates on
 * charters+digests identically" rule.
 *
 * <p>Deliberately minimal in C1: the full sim-ledger digest is D2/D-track
 * work. Stage-2 realization overwrites estimates with truth.
 *
 * @param estPopulation rough headcount from the size band (mid-point of
 *                      the tier's building range, scaled).
 * @param homeCategory  the target cell's broad biome category.
 * @param relief        the target cell's {@code maxY - minY} (slope proxy).
 * @param freshwater    target cell has river/swamp freshwater access.
 * @param coastal       target cell is coast-adjacent (ocean).
 */
public record CharterDigest(
        int estPopulation,
        tterrag1112.life_in_the_village.World.Atlas.BiomeCategory homeCategory,
        int relief,
        boolean freshwater,
        boolean coastal
) {

    public static final Codec<CharterDigest> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("estPop", 0).forGetter(CharterDigest::estPopulation),
            tterrag1112.life_in_the_village.World.Atlas.BiomeCategory.CODEC
                    .optionalFieldOf("homeCat",
                            tterrag1112.life_in_the_village.World.Atlas.BiomeCategory.PLAINS)
                    .forGetter(CharterDigest::homeCategory),
            Codec.INT.optionalFieldOf("relief", 0).forGetter(CharterDigest::relief),
            Codec.BOOL.optionalFieldOf("fresh", false).forGetter(CharterDigest::freshwater),
            Codec.BOOL.optionalFieldOf("coastal", false).forGetter(CharterDigest::coastal)
    ).apply(i, CharterDigest::new));

    /** Neutral default for a charter whose target cell could not be read. */
    public static final CharterDigest UNKNOWN = new CharterDigest(
            0, tterrag1112.life_in_the_village.World.Atlas.BiomeCategory.PLAINS,
            0, false, false);

    /**
     * Builds a digest from a target atlas cell and a size band. The
     * population estimate is a crude midpoint of the tier's building
     * range times an occupancy factor; refined to truth at realization.
     */
    public static CharterDigest fromCell(AtlasCell cell, VillageSizeTier band) {
        if (cell == null) {
            return new CharterDigest(estPopFor(band),
                    tterrag1112.life_in_the_village.World.Atlas.BiomeCategory.PLAINS,
                    0, false, false);
        }
        return new CharterDigest(
                estPopFor(band),
                cell.category(),
                cell.slope(),
                cell.isFreshwater(),
                cell.isCoast());
    }

    private static int estPopFor(VillageSizeTier band) {
        if (band == null) band = VillageSizeTier.VILLAGE;
        int maxB = band.maxBuildings == Integer.MAX_VALUE ? 24 : band.maxBuildings;
        int midBuildings = (band.minBuildings + maxB) / 2;
        // ~2.5 inhabitants per building as a rough stage-0 estimate.
        return Math.max(1, (midBuildings * 5) / 2);
    }
}
