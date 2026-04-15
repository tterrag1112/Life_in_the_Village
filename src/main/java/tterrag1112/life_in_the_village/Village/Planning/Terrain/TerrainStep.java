// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/TerrainStep.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;

/**
 * A single operation in a terrain preparation pipeline.
 *
 * <h3>Composable strategies</h3>
 * Each {@link TerrainStrategy} is a list of these steps executed in
 * order. Adding a new kind of preparation means adding one step
 * implementation. Adding a new village terrain style means adding one
 * enum value with the right step list. This is the composition point
 * that Phase 8's shoreline-aware and island-aware strategies will
 * extend without needing to touch existing code.
 *
 * <h3>Contract</h3>
 * Steps operate on the already-planned layout. They may:
 * <ul>
 *   <li>Modify world blocks (carving, filling, placing foundations)</li>
 *   <li>Read pad Y and footprint from each {@link VillageLayout}'s slots</li>
 *   <li>Read {@link TerrainProfile} for ridge and water awareness</li>
 * </ul>
 * Steps must NOT modify the layout itself — slot positions are final
 * by the time prep runs. The one exception is {@code snapY} calls to
 * resolve a slot's final Y after pad levelling.
 */
public interface TerrainStep {

    /**
     * A short name for logging.
     */
    String name();

    /**
     * Execute this step.
     *
     * @param level   the server level
     * @param layout  the finalised village layout
     * @param profile terrain analysis used during planning
     * @param style   biome-specific block palette for foundations,
     *                retaining walls, and caps
     */
    void execute(ServerLevel level,
                 VillageLayout layout,
                 TerrainProfile profile,
                 VillageBiomeStyle style);
}