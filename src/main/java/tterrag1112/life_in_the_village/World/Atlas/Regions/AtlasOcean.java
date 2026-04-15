// src/main/java/tterrag1112/life_in_the_village/World/Atlas/Regions/AtlasOcean.java
package tterrag1112.life_in_the_village.World.Atlas.Regions;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * A contiguous body of OCEAN cells. Tracked separately from regions
 * because oceans are impassable to kingdom claims and often span
 * much larger areas than a single region should.
 */
public record AtlasOcean(
        int id,
        long centroidKey,
        List<Long> cellKeys
) {
    public static final Codec<AtlasOcean> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(AtlasOcean::id),
            Codec.LONG.fieldOf("centroid").forGetter(AtlasOcean::centroidKey),
            Codec.LONG.listOf().fieldOf("cells").forGetter(AtlasOcean::cellKeys)
    ).apply(i, AtlasOcean::new));

    public int size() { return cellKeys.size(); }
}