// src/main/java/tterrag1112/life_in_the_village/Kingdom/KingdomClaim.java
package tterrag1112.life_in_the_village.Kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * A kingdom's territorial claim as a set of atlas cell keys.
 *
 * <p>Produced by {@link KingdomClaimComputer} via Dijkstra expansion
 * from the kingdom's origin cell. The claim is the spatial envelope
 * within which the kingdom's villages are eventually placed.
 *
 * <p>Stored on the {@link Kingdom} and persisted with it. Immutable
 * after creation — recomputing a claim produces a new instance.
 */
public record KingdomClaim(
        long originCellKey,
        List<Long> claimedCellKeys,
        float budgetSpent,
        float budgetTotal,
        /** Category of the region the origin fell in — used as "home category". */
        String homeCategoryName
) {
    public static final Codec<KingdomClaim> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("origin").forGetter(KingdomClaim::originCellKey),
            Codec.LONG.listOf().fieldOf("cells").forGetter(KingdomClaim::claimedCellKeys),
            Codec.FLOAT.optionalFieldOf("spent", 0f).forGetter(KingdomClaim::budgetSpent),
            Codec.FLOAT.optionalFieldOf("total", 0f).forGetter(KingdomClaim::budgetTotal),
            Codec.STRING.optionalFieldOf("home", "PLAINS").forGetter(KingdomClaim::homeCategoryName)
    ).apply(i, KingdomClaim::new));

    public int size() { return claimedCellKeys.size(); }
    /**
     * Maximum distance in blocks from the origin cell centre to any
     * claimed cell centre. A loose proxy for "how far this claim
     * reaches" — used by kingdom-to-kingdom spacing checks.
     */
    public int effectiveRadiusBlocks() {
        int ox = tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackX(originCellKey);
        int oz = tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackZ(originCellKey);
        int oxBlock = (ox << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
        int ozBlock = (oz << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
        long worstSq = 0;
        for (long key : claimedCellKeys) {
            int cx = tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackX(key);
            int cz = tterrag1112.life_in_the_village.World.Atlas.AtlasCell.unpackZ(key);
            int cxBlock = (cx << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                    + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
            int czBlock = (cz << tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_SHIFT)
                    + tterrag1112.life_in_the_village.World.Atlas.AtlasCell.CELL_HALF;
            long dx = cxBlock - oxBlock;
            long dz = czBlock - ozBlock;
            long dsq = dx * dx + dz * dz;
            if (dsq > worstSq) worstSq = dsq;
        }
        return (int) Math.sqrt(worstSq);
    }
}