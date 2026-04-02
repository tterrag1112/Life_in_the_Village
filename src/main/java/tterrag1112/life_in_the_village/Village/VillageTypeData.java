// src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeData.java
package tterrag1112.life_in_the_village.Village;

import java.util.List;

/**
 * Immutable data loaded from a village type JSON file.
 *
 * <h3>Capital configuration</h3>
 * Village types with 20+ starter buildings (after count expansion) are treated
 * as capitals by {@link tterrag1112.life_in_the_village.Village.Planning.VillagePlanner}
 * and routed to {@link tterrag1112.life_in_the_village.Village.Planning.CapitalLayoutPlanner}.
 * The {@link CapitalProfile} record — loaded from {@code "capital_profile"} in the JSON —
 * controls every tunable aspect of the capital layout engine:
 * layout topology, grid density, building dimensions, and gate behaviour.
 *
 * <h3>Serialisation</h3>
 * Loaded by {@link VillageTypeRegistry} from
 * {@code data/<modid>/village_types/<type>.json}.
 */
public class VillageTypeData {

    // =========================================================================
    // StarterBuilding — now uses min/max count instead of offset
    // =========================================================================

    public record StarterBuilding(
            String type,
            String structure,
            int    minCount,
            int    maxCount
    ) {
        public int resolveCount(java.util.Random rng) {
            if (maxCount <= minCount) return minCount;
            return minCount + rng.nextInt(maxCount - minCount + 1);
        }
    }

    public record StarterNpc(
            String profession,
            String buildingType,
            String familyRole
    ) {}

    public record StarterItem(
            String buildingType,
            String item,
            int    count
    ) {}

    // =========================================================================
    // Shape profile — controls the ring/linear/hilltop planner
    // =========================================================================

    public record VillageShapeProfile(
            ShapeType shapeType,
            boolean   forcedAxis,
            int       maxRings,
            float     streetDensity,
            boolean   walledByDefault
    ) {
        public static VillageShapeProfile defaultProfile() {
            return new VillageShapeProfile(ShapeType.RADIAL, false, 2, 1.0f, false);
        }
    }

    public enum ShapeType {
        RADIAL, LINEAR, CLUSTERED, RIVERINE, HILLTOP, PLAZA, COURTYARD
    }

    // =========================================================================
    // Capital profile — controls CapitalLayoutPlanner when buildingCount >= 20
    // =========================================================================

