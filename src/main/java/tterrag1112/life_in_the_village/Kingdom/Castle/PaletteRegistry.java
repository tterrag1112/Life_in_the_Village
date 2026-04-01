package tterrag1112.life_in_the_village.Kingdom.Castle;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of all available ArchitectPalettes.
 * Built-in palettes registered at startup.
 * Datapacks can add custom palettes.
 */
public class PaletteRegistry {

    public static final PaletteRegistry INSTANCE = new PaletteRegistry();

    private final Map<String, ArchitectPalette> palettes = new HashMap<>();

    private PaletteRegistry() {
        register("stone_brick",   ArchitectPalette.stoneBrick());
        register("sandstone",     ArchitectPalette.sandstone());
        register("deepslate",     ArchitectPalette.deepslate());
        register("blackstone",    ArchitectPalette.blackstone());
        register("quartz",        ArchitectPalette.quartz());
        register("red_sandstone", ArchitectPalette.redSandstone());
        register("oak_wood",      ArchitectPalette.oakWood());
        register("prismarine",    ArchitectPalette.prismarine());
        register("formal_palace", ArchitectPalette.formalPalace());
        register("grand_palace",  ArchitectPalette.grandPalace());
        register("rustic_manor",  ArchitectPalette.rusticManor());
    }

    public void register(String id, ArchitectPalette palette) {
        palettes.put(id, palette);
    }

    public ArchitectPalette get(String id) {
        return palettes.getOrDefault(id, ArchitectPalette.stoneBrick());
    }
}