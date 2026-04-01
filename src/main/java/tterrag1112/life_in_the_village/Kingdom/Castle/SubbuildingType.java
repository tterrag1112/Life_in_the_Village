package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.util.StringRepresentable;

/**
 * All possible functional subbuilding types that can exist within a castle.
 *
 * Each type maps to:
 *  - A PlacementZone defining where in the castle it belongs
 *  - A pool key under castle/subbuildings/<type>/<style>.nbt
 *  - A loot table under loot_tables/castle/<type>.json
 *  - One or more TownspersonMob profession slots
 *
 * Types marked REQUIRED must always be generated regardless of style config.
 */
public enum SubbuildingType implements StringRepresentable {

    // ---- Nobility & Governance ---- (REQUIRED for all castles)
    THRONE_ROOM     (PlacementZone.DONJON_GROUND,   true),
    COUNCIL_CHAMBER (PlacementZone.DONJON_UPPER,    false),
    ROYAL_QUARTERS  (PlacementZone.DONJON_UPPER,    true),
    NOBLE_QUARTERS  (PlacementZone.INNER_WARD,      false),

    // ---- Military ----
    BARRACKS        (PlacementZone.OUTER_WARD,      true),
    ARMOURY         (PlacementZone.WALL_INTERIOR,   false),
    TRAINING_YARD   (PlacementZone.COURTYARD,       false),
    GUARDHOUSE      (PlacementZone.TOWER_INTERIOR,  false),

    // ---- Scholarly & Religious ----
    LIBRARY         (PlacementZone.INNER_WARD,      false),
    CHAPEL          (PlacementZone.INNER_WARD,      false),
    OBSERVATORY     (PlacementZone.TOWER_INTERIOR,  false),
    ALCHEMIST_LAB   (PlacementZone.INNER_WARD,      false),

    // ---- Economic ----
    TREASURY        (PlacementZone.DONJON_GROUND,   true),
    GRANARY         (PlacementZone.OUTER_WARD,      false),
    STABLE          (PlacementZone.COURTYARD,       false),
    KITCHEN         (PlacementZone.OUTER_WARD,      false),

    // ---- Crafting & Production ----
    SMITHY          (PlacementZone.OUTER_WARD,      false),
    TAILORS_ROOM    (PlacementZone.INNER_WARD,      false),
    SCRIPTORIUM     (PlacementZone.INNER_WARD,      false),

    // ---- Infrastructure ----
    WELL            (PlacementZone.COURTYARD,       true),
    CISTERN         (PlacementZone.UNDERGROUND,     false),
    DUNGEON         (PlacementZone.UNDERGROUND,     false),
    GATEHOUSE_OFFICE(PlacementZone.WALL_INTERIOR,   false);

    public final PlacementZone defaultZone;
    public final boolean requiredAlways;

    SubbuildingType(PlacementZone defaultZone, boolean requiredAlways) {
        this.defaultZone    = defaultZone;
        this.requiredAlways = requiredAlways;
    }

    // ---- Where subbuildings can be placed ----

    public enum PlacementZone implements StringRepresentable {
        DONJON_GROUND,    // Ground floor of the keep
        DONJON_UPPER,     // Upper floors of the keep
        INNER_WARD,       // Inside the inner wall ring
        OUTER_WARD,       // Between inner and outer wall
        WALL_INTERIOR,    // Hollowed into the wall thickness
        TOWER_INTERIOR,   // Inside a specific tower
        UNDERGROUND,      // Below ground level
        COURTYARD;        // Open roofless area

        public static final com.mojang.serialization.Codec<PlacementZone> CODEC =
                StringRepresentable.fromEnum(PlacementZone::values);

        @Override
        public String getSerializedName() {
            return this.name().toLowerCase();
        }
    }

    public static final com.mojang.serialization.Codec<SubbuildingType> CODEC =
            StringRepresentable.fromEnum(SubbuildingType::values);

    @Override
    public String getSerializedName() {
        return this.name().toLowerCase();
    }
}