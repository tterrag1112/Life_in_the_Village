package tterrag1112.life_in_the_village.Village.Decoration.Parks;

import net.minecraft.util.StringRepresentable;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B2.4 — catalog of garden styles per doc 09 §"Style catalog".
 *
 * <p>Each style ships with hardcoded metadata: a weighted piece
 * pool (drawn from {@link ParkPrimitive}), a preserve bias
 * (how much to lean on existing vegetation vs composed
 * primitives), bounding-size limits, and inclination affinities
 * that bias style selection by village character.</p>
 *
 * <p>Default culture is the only authored culture today —
 * future cultures override per-style metadata via a future
 * {@code GardenStyleRegistry} extension; B2.4 ships the registry
 * as a flat enum since the data is fixed and small.</p>
 */
public enum GardenStyle implements StringRepresentable {

    /** Default lawn + wildflowers + trees. Open, casual. */
    COTTAGE_GREEN(
            0.7,
            12, 25,
            EnumSet.of(TerrainAffinity.OPEN, TerrainAffinity.FOREST_EDGE),
            piecePool(
                    ParkPrimitive.FLOWER_BED, 3,
                    ParkPrimitive.HEDGEROW,    2,
                    ParkPrimitive.BENCH,       1,
                    ParkPrimitive.GRAVEL_PATH, 2,
                    ParkPrimitive.TRELLIS,     1),
            EnumSet.of(Inclination.AGRICULTURAL, Inclination.RESIDENTIAL)),

    /** Gravel + hedges + statues — geometric, civic. */
    FORMAL_PARK(
            0.2,
            16, 30,
            EnumSet.of(TerrainAffinity.OPEN),
            piecePool(
                    ParkPrimitive.HEDGEROW,        3,
                    ParkPrimitive.STATUE_PEDESTAL, 1,
                    ParkPrimitive.TOPIARY,         2,
                    ParkPrimitive.GRAVEL_PATH,     3,
                    ParkPrimitive.BENCH,           1),
            EnumSet.of(Inclination.CIVIC)),

    /** Sand + boulders + pond + bridge — contemplative. */
    ZEN_GARDEN(
            0.3,
            10, 20,
            EnumSet.of(TerrainAffinity.WATER_ADJACENT, TerrainAffinity.OPEN),
            piecePool(
                    ParkPrimitive.POND,           1,
                    ParkPrimitive.STANDING_STONE, 2,
                    ParkPrimitive.GRAVEL_PATH,    2,
                    ParkPrimitive.WOODEN_ARCH,    1),
            EnumSet.of(Inclination.SACRED, Inclination.CIVIC)),

    /** Standing stones + preserved trees + shrine — heaviest preserve bias. */
    SACRED_GROVE(
            0.9,
            14, 28,
            EnumSet.of(TerrainAffinity.FOREST_EDGE, TerrainAffinity.FOREST_INTERIOR),
            piecePool(
                    ParkPrimitive.STANDING_STONE, 2,
                    ParkPrimitive.BENCH,          1),
            EnumSet.of(Inclination.SACRED)),

    /** Obelisks + cypress + plaques — solemn civic memorial. */
    MEMORIAL_PARK(
            0.5,
            14, 25,
            EnumSet.of(TerrainAffinity.OPEN),
            piecePool(
                    ParkPrimitive.STATUE_PEDESTAL, 2,
                    ParkPrimitive.HEDGEROW,        2,
                    ParkPrimitive.GRAVEL_PATH,     2,
                    ParkPrimitive.BENCH,           1,
                    ParkPrimitive.LAMPPOST,        1),
            EnumSet.of(Inclination.CIVIC, Inclination.DEFENSIVE));

    private final double preserveBias;
    private final int minSize;
    private final int maxSize;
    private final Set<TerrainAffinity> terrainAffinity;
    private final Map<ParkPrimitive, Integer> piecePool;
    private final Set<Inclination> inclinationAffinity;

    GardenStyle(double preserveBias,
                int minSize, int maxSize,
                Set<TerrainAffinity> terrainAffinity,
                Map<ParkPrimitive, Integer> piecePool,
                Set<Inclination> inclinationAffinity) {
        this.preserveBias = preserveBias;
        this.minSize = minSize;
        this.maxSize = maxSize;
        this.terrainAffinity = terrainAffinity;
        this.piecePool = piecePool;
        this.inclinationAffinity = inclinationAffinity;
    }

    public double preserveBias()                { return preserveBias; }
    public int minSize()                        { return minSize; }
    public int maxSize()                        { return maxSize; }
    public Set<TerrainAffinity> terrainAffinity() { return terrainAffinity; }
    public Map<ParkPrimitive, Integer> piecePool() { return piecePool; }
    public Set<Inclination> inclinationAffinity() { return inclinationAffinity; }

    @Override public String getSerializedName() { return name(); }

    public static final com.mojang.serialization.Codec<GardenStyle> CODEC =
            StringRepresentable.fromEnum(GardenStyle::values);

    /** Helper: builds an order-preserving primitive→weight map. */
    private static Map<ParkPrimitive, Integer> piecePool(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("piecePool: odd number of args");
        }
        EnumMap<ParkPrimitive, Integer> out = new EnumMap<>(ParkPrimitive.class);
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((ParkPrimitive) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    /** Coarse terrain affinity hint used by the candidate finder
     *  to filter styles against a candidate area's dominant terrain. */
    public enum TerrainAffinity {
        OPEN, FOREST_EDGE, FOREST_INTERIOR, WATER_ADJACENT
    }

    /** Returns the {@code default}-culture preference weight map
     *  the prompt asks B2.4 to author: balanced across styles with
     *  slight emphasis on FORMAL + COTTAGE_GREEN (the prompt's
     *  "formal + orchard" lean translated to doc-09 names). */
    public static Map<GardenStyle, Double> defaultPreferenceWeights() {
        Map<GardenStyle, Double> m = new LinkedHashMap<>();
        m.put(COTTAGE_GREEN, 1.2);
        m.put(FORMAL_PARK,   1.2);
        m.put(ZEN_GARDEN,    1.0);
        m.put(SACRED_GROVE,  1.0);
        m.put(MEMORIAL_PARK, 0.9);
        return m;
    }

    /** Iterates every style; convenience for registry diagnostics. */
    public static List<GardenStyle> all() { return List.of(values()); }
}
