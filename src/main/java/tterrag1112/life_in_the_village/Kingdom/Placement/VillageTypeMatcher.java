// src/main/java/tterrag1112/life_in_the_village/Kingdom/Placement/VillageTypeMatcher.java
package tterrag1112.life_in_the_village.Kingdom.Placement;

import tterrag1112.life_in_the_village.Village.VillageTag;
import tterrag1112.life_in_the_village.Village.VillageTypeData;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;

import java.util.Set;

/**
 * Decides whether an atlas cell is a valid host for a village type
 * given that type's tag set.
 *
 * <h3>Matching semantics</h3>
 * A type with NO tags matches any buildable cell — it's a "generalist"
 * type with no terrain preference. Types WITH tags require every tag
 * to be satisfiable by the cell:
 * <ul>
 *   <li>AGRICULTURAL — requires PLAINS/SAVANNA category, low slope</li>
 *   <li>COASTAL — requires coast flag</li>
 *   <li>RIVERSIDE — requires river-adj or freshwater flag</li>
 *   <li>MOUNTAIN — requires MOUNTAIN category or steep flag</li>
 *   <li>FOREST/DESERT/JUNGLE/SWAMP/SNOWY — require matching category</li>
 *   <li>TRADE/DEFENSIVE/RELIGIOUS/INDUSTRIAL/REMOTE — no hard filter,
 *       handled by scoring function</li>
 * </ul>
 *
 * <p>Tag matching uses AND semantics between hard-filter tags — a
 * COASTAL+RIVERSIDE type needs both a coast flag AND river adjacency,
 * which is quite rare (an estuary). Most types declare one or the other.
 */
public final class VillageTypeMatcher {

    private VillageTypeMatcher() {}

    /** Hard filter — can this cell host a village of this type at all? */
    public static boolean cellMatchesType(AtlasCell cell, VillageTypeData type) {
        if (cell == null) return false;
        if (!cell.isBuildable()) return false;
        if (cell.isSteep() && !type.getTags().contains(VillageTag.MOUNTAIN)) return false;

        Set<VillageTag> tags = type.getTags();
        if (tags.isEmpty()) return true; // generalist

        BiomeCategory cat = cell.category();

        if (tags.contains(VillageTag.AGRICULTURAL)) {
            if (cat != BiomeCategory.PLAINS && cat != BiomeCategory.SAVANNA) return false;
            if (cell.slope() > 6) return false;
        }
        if (tags.contains(VillageTag.COASTAL) && !cell.isCoast()) return false;
        if (tags.contains(VillageTag.RIVERSIDE)
                && !cell.isRiverAdj() && !cell.isFreshwater()) return false;
        if (tags.contains(VillageTag.MOUNTAIN)
                && cat != BiomeCategory.MOUNTAIN && !cell.isSteep()) return false;
        if (tags.contains(VillageTag.FOREST) && cat != BiomeCategory.FOREST) return false;
        if (tags.contains(VillageTag.DESERT) && cat != BiomeCategory.DESERT) return false;
        if (tags.contains(VillageTag.SWAMP)  && cat != BiomeCategory.SWAMP)  return false;
        // JUNGLE, SNOWY: only match their categories when explicitly tagged
        return true;
    }

    /**
     * Does any hard-filter tag reject this cell, but only by tag match
     * rather than by unbuildability? Used by the relaxed-match fallback
     * to find "close enough" cells when strict matching yields nothing.
     */
    public static boolean cellMatchesRelaxed(AtlasCell cell, VillageTypeData type) {
        if (cell == null || !cell.isBuildable()) return false;
        // Drop category tags; keep water-adjacency and slope gates since
        // those are physical, not stylistic.
        Set<VillageTag> tags = type.getTags();
        if (tags.contains(VillageTag.COASTAL)    && !cell.isCoast())    return false;
        if (tags.contains(VillageTag.RIVERSIDE)
                && !cell.isRiverAdj() && !cell.isFreshwater()) return false;
        return true;
    }
}