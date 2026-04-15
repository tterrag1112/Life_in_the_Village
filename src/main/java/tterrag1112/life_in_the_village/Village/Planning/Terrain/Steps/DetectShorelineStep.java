// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/Steps/DetectShorelineStep.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain.Steps;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainStep;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;

/**
 * Detects shoreline segments around the village and records them on
 * the layout for later steps to consume.
 *
 * <h3>Phase 9a scope</h3>
 * This step is a stub in Phase 9a. The detection logic is implemented
 * but nothing is done with the result yet. Phase 8 will add shoreline
 * wall placement and island-aware subdivision that consume the data
 * this step produces.
 *
 * <p>Included in the {@code WATERFRONT} strategy now so the
 * infrastructure exists and Phase 8 can drop in without refactoring.
 */
public class DetectShorelineStep implements TerrainStep {

    @Override
    public String name() { return "detect_shoreline"; }

    @Override
    public void execute(ServerLevel level, VillageLayout layout,
                        TerrainProfile profile, VillageBiomeStyle style) {
        // Phase 9a stub — Phase 8 implements this
        if (profile.hasWater()) {
            System.out.println("DetectShorelineStep: water body present at "
                    + profile.waterBody().centre().toShortString()
                    + " (shoreline detection deferred to Phase 8)");
        }
    }
}