// src/main/java/tterrag1112/life_in_the_village/World/Atlas/Regions/AtlasRegion.java
package tterrag1112.life_in_the_village.World.Atlas.Regions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.List;

/**
 * A contiguous cluster of atlas cells sharing a compatible biome category.
 *
 * <p>Regions are the coarse territorial units the kingdom claim system
 * reasons about. Oceans are tracked separately via {@link AtlasOcean}
 * because they're impassable and can be arbitrarily large.
 *
 * <p>Small patches (below {@link AtlasRegionIndex#MIN_REGION_SIZE}) are
 * absorbed into the dominant surrounding region during construction, so
 * you won't get a 3-cell desert "region" inside a jungle.
 */
public record AtlasRegion(
        int id,
        BiomeCategory category,
        long centroidKey,
        List<Long> cellKeys
) {
    public static final Codec<AtlasRegion> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(AtlasRegion::id),
            BiomeCategory.CODEC.fieldOf("category").forGetter(AtlasRegion::category),
            Codec.LONG.fieldOf("centroid").forGetter(AtlasRegion::centroidKey),
            Codec.LONG.listOf().fieldOf("cells").forGetter(AtlasRegion::cellKeys)
    ).apply(i, AtlasRegion::new));

    public int size() { return cellKeys.size(); }
}