package tterrag1112.life_in_the_village.Village.Roads.Graph.Worldgen;

import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter.BiasHints;
import tterrag1112.life_in_the_village.Village.Roads.Graph.GreatRoadCharacter.CharacterTag;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.BiomeCategory;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.List;

/**
 * Analyses a just-routed great-road cell path and assigns it a stable
 * {@link GreatRoadCharacter} based on the terrain it traverses.
 *
 * <p>Called by {@link GreatRoadTrunkRouter#routePair} immediately after A*
 * produces a successful cell path, before the edge is committed.
 */
public final class GreatRoadCharacterAnalyzer {

    private GreatRoadCharacterAnalyzer() {}

    /**
     * Walks the cell path, tallies terrain categories, and assigns a
     * {@link CharacterTag}. Tag thresholds are first-match (order matters).
     */
    public static GreatRoadCharacter analyze(
            List<Long> cellPath,
            WorldAtlas atlas,
            long worldSeed) {

        int total = 0, steepCount = 0, riverAdjCount = 0, forestCount = 0;
        int mountainCount = 0, plainCount = 0, coastCount = 0;

        for (long cellKey : cellPath) {
            AtlasCell cell = atlas.getCellByCoord(
                    AtlasCell.unpackX(cellKey), AtlasCell.unpackZ(cellKey));
            if (cell == null) continue;
            total++;
            if (cell.isSteep()) steepCount++;
            if (cell.isRiverAdj() || cell.has(AtlasCell.FLAG_HAS_RIVER)) riverAdjCount++;
            if (cell.isCoast()) coastCount++;
            BiomeCategory cat = cell.category();
            if (cat == BiomeCategory.FOREST) forestCount++;
            if (cat == BiomeCategory.MOUNTAIN) mountainCount++;
            if ((cat == BiomeCategory.PLAINS || cat == BiomeCategory.SAVANNA)
                    && !cell.isSteep()) plainCount++;
        }

        long characterSeed = computeCharacterSeed(cellPath, worldSeed);

        if (total == 0) {
            return buildCharacter(CharacterTag.DEFAULT, characterSeed);
        }

        float steepRatio    = (float) steepCount    / total;
        float riverRatio    = (float) riverAdjCount / total;
        float forestRatio   = (float) forestCount   / total;
        float mountainRatio = (float) mountainCount / total;
        float plainRatio    = (float) plainCount    / total;
        float coastRatio    = (float) coastCount    / total;

        CharacterTag tag;
        if (coastRatio > 0.4f) {
            tag = CharacterTag.COASTAL;
        } else if (mountainRatio > 0.5f) {
            tag = CharacterTag.MOUNTAIN_HUGGING;
        } else if (steepRatio > 0.3f) {
            tag = CharacterTag.UPLAND_PASS;
        } else if (riverRatio > 0.4f) {
            tag = CharacterTag.RIVER_FOLLOWING;
        } else if (forestRatio > 0.7f) {
            tag = CharacterTag.FOREST_WANDERING;
        } else if (plainRatio > 0.7f && steepRatio < 0.1f) {
            tag = CharacterTag.PLAINS_STRAIGHT;
        } else {
            tag = CharacterTag.DEFAULT;
        }

        return buildCharacter(tag, characterSeed);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    static long computeCharacterSeed(List<Long> cellPath, long worldSeed) {
        long first = cellPath.get(0);
        long last  = cellPath.get(cellPath.size() - 1);
        // XOR-combine first+last cell keys for direction-independence
        long h = worldSeed ^ (first * 6364136223846793005L) ^ (last * 2862933555777941757L);
        return h * 6364136223846793005L + 1442695040888963407L;
    }

    private static GreatRoadCharacter buildCharacter(CharacterTag tag, long characterSeed) {
        float amp, freq, steepBias, riverBias, forestBias;
        switch (tag) {
            case MOUNTAIN_HUGGING -> { amp = 18.0f; freq = 0.06f; steepBias = 1.5f; riverBias = 1.0f; forestBias = 1.0f; }
            case PLAINS_STRAIGHT  -> { amp = 4.0f;  freq = 0.05f; steepBias = 3.0f; riverBias = 1.0f; forestBias = 1.2f; }
            case RIVER_FOLLOWING  -> { amp = 14.0f; freq = 0.10f; steepBias = 2.0f; riverBias = 0.5f; forestBias = 1.0f; }
            case FOREST_WANDERING -> { amp = 10.0f; freq = 0.12f; steepBias = 2.0f; riverBias = 1.2f; forestBias = 0.9f; }
            case COASTAL          -> { amp = 8.0f;  freq = 0.08f; steepBias = 2.0f; riverBias = 1.0f; forestBias = 1.0f; }
            case UPLAND_PASS      -> { amp = 15.0f; freq = 0.07f; steepBias = 1.8f; riverBias = 1.0f; forestBias = 1.0f; }
            default               -> { amp = 12.0f; freq = 0.08f; steepBias = 2.0f; riverBias = 1.0f; forestBias = 1.0f; }
        }
        return new GreatRoadCharacter(
                tag, amp, freq, characterSeed,
                new BiasHints(steepBias, riverBias, forestBias, amp));
    }
}
