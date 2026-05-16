package tterrag1112.life_in_the_village.Village.Planning.V2.Layer4;

import net.minecraft.world.level.block.Rotation;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.Variants.Style;
import tterrag1112.life_in_the_village.Village.Planning.FootprintProvider;
import tterrag1112.life_in_the_village.Village.Planning.StructureSizeCache;

import java.util.EnumMap;
import java.util.Map;

/**
 * Track E1 — fixed in-memory {@link FootprintProvider} for the
 * headless harness. Returns deterministic footprint dimensions per
 * {@link BuildingType}; no NBT lookups, no resource manager, no
 * server.
 *
 * <p>Sizes are coarse but realistic — small civic buildings around
 * 8x8, medium workshops/houses around 12x12, large halls around
 * 16x16, capital-scale buildings 20x20. The harness needs sizes to
 * be varied enough that the planner's footprint-aware spacing logic
 * is exercised; exact values don't matter as long as they're stable
 * across runs (the regression gate compares against its own
 * baseline, not against the live NBT).
 *
 * <p>{@link Style}, {@code culture}, {@code variantId}, and {@code
 * level} arguments are ignored — the harness keys footprints on
 * type + rotation alone. This matches the planner's actual usage
 * pattern: it always looks up the default variant at LEVEL=1.
 */
public final class FixedFootprintProvider implements FootprintProvider {

    private static final int MIN_GAP = StructureSizeCache.MIN_GAP;

    /** Default footprint when the type isn't in {@link #SIZES}. */
    private static final int[] DEFAULT = {12, 12};

    private static final Map<BuildingType, int[]> SIZES = new EnumMap<>(BuildingType.class);
    static {
        // small (8x8)
        for (BuildingType t : new BuildingType[]{
                BuildingType.HOUSE, BuildingType.WELL, BuildingType.STOCKPILE,
                BuildingType.SHRINE, BuildingType.WATCHTOWER, BuildingType.GUARD_TOWER,
                BuildingType.HEALER_HUT, BuildingType.PIER, BuildingType.BELL_TOWER}) {
            SIZES.put(t, new int[]{8, 8});
        }
        // medium (12x12) - the default; explicit entries for readability
        for (BuildingType t : new BuildingType[]{
                BuildingType.FARMHOUSE, BuildingType.BAKERY, BuildingType.STABLE,
                BuildingType.MILLER, BuildingType.WOODCUTTER, BuildingType.BLACKSMITH,
                BuildingType.CARPENTRY, BuildingType.MINE, BuildingType.STONEMASON,
                BuildingType.WEAVER, BuildingType.CANDLEMAKER, BuildingType.APOTHECARY,
                BuildingType.FISHERY, BuildingType.VINEYARD, BuildingType.WINERY,
                BuildingType.ARMORER, BuildingType.TOOLSMITH, BuildingType.ATELIER,
                BuildingType.SCRIBE_WORKSHOP, BuildingType.SCHOLARS_RETREAT,
                BuildingType.PRISON}) {
            SIZES.put(t, new int[]{12, 12});
        }
        // large (16x16)
        for (BuildingType t : new BuildingType[]{
                BuildingType.TOWN_HALL, BuildingType.MARKET, BuildingType.INN,
                BuildingType.CHAPEL, BuildingType.TEMPLE, BuildingType.BARRACKS,
                BuildingType.LIBRARY, BuildingType.WAREHOUSE, BuildingType.DOCKS,
                BuildingType.GUILD_HALL, BuildingType.GUILD_HALL_CRAFTSMEN,
                BuildingType.GUILD_HALL_MERCHANTS, BuildingType.GUILD_HALL_AGRICULTURAL,
                BuildingType.GUILD_HALL_RELIGIOUS, BuildingType.GUILD_HALL_SCHOLARLY,
                BuildingType.ESTATE, BuildingType.NOBLE_MANOR}) {
            SIZES.put(t, new int[]{16, 16});
        }
        // capital (20x20)
        for (BuildingType t : new BuildingType[]{
                BuildingType.CASTLE, BuildingType.CHANCELLERY, BuildingType.TREASURY}) {
            SIZES.put(t, new int[]{20, 20});
        }
        // Special — procedurally generated; planner never queries it
        // but enter an entry so a stray lookup doesn't NPE.
        SIZES.put(BuildingType.TOWN_SQUARE, new int[]{16, 16});
    }

    @Override
    public StructureSizeCache.FootprintInfo get(String culture, Style style,
                                                BuildingType type, String variantId,
                                                int level, Rotation rotation) {
        int[] wl = SIZES.getOrDefault(type, DEFAULT);
        int w = wl[0];
        int l = wl[1];
        if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90) {
            int tmp = w; w = l; l = tmp;
        }
        int halfW = w / 2;
        int halfL = l / 2;
        int radius = (int) Math.ceil(Math.sqrt(halfW * halfW + halfL * halfL)) + MIN_GAP;
        return new StructureSizeCache.FootprintInfo(w, l, radius);
    }
}
