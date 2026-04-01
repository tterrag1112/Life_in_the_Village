package tterrag1112.life_in_the_village.Kingdom.Castle;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of all available WallDesigns.
 * Populated at startup with built-in designs.
 * Datapacks can register additional designs by adding entries here.
 */
public class WallDesignRegistry {

    public static final WallDesignRegistry INSTANCE = new WallDesignRegistry();

    private final Map<String, WallDesign> designs = new HashMap<>();

    private WallDesignRegistry() {
        // Register all built-in designs
        register(WallDesign.PLAIN);
        register(WallDesign.NORMAN);
        register(WallDesign.NORMAN_HEAVY);
        register(WallDesign.BANDED);
        register(WallDesign.PILASTERED);
        register(WallDesign.MOORISH);
        register(WallDesign.JAPANESE);
        register(WallDesign.DWARVEN);
        register(WallDesign.DARK);
        register(WallDesign.ELVISH);
        register(WallDesign.RUINED);
        register(WallDesign.FORMAL_PALACE);
        register(WallDesign.GRAND_PALACE);
        register(WallDesign.RUSTIC_MANOR);
    }

    public void register(WallDesign design) {
        designs.put(design.id(), design);
    }

    public WallDesign get(String id) {
        return designs.getOrDefault(id, WallDesign.PLAIN);
    }

    public boolean has(String id) {
        return designs.containsKey(id);
    }
}