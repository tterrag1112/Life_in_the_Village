package tterrag1112.life_in_the_village.Village.Buildings.Complex;

/**
 * Detour A — coarse biome categories used by
 * {@link BuildingComplexSpec#blockedBiomes()} as a coarse veto
 * against spawning a complex in unsuitable biomes.
 *
 * <p>This is a thin convenience layer over Minecraft's biome
 * registry. The mapping from a {@code Holder<Biome>} to this enum
 * lives in the algorithm package (Stage 2) so the spec stays free
 * of {@code net.minecraft} types. Add new tags as new complex
 * types declare new vetoes.
 */
public enum BiomeTag {
    OCEAN,
    DESERT,
    MUSHROOM,
    NETHER,
    END,
    /** Catch-all for biomes that aren't otherwise tagged but are
     *  obviously unsuitable (e.g. void). */
    UNINHABITABLE
}
