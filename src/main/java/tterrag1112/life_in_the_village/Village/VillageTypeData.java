// src/main/java/tterrag1112/life_in_the_village/Village/VillageTypeData.java
package tterrag1112.life_in_the_village.Village;

import java.util.List;

public class VillageTypeData {

    public record StarterBuilding(
            String type,
            String structure,
            int    minCount,  // minimum instances to place (default 1)
            int    maxCount   // maximum instances to place (default same as min)
    ) {
        /** Returns the exact count when min == max, or min as the floor. */
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
    // Shape profile — controls how the planner arranges buildings
    // =========================================================================

    /**
     * Describes the gross layout topology of a village type.
     *
     * <p>Loaded from the {@code "shape_profile"} object in the village JSON.
     * If absent, defaults to RADIAL with neutral parameters.</p>
     */
    public record VillageShapeProfile(
            /** Gross layout topology. */
            ShapeType shapeType,
            /**
             * For LINEAR villages: if true the planner aligns the street
             * axis to the terrain's flattest direction; if false it uses
             * the spawner's default north-south axis.
             */
            boolean forcedAxis,
            /**
             * Maximum number of building rings (RADIAL, PLAZA, COURTYARD).
             * Ignored for LINEAR, CLUSTERED, HILLTOP, RIVERINE.
             */
            int maxRings,
            /**
             * Multiplier on the base spacing between buildings.
             * {@code < 1.0} = dense, {@code > 1.0} = sparse.
             */
            float streetDensity,
            /**
             * If true the village always gets a perimeter wall regardless
             * of the random roll in VillagePerimeter.
             */
            boolean walledByDefault
    ) {
        /** Default profile — mirrors the existing RADIAL behaviour. */
        public static VillageShapeProfile defaultProfile() {
            return new VillageShapeProfile(
                    ShapeType.RADIAL, false, 2, 1.0f, false);
        }
    }

    /**
     * Village layout topologies.
     *
     * <table>
     *   <tr><td>RADIAL</td>
     *       <td>Classic ring layout around the town hall.
     *           Most towns and hamlets.</td></tr>
     *   <tr><td>LINEAR</td>
     *       <td>Buildings flanking a main street along the terrain's
     *           flattest axis. Market towns, road villages.</td></tr>
     *   <tr><td>CLUSTERED</td>
     *       <td>Loose organic micro-clusters scattered around the centre.
     *           Remote hamlets, farming settlements.</td></tr>
     *   <tr><td>RIVERINE</td>
     *       <td>Buildings arranged along a waterfront; falls back to RADIAL
     *           if no water is detected.</td></tr>
     *   <tr><td>HILLTOP</td>
     *       <td>Town hall at the highest point, buildings spiral down
     *           the slope. Defensive settlements, watchtower towns.</td></tr>
     *   <tr><td>PLAZA</td>
     *       <td>Buildings arranged around a large central open square.
     *           Planned towns, trade hubs.</td></tr>
     *   <tr><td>COURTYARD</td>
     *       <td>Tight inner ring forms an enclosed ward; outer ring is
     *           looser. Fortified villages, noble estates.</td></tr>
     * </table>
     */
    public enum ShapeType {
        RADIAL, LINEAR, CLUSTERED, RIVERINE, HILLTOP, PLAZA, COURTYARD
    }

    // =========================================================================
    // Fields
    // =========================================================================

    private final String               type;
    private final String               culture;
    private final List<StarterBuilding> starterBuildings;
    private final List<StarterNpc>      starterNpcs;
    private final List<StarterItem>     starterItems;
    private final VillageShapeProfile   shapeProfile;

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           List<StarterNpc>      starterNpcs,
                           List<StarterItem>     starterItems) {
        this(type, culture, starterBuildings, starterNpcs, starterItems,
                VillageShapeProfile.defaultProfile());
    }

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           List<StarterNpc>      starterNpcs,
                           List<StarterItem>     starterItems,
                           VillageShapeProfile   shapeProfile) {
        this.type             = type;
        this.culture          = culture;
        this.starterBuildings = List.copyOf(starterBuildings);
        this.starterNpcs      = List.copyOf(starterNpcs);
        this.starterItems     = List.copyOf(starterItems);
        this.shapeProfile     = shapeProfile;
    }

    public String               getType()             { return type; }
    public String               getCulture()          { return culture; }
    public List<StarterBuilding> getStarterBuildings() { return starterBuildings; }
    public List<StarterNpc>      getStarterNpcs()      { return starterNpcs; }
    public List<StarterItem>     getStarterItems()     { return starterItems; }
    public VillageShapeProfile   getShapeProfile()     { return shapeProfile; }
}