    /**
     * Fine-grained controls for capital city layout.
     *
     * <p>All fields are optional in the JSON; sensible defaults are supplied
     * by {@link #defaultRound()}. Modpack designers can override individual
     * values to produce dramatically different city feels.</p>
     *
     * <h3>Grid spacing</h3>
     * If {@code gridSpacing == 0} (the default) the planner auto-calculates
     * spacing to fit exactly {@code buildingsPerFace} buildings per city
     * block face using the formula:
     * <pre>
     *   spacing = buildingsPerFace * (buildingWidth + alleyGap)
     *           + 2 * faceSetback + 4
     * </pre>
     */
    public record CapitalProfile(
            /**
             * Overall street layout topology.
             * <ul>
             *   <li>ROUND — wagon-wheel radial avenues + rectangular grid blocks</li>
             *   <li>SQUARE — pure axis-aligned grid, Roman castrum style</li>
             *   <li>ORGANIC — denser ring-based layout (no strict grid)</li>
             * </ul>
             */
            CapitalLayoutType layoutType,
            /**
             * Grid line spacing in blocks. 0 = auto-calculate from
             * {@code buildingWidth}, {@code alleyGap}, {@code faceSetback},
             * and {@code buildingsPerFace}.
             */
            int gridSpacing,
            /**
             * Target number of buildings along each face of a city block.
             * Used in the auto-spacing formula. Default 2.
             */
            int buildingsPerFace,
            /** Typical building footprint width (along the street). Default 12. */
            int buildingWidth,
            /** Typical building footprint depth (perpendicular to street). Default 10. */
            int buildingDepth,
            /** Distance from road kerb to building front face. Default 2. */
            int faceSetback,
            /** Gap between adjacent buildings on the same face. Default 2. */
            int alleyGap,
            /** Number of radial avenues for ROUND layout. Default 6. */
            int spokeCount,
            /**
             * When true, trade roads terminate at the nearest city gate
             * (outer ring / perimeter) rather than routing to the town square.
             * This prevents the trade road from carving through the city wall.
             */
            boolean gateRoads
    ) {
        /** Default profile — ROUND layout with comfortable city-block sizing. */
        public static CapitalProfile defaultRound() {
            return new CapitalProfile(
                    CapitalLayoutType.ROUND,
                    0,   // auto-spacing
                    2,   // 2 buildings per face
                    12,  // building width
                    10,  // building depth
                    2,   // face setback
                    4,   // alley gap
                    6,   // spoke count
                    true // gate roads
            );
        }

        /**
         * Resolves the actual grid spacing to use.
         * When {@code gridSpacing > 0} that value is used directly.
         * When 0, auto-calculates to fit {@code buildingsPerFace} per face.
         */
        public int resolvedGridSpacing() {
            if (gridSpacing > 0) return gridSpacing;
            // Road surface on both sides: 2 × roadHalfWidth (SECONDARY=2) = 4
            // Setback from road kerb: 2 × faceSetback
            // Buildings per face × (width + gap) — gap must be > MIN_GAP(3)
            // Corner inset: 2 × buildingDepth (one each end of N/S faces)
            // Margin: 4 blocks for numerical safety
            int effectiveGap  = Math.max(alleyGap, 4);     // must exceed MIN_GAP(3)
            int roadBothSides = 4;                          // 2 × SECONDARY.halfWidth
            int cornerBothEnds= 2 * buildingDepth;
            int raw = buildingsPerFace * (buildingWidth + effectiveGap)
                    + 2 * faceSetback
                    + roadBothSides
                    + cornerBothEnds
                    + 4;                                    // safety margin
            return (raw % 2 == 0) ? raw : raw + 1;         // round up to even
        }
    }

    /** Layout topology for capitals. */
    public enum CapitalLayoutType {
        /** Wagon-wheel radial avenues + rectangular grid blocks. */
        ROUND,
        /** Pure axis-aligned rectangular grid, Roman castrum style. */
        SQUARE,
        /** Organic ring-based layout without strict geometry. */
        ORGANIC
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final String               type;
    public final String               culture;
    private final List<StarterBuilding> starterBuildings;
    private final List<StarterNpc>      starterNpcs;
    private final List<StarterItem>     starterItems;
    private final VillageShapeProfile   shapeProfile;
    private final CapitalProfile        capitalProfile;

    // =========================================================================
    // Constructors
    // =========================================================================

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           List<StarterNpc>      starterNpcs,
                           List<StarterItem>     starterItems) {
        this(type, culture, starterBuildings, starterNpcs, starterItems,
                VillageShapeProfile.defaultProfile(),
                CapitalProfile.defaultRound());
    }

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           List<StarterNpc>      starterNpcs,
                           List<StarterItem>     starterItems,
                           VillageShapeProfile   shapeProfile) {
        this(type, culture, starterBuildings, starterNpcs, starterItems,
                shapeProfile, CapitalProfile.defaultRound());
    }

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           List<StarterNpc>      starterNpcs,
                           List<StarterItem>     starterItems,
                           VillageShapeProfile   shapeProfile,
                           CapitalProfile        capitalProfile) {
        this.type             = type;
        this.culture          = culture;
        this.starterBuildings = List.copyOf(starterBuildings);
        this.starterNpcs      = List.copyOf(starterNpcs);
        this.starterItems     = List.copyOf(starterItems);
        this.shapeProfile     = shapeProfile;
        this.capitalProfile   = capitalProfile != null
                ? capitalProfile : CapitalProfile.defaultRound();
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String               getType()             { return type;             }
    public String               getCulture()          { return culture;          }
    public List<StarterBuilding> getStarterBuildings() { return starterBuildings; }
    public List<StarterNpc>      getStarterNpcs()      { return starterNpcs;      }
    public List<StarterItem>     getStarterItems()     { return starterItems;     }
    public VillageShapeProfile   getShapeProfile()     { return shapeProfile;     }
    public CapitalProfile        getCapitalProfile()   { return capitalProfile;   }
}