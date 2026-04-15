// src/main/java/tterrag1112/life_in_the_village/Village/VillageTagDeriver.java
package tterrag1112.life_in_the_village.Village;

import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStrategy;

import java.util.EnumSet;
import java.util.Set;

/**
 * Infers tags for a {@link VillageTypeData} from its other declared
 * properties. Lets village type JSON declare its terrain, layout, and
 * starter buildings once and have tags fall out automatically.
 *
 * <h3>Derivation rules</h3>
 * <table>
 *   <tr><th>Source property</th><th>Derived tag</th></tr>
 *   <tr><td>terrain_strategy: WATERFRONT</td><td>COASTAL, RIVERSIDE</td></tr>
 *   <tr><td>terrain_strategy: MOUNTAIN</td><td>MOUNTAIN</td></tr>
 *   <tr><td>shape_type: CROSSROADS</td><td>TRADE</td></tr>
 *   <tr><td>shape_type: RIVERINE or DOCKSIDE</td><td>RIVERSIDE</td></tr>
 *   <tr><td>shape_type: HILLTOP</td><td>MOUNTAIN, DEFENSIVE</td></tr>
 *   <tr><td>shape_type: OUTPOST</td><td>REMOTE, DEFENSIVE</td></tr>
 *   <tr><td>Has a DOCKS starter building</td><td>COASTAL</td></tr>
 *   <tr><td>≥ 2 FARMHOUSE buildings</td><td>AGRICULTURAL</td></tr>
 *   <tr><td>Has a CHURCH/TEMPLE/SHRINE</td><td>RELIGIOUS</td></tr>
 *   <tr><td>Has a MINE/FORGE/SMELTER/WORKSHOP</td><td>INDUSTRIAL</td></tr>
 * </table>
 *
 * <p>WATERFRONT implies both COASTAL and RIVERSIDE because the strategy
 * itself doesn't distinguish — individual types should narrow this by
 * adding a manual tag if they only want one (e.g. a fishing village
 * declares {@code "tags": ["COASTAL"]} to exclude inland river sites).
 */
public final class VillageTagDeriver {

    private VillageTagDeriver() {}

    // Building-type keyword triggers. Matched case-insensitively against
    // the StarterBuilding.type field because building types are loaded as
    // free-form strings from JSON.
    private static final String[] DOCKS_KEYWORDS     = { "dock", "pier", "wharf" };
    private static final String[] FARMHOUSE_KEYWORDS = { "farmhouse", "farm" };
    private static final String[] RELIGIOUS_KEYWORDS = { "church", "temple", "shrine", "chapel" };
    private static final String[] INDUSTRIAL_KEYWORDS = {
            "mine", "forge", "smelter", "workshop", "kiln", "foundry"
    };

    /** Computes the full derived tag set for a village type. */
    public static Set<VillageTag> derive(
            TerrainStrategy strategy,
            VillageTypeData.ShapeType shapeType,
            java.util.List<VillageTypeData.StarterBuilding> buildings) {

        Set<VillageTag> tags = EnumSet.noneOf(VillageTag.class);

        // ── From terrain strategy ──
        if (strategy != null) {
            switch (strategy) {
                case WATERFRONT -> { tags.add(VillageTag.COASTAL); tags.add(VillageTag.RIVERSIDE); }
                case MOUNTAIN   -> tags.add(VillageTag.MOUNTAIN);
                default         -> {}
            }
        }

        // ── From shape type ──
        if (shapeType != null) {
            switch (shapeType) {
                case CROSSROADS -> tags.add(VillageTag.TRADE);
                case RIVERINE, DOCKSIDE -> tags.add(VillageTag.RIVERSIDE);
                case HILLTOP    -> { tags.add(VillageTag.MOUNTAIN); tags.add(VillageTag.DEFENSIVE); }
                case OUTPOST    -> { tags.add(VillageTag.REMOTE); tags.add(VillageTag.DEFENSIVE); }
                default         -> {}
            }
            if (shapeType == VillageTypeData.ShapeType.DOCKSIDE) {
                tags.add(VillageTag.COASTAL);
            }
        }

        // ── From starter buildings ──
        if (buildings != null) {
            int farmhouses = 0;
            boolean hasDocks = false, hasReligious = false, hasIndustrial = false;

            for (VillageTypeData.StarterBuilding b : buildings) {
                String t = b.type() == null ? "" : b.type().toLowerCase();
                if (matchesAny(t, DOCKS_KEYWORDS))      hasDocks      = true;
                if (matchesAny(t, FARMHOUSE_KEYWORDS))  farmhouses   += Math.max(1, b.minCount());
                if (matchesAny(t, RELIGIOUS_KEYWORDS))  hasReligious  = true;
                if (matchesAny(t, INDUSTRIAL_KEYWORDS)) hasIndustrial = true;
            }

            if (hasDocks)        tags.add(VillageTag.COASTAL);
            if (farmhouses >= 2) tags.add(VillageTag.AGRICULTURAL);
            if (hasReligious)    tags.add(VillageTag.RELIGIOUS);
            if (hasIndustrial)   tags.add(VillageTag.INDUSTRIAL);
        }

        return tags;
    }

    private static boolean matchesAny(String haystack, String[] needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }
}