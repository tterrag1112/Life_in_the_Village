package tterrag1112.life_in_the_village.Village;

import java.util.List;

public class VillageTypeData {

    public record StarterBuilding(
            String type,        // BuildingType name
            String structure,   // structure path
            int[] offset        // [x, y, z] offset from spawn origin
    ) {}

    public record StarterNpc(
            String profession,  // Profession name
            String buildingType,// BuildingType name of assigned building
            String familyRole   // FamilyRole name
    ) {}

    public record StarterItem(
            String buildingType,// BuildingType name of target building
            String item,        // item registry name
            int count
    ) {}

    private final String type;
    private final String culture;
    private final List<StarterBuilding> starterBuildings;
    private final List<StarterNpc> starterNpcs;
    private final List<StarterItem> starterItems;

    public VillageTypeData(String type, String culture,
                           List<StarterBuilding> starterBuildings,
                           List<StarterNpc> starterNpcs,
                           List<StarterItem> starterItems) {
        this.type = type;
        this.culture = culture;
        this.starterBuildings = List.copyOf(starterBuildings);
        this.starterNpcs = List.copyOf(starterNpcs);
        this.starterItems = List.copyOf(starterItems);
    }

    public String getType()                          { return type; }
    public String getCulture()                       { return culture; }
    public List<StarterBuilding> getStarterBuildings(){ return starterBuildings; }
    public List<StarterNpc> getStarterNpcs()         { return starterNpcs; }
    public List<StarterItem> getStarterItems()       { return starterItems; }
}
