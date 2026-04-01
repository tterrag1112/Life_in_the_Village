package tterrag1112.life_in_the_village.Kingdom.Castle;

import net.minecraft.resources.Identifier;

/**
 * The type of architectural element a design file represents.
 * Determines the subdirectory under data/life_in_the_village/structures/castle/
 * and which design pool in DetailApplicator consumes it.
 */
public enum DesignType {

    /** Flat wall face — tiled horizontally along a wall panel. */
    WALL("walls"),

    /** Battlement / parapet — stamped on the top course of a wall. */
    BATTLEMENT("battlements"),

    /** Tower cap — stamped centered on the tower top ring. */
    TOWER_CAP("tower_caps"),

    /** Gatehouse facade — stamped over the gate passage top. */
    GATEHOUSE("gatehouses"),

    /** Corner piece — placed at the junction of two wall panels. */
    CORNER("corners"),

    /** Window surround — stamped around arrow slit openings. */
    WINDOW("windows"),

    /** Donjon roof — stamped on top of the keep. */
    DONJON_ROOF("donjon_roofs");

    private final String folder;

    DesignType(String folder) {
        this.folder = folder;
    }

    public String folder() { return folder; }

    /** Canonical NBT path for index N of this type and style. */
    public String nbtPath(String styleId, int index) {
        return "castle/" + folder + "/" + styleId + "_" + index;
    }

    /** ResourceLocation-style key for DetailApplicator pool lookup. */
    public Identifier poolKey(String styleId, int index) {
        return Identifier.fromNamespaceAndPath(
                "life_in_the_village",
                "castle/" + folder + "/" + styleId + "_" + index);
    }
}