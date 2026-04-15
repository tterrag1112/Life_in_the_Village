// src/main/java/tterrag1112/life_in_the_village/Kingdom/KingdomClaimCosts.java
package tterrag1112.life_in_the_village.Kingdom;

import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Cost tables used by {@link KingdomClaimComputer}.
 *
 * <p>Two factors combine multiplicatively:
 * <ol>
 *   <li>Terrain cost — based on the cell's biome category and flags.
 *       Ocean is impassable; mountain is very expensive; flat land is
 *       cheap.</li>
 *   <li>Home-affinity multiplier — how "familiar" the cell's category
 *       is to the kingdom's home category. Matching category gets a
 *       discount, foreign categories pay more. This produces the
 *       "plains kingdoms don't sprawl into jungles" behaviour.</li>
 * </ol>
 *
 * <p>Per-culture affinity tables are a later slice. For now the table
 * is keyed purely off the origin's region category.
 */
public final class KingdomClaimCosts {

    private KingdomClaimCosts() {}

    // ── Base terrain costs ───────────────────────────────────────────────────

    /** Returned by {@link #baseCost} for impassable cells. */
    public static final float IMPASSABLE = Float.POSITIVE_INFINITY;

    public static float baseCost(AtlasCell cell) {
        if (cell == null) return 5f; // unfilled — treat as moderate unknown
        BiomeCategory cat = cell.category();

        // Oceans form hard borders. Rivers are cheap (follow them) but a
        // pure river cell with no land neighbour is effectively a wet wall.
        if (cat == BiomeCategory.OCEAN) return IMPASSABLE;
        if (cat == BiomeCategory.VOID)  return IMPASSABLE;
        if (cat == BiomeCategory.RIVER && !cell.isRiverAdj()) return IMPASSABLE;

        float base;
        switch (cat) {
            case PLAINS, SAVANNA -> base = 1.0f;
            case FOREST          -> base = 1.2f;
            case BEACH           -> base = 1.3f;
            case DESERT          -> base = 1.5f;
            case JUNGLE          -> base = 1.8f;
            case SNOWY           -> base = 1.6f;
            case SWAMP           -> base = 2.2f;
            case MOUNTAIN        -> base = 6.0f;   // firm border, not impassable
            case RIVER           -> base = 1.2f;   // river-adj land is cheap
            case MUSHROOM        -> base = 3.0f;
            default              -> base = 1.5f;
        }

        if (cell.isSteep()) base *= 1.8f;
        if (cell.isRiverAdj()) base *= 0.9f; // rivers guide expansion
        return base;
    }

    // ── Home affinity ────────────────────────────────────────────────────────

    /**
     * Multiplier for a cell's cost based on how related its category is
     * to the kingdom's home category. Lower multiplier = cheaper.
     *
     * <p>Default profile: home category 0.7×, friendly neighbours 0.85×,
     * neutral 1.0×, foreign 1.3×. Mountains stay expensive from terrain
     * cost regardless of affinity.
     */
    public static float affinityMultiplier(BiomeCategory home, BiomeCategory cell) {
        if (home == null || cell == null) return 1.0f;
        if (home == cell) return 0.7f;

        Map<BiomeCategory, Float> table = AFFINITY.get(home);
        if (table == null) return 1.0f;
        return table.getOrDefault(cell, 1.0f);
    }

    /** Returns combined cost for one cell, given the kingdom's home category. */
    public static float totalCost(AtlasCell cell, BiomeCategory home) {
        float base = baseCost(cell);
        if (Float.isInfinite(base)) return base;
        return base * affinityMultiplier(home, cell == null ? null : cell.category());
    }

    // ── Affinity tables ──────────────────────────────────────────────────────

    private static final Map<BiomeCategory, Map<BiomeCategory, Float>> AFFINITY =
            new EnumMap<>(BiomeCategory.class);

    static {
        // Plains kingdoms are comfortable in plains/forest/savanna, foreign in deserts/jungles
        put(BiomeCategory.PLAINS, Map.of(
                BiomeCategory.FOREST, 0.85f, BiomeCategory.SAVANNA, 0.85f,
                BiomeCategory.BEACH, 1.0f, BiomeCategory.SWAMP, 1.1f,
                BiomeCategory.DESERT, 1.3f, BiomeCategory.JUNGLE, 1.4f,
                BiomeCategory.SNOWY, 1.3f));

        put(BiomeCategory.FOREST, Map.of(
                BiomeCategory.PLAINS, 0.85f, BiomeCategory.SAVANNA, 0.95f,
                BiomeCategory.JUNGLE, 1.1f, BiomeCategory.SWAMP, 1.1f,
                BiomeCategory.DESERT, 1.4f, BiomeCategory.SNOWY, 1.2f));

        put(BiomeCategory.DESERT, Map.of(
                BiomeCategory.SAVANNA, 0.85f, BiomeCategory.PLAINS, 1.1f,
                BiomeCategory.BEACH, 1.0f, BiomeCategory.FOREST, 1.5f,
                BiomeCategory.JUNGLE, 1.5f, BiomeCategory.SNOWY, 1.8f,
                BiomeCategory.SWAMP, 1.8f));

        put(BiomeCategory.SAVANNA, Map.of(
                BiomeCategory.PLAINS, 0.85f, BiomeCategory.DESERT, 0.9f,
                BiomeCategory.FOREST, 0.95f, BiomeCategory.JUNGLE, 1.1f,
                BiomeCategory.SNOWY, 1.5f));

        put(BiomeCategory.JUNGLE, Map.of(
                BiomeCategory.FOREST, 1.0f, BiomeCategory.SWAMP, 1.0f,
                BiomeCategory.SAVANNA, 1.1f, BiomeCategory.PLAINS, 1.3f,
                BiomeCategory.DESERT, 1.6f, BiomeCategory.SNOWY, 1.8f));

        put(BiomeCategory.SNOWY, Map.of(
                BiomeCategory.MOUNTAIN, 0.9f, BiomeCategory.FOREST, 1.0f,
                BiomeCategory.PLAINS, 1.2f, BiomeCategory.DESERT, 1.8f,
                BiomeCategory.JUNGLE, 1.8f));

        put(BiomeCategory.SWAMP, Map.of(
                BiomeCategory.JUNGLE, 0.9f, BiomeCategory.FOREST, 0.95f,
                BiomeCategory.RIVER, 0.85f, BiomeCategory.PLAINS, 1.1f,
                BiomeCategory.DESERT, 1.6f, BiomeCategory.SNOWY, 1.6f));

        put(BiomeCategory.MOUNTAIN, Map.of(
                BiomeCategory.SNOWY, 0.9f, BiomeCategory.FOREST, 1.0f,
                BiomeCategory.PLAINS, 1.1f, BiomeCategory.DESERT, 1.3f,
                BiomeCategory.JUNGLE, 1.5f));
    }

    private static void put(BiomeCategory home, Map<BiomeCategory, Float> table) {
        AFFINITY.put(home, new EnumMap<>(table));
    }
}