package tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen;

import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Curated pool of Old Realm road names for Phase 7b named-road selection.
 *
 * <p>Names are drawn from four registers: Latin/imperial, Anglic archaic, poetic,
 * and geographic/directional. Scarcity is by design — roughly one named road per
 * 8 000 × 8 000 block region, so a ~40 000 × 40 000 world produces ~25 named
 * roads at most, well within the ~30-name pool.
 */
public final class OldRealmNamePool {

    private OldRealmNamePool() {}

    /** Latin/imperial flavor. */
    private static final List<String> VIA_NAMES = List.of(
            "Via Antiqua", "Via Imperialis", "Via Aurelia", "Via Orientalis",
            "Via Occidentalis", "Via Septentrion", "Via Caelestis"
    );

    /** Anglic archaic flavor. */
    private static final List<String> OLD_WAYS = List.of(
            "Old Caelin Road", "Old Myrren Way", "Old Thorin Road",
            "Old Silverway", "Old Mirewalk", "Old Stonemarch"
    );

    /** Descriptive/poetic. */
    private static final List<String> POETIC_WAYS = List.of(
            "The Hammerway", "The Sunset March", "The King's Way",
            "The Pilgrim's Road", "The Salt Road", "The Long Winter Road",
            "The Mourner's Road", "The Broken March"
    );

    /** Geographic/directional. */
    private static final List<String> GEOGRAPHIC_WAYS = List.of(
            "The Ironmount Road", "The Coldcreek Way", "The Blackriver March",
            "The Highpass Road", "The Riverwatch Way", "The Farwind Road",
            "The Sentinel Road", "The Twinforks Way"
    );

    /** Returns the full pool in a stable order (used for shuffling). */
    public static List<String> allNames() {
        List<String> all = new ArrayList<>();
        all.addAll(VIA_NAMES);
        all.addAll(OLD_WAYS);
        all.addAll(POETIC_WAYS);
        all.addAll(GEOGRAPHIC_WAYS);
        return all;
    }

    /**
     * Selects a name for a great road deterministically.
     *
     * <p>The name is seeded from the anchor positions and world seed so the same
     * road always receives the same candidate order. The first name not in
     * {@code usedNames} is returned. If the entire pool is exhausted (unlikely —
     * ~30 names for ~25 roads), a fallback name is generated from the geographic
     * fragments.
     *
     * @param anchorA   one endpoint anchor position
     * @param anchorB   other endpoint anchor position
     * @param worldSeed world level seed
     * @param usedNames names already assigned to other roads (mutated on return)
     * @return the selected name (never null)
     */
    public static String selectName(BlockPos anchorA, BlockPos anchorB,
                                    long worldSeed, Set<String> usedNames) {
        long seed = stableSeed(anchorA, anchorB, worldSeed);
        Random rng = new Random(seed);

        List<String> pool = allNames();
        Collections.shuffle(pool, rng);

        for (String name : pool) {
            if (!usedNames.contains(name)) {
                usedNames.add(name);
                return name;
            }
        }

        // Fallback: generate a unique ordinal name
        int index = usedNames.size() - pool.size() + 1;
        String fallback = "The Great Road of the Old Realm (No. " + index + ")";
        usedNames.add(fallback);
        return fallback;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Order-independent stable hash combining both anchor positions and world seed. */
    static long stableSeed(BlockPos a, BlockPos b, long worldSeed) {
        long ha = (long) a.getX() * 73856093L ^ (long) a.getZ() * 19349663L;
        long hb = (long) b.getX() * 73856093L ^ (long) b.getZ() * 19349663L;
        return (ha ^ hb) * 6364136223846793005L + worldSeed;
    }
}
