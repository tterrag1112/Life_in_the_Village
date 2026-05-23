package tterrag1112.life_in_the_village.Village.Farms.Complex;

import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import tterrag1112.life_in_the_village.Village.Buildings.Complex.BiomeTag;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detour A — maps a Minecraft {@code Holder<Biome>} to zero or
 * more {@link BiomeTag}s used by
 * {@link tterrag1112.life_in_the_village.Village.Buildings.Complex.BuildingComplexSpec#blockedBiomes()}.
 *
 * <p>This is the harness/algorithm seam: the spec stays free of
 * {@code net.minecraft} types (so it remains JSON-friendly later),
 * the mapping lives here where biome queries belong.
 *
 * <p>{@link #tagsFor(Holder)} returns the union of tags the biome
 * matches. Multi-tag possible (a deep-ocean biome is both OCEAN
 * and UNINHABITABLE-adjacent, but for current spec semantics it's
 * sufficient that ANY tag intersects the spec's blocked set).
 */
public final class BiomeTagging {

    private BiomeTagging() {}

    /** Returns the {@link BiomeTag}s applicable to {@code biome}.
     *  Never null; empty when no tag fits (a temperate plains
     *  biome maps to no tag at all — that's the desired "allow"
     *  outcome). */
    public static Set<BiomeTag> tagsFor(Holder<Biome> biome) {
        EnumSet<BiomeTag> tags = EnumSet.noneOf(BiomeTag.class);
        if (biome == null) {
            tags.add(BiomeTag.UNINHABITABLE);
            return tags;
        }
        // Conservative tag matching using only well-established
        // 1.21 BiomeTags. DESERT detection is intentionally absent
        // here — sand-dominant biomes are knocked out upstream by
        // the arable-score filter (sand fails the
        // BlockCategory==OPEN/SHORE gate inside V2FeatureMap), so a
        // redundant biome-veto isn't required for spawn correctness.
        // Add a DESERT branch here only when a smoke test surfaces
        // a sand biome that slips past arable scoring.
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)) {
            tags.add(BiomeTag.OCEAN);
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            tags.add(BiomeTag.DESERT);
        }
        if (biome.is(BiomeTags.IS_MUSHROOM)) {
            tags.add(BiomeTag.MUSHROOM);
        }
        if (biome.is(BiomeTags.IS_NETHER)) {
            tags.add(BiomeTag.NETHER);
        }
        if (biome.is(BiomeTags.IS_END)) {
            tags.add(BiomeTag.END);
        }
        return tags;
    }

    /** True iff {@code biome} carries any tag in {@code blocked}.
     *  Convenience for the flood-fill admissibility check. */
    public static boolean isBlocked(Holder<Biome> biome, Set<BiomeTag> blocked) {
        if (blocked == null || blocked.isEmpty()) return false;
        Set<BiomeTag> tags = tagsFor(biome);
        for (BiomeTag t : tags) {
            if (blocked.contains(t)) return true;
        }
        return false;
    }
}
