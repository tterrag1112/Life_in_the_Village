package tterrag1112.life_in_the_village.World.Atlas;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;

import com.mojang.serialization.Codec;

/**
 * Broad biome category used by the World Atlas for fast spatial queries.
 *
 * <p>Each Minecraft biome maps to exactly one of these categories via
 * {@link #fromBiomeKey(String)}, which inspects the biome resource path.
 * The mapping is intentionally string-based so it works with vanilla,
 * modded, and worldgen-replacement biomes without needing per-mod patches.
 *
 * <p>Categories drive: village type weighting, culture selection,
 * trade route cost grids, building adjacency rules (river/coast),
 * and shape profile constraints.
 */
public enum BiomeCategory implements StringRepresentable {
    PLAINS,      // plains, meadow, sunflower plains, steppe
    FOREST,      // forest, birch, dark forest, taiga, old growth
    JUNGLE,      // jungle, bamboo, sparse jungle
    DESERT,      // desert, badlands, eroded badlands
    SAVANNA,     // savanna, savanna plateau, windswept savanna
    SWAMP,       // swamp, mangrove swamp
    SNOWY,       // snowy plains, snowy taiga, ice spikes, frozen peaks
    MOUNTAIN,    // jagged peaks, stony peaks, windswept hills
    MUSHROOM,    // mushroom fields
    BEACH,       // beach, snowy beach, stony shore
    RIVER,       // river, frozen river
    OCEAN,       // any ocean variant, deep ocean
    VOID,        // the_void or unknown
    OTHER;       // unrecognized — falls back to PLAINS-like behavior

    public static final Codec<BiomeCategory> CODEC =
            StringRepresentable.fromEnum(BiomeCategory::values);

    @Override public String getSerializedName() { return name(); }

    /**
     * Classifies a biome by its resource location path.
     * Uses substring matching against well-known biome name fragments.
     * Order matters — more specific categories come first.
     */
    public static BiomeCategory fromBiomeKey(String fullKey) {
        if (fullKey == null || fullKey.isEmpty()) return OTHER;
        // Use just the path portion if a namespace is present
        int colon = fullKey.indexOf(':');
        String path = colon >= 0 ? fullKey.substring(colon + 1) : fullKey;

        if (path.contains("void"))                              return VOID;
        if (path.contains("ocean"))                             return OCEAN;
        if (path.contains("river"))                             return RIVER;
        if (path.contains("beach") || path.contains("shore"))   return BEACH;
        if (path.contains("mushroom"))                          return MUSHROOM;

        if (path.contains("jagged") || path.contains("stony_peaks")
                || path.contains("frozen_peaks"))               return MOUNTAIN;
        if (path.contains("windswept") && !path.contains("savanna")) return MOUNTAIN;

        if (path.contains("snowy") || path.contains("frozen")
                || path.contains("ice"))                        return SNOWY;

        if (path.contains("swamp"))                             return SWAMP;
        if (path.contains("jungle") || path.contains("bamboo")) return JUNGLE;
        if (path.contains("savanna"))                           return SAVANNA;
        if (path.contains("desert") || path.contains("badlands")) return DESERT;

        if (path.contains("forest") || path.contains("taiga")
                || path.contains("grove") || path.contains("old_growth"))
            return FOREST;

        if (path.contains("plains") || path.contains("meadow")
                || path.contains("steppe") || path.contains("cherry"))
            return PLAINS;

        return OTHER;
    }

    public static BiomeCategory fromHolder(Holder<Biome> holder) {
        return holder.unwrapKey()
                .map(ResourceKey::identifier)
                .map(Object::toString)
                .map(BiomeCategory::fromBiomeKey)
                .orElse(OTHER);
    }

    /** Cells of this category cannot host buildings (water/void). */
    public boolean isUnbuildable() {
        return this == OCEAN || this == VOID;
    }

    /** Cells of this category have flowing freshwater. */
    public boolean isFreshwater() {
        return this == RIVER || this == SWAMP;
    }
}