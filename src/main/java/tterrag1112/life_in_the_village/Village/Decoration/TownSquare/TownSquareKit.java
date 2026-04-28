package tterrag1112.life_in_the_village.Village.Decoration.TownSquare;

import net.minecraft.resources.Identifier;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;

import java.util.List;
import java.util.Optional;

/**
 * Doc 04 §"Data structures" — the bundle of NBT pieces that compose
 * a town square at a given (culture, tier). Each field is the
 * decoration-framework {@code pieceId} (without the leading
 * {@code structures/} or trailing {@code .nbt}) for the matching
 * {@link tterrag1112.life_in_the_village.Village.Decoration.Framework
 * .DecorationProfile}.
 *
 * <p>Doc 04's {@code pavingNbtOrNull} field is intentionally absent —
 * paving is biome-and-culture-styled programmatic placement done by
 * {@code TownSquareComposer}, not an NBT. Authoring 4 tiers × N
 * cultures × M biomes of paving NBTs would be unwieldy compared to
 * the existing {@code VillageBiomeStyle} approach. Documented in
 * doc 04 revision notes.</p>
 */
public record TownSquareKit(
        String culture,
        VillageSizeTier tier,
        Identifier centralFeatureNbt,
        List<Identifier> benchVariants,
        Optional<Identifier> gazeboNbt,
        Optional<Identifier> monumentNbt,
        Identifier noticeBoardNbt,
        Identifier lampNbt,
        Optional<Identifier> flowerbedNbt
) {
    public TownSquareKit {
        if (culture == null || culture.isBlank()) {
            throw new IllegalArgumentException(
                    "TownSquareKit.culture must be non-blank (use \"default\")");
        }
        if (tier == null) {
            throw new IllegalArgumentException("TownSquareKit.tier must be non-null");
        }
        if (centralFeatureNbt == null) {
            throw new IllegalArgumentException(
                    "TownSquareKit.centralFeatureNbt must be non-null");
        }
        if (noticeBoardNbt == null) {
            throw new IllegalArgumentException(
                    "TownSquareKit.noticeBoardNbt must be non-null");
        }
        if (lampNbt == null) {
            throw new IllegalArgumentException(
                    "TownSquareKit.lampNbt must be non-null");
        }
        benchVariants = benchVariants == null ? List.of() : List.copyOf(benchVariants);
        gazeboNbt = gazeboNbt == null ? Optional.empty() : gazeboNbt;
        monumentNbt = monumentNbt == null ? Optional.empty() : monumentNbt;
        flowerbedNbt = flowerbedNbt == null ? Optional.empty() : flowerbedNbt;
    }
}
