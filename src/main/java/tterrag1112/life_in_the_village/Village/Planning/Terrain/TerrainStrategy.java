// src/main/java/tterrag1112/life_in_the_village/Village/Planning/Terrain/TerrainStrategy.java
package tterrag1112.life_in_the_village.Village.Planning.Terrain;

import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Planning.TerrainProfile;
import tterrag1112.life_in_the_village.Village.Planning.VillageLayout;
import tterrag1112.life_in_the_village.Village.Planning.Terrain.Steps.*;

import java.util.List;

/**
 * The overall terrain preparation strategy for a village type, composed
 * from {@link TerrainStep} pieces.
 *
 * <h3>Strategies</h3>
 * <ul>
 *   <li><b>FLAT</b> — minimal work, assumes buildable terrain. Light
 *       smoothing, small holes filled, building pads cleanly levelled.
 *       No retaining walls. Best for plains and farming villages where
 *       the site selector has already found flat ground.</li>
 *   <li><b>SLOPE_AWARE</b> — the default for forest and mixed-terrain
 *       villages. Building pads are cut into slopes with retaining
 *       walls on the uphill side and foundations on the downhill.
 *       Natural terrain between buildings is preserved.</li>
 *   <li><b>MOUNTAIN</b> — for villages intentionally built on steep
 *       terrain (hilltop, mountain types). No smoothing at all —
 *       retaining walls and foundations do all the work. The steep
 *       character of the site is preserved.</li>
 *   <li><b>WATERFRONT</b> — for coastal and river villages. Shoreline
 *       detection is recorded on the layout for Phase 8 to consume.
 *       Per-building prep is slope-aware.</li>
 * </ul>
 *
 * <h3>Adding a new strategy</h3>
 * Add an enum value with a list of steps. Existing steps can be
 * recomposed freely; new steps get new implementations. Village type
 * JSON gets a {@code terrain_strategy} field that parses into this
 * enum via {@link #fromName(String)}.
 */
public enum TerrainStrategy {

    FLAT(List.of(
            new ClearTreesStep(),
            new FillHolesStep(),
            new LightSmoothStep(),
            new LevelBuildingPadsStep(false) // no retaining walls
    )),

    SLOPE_AWARE(List.of(
            new ClearTreesStep(),
            new FillHolesStep(),
            new LevelBuildingPadsStep(true),
            new RetainingWallStep(),
            new FoundationStep()
    )),

    MOUNTAIN(List.of(
            new ClearTreesStep(),
            new LevelBuildingPadsStep(true),
            new RetainingWallStep(),
            new FoundationStep()
    )),

    WATERFRONT(List.of(
            new ClearTreesStep(),
            new FillHolesStep(),
            new DetectShorelineStep(), // records shoreline for Phase 8
            new LevelBuildingPadsStep(true),
            new RetainingWallStep(),
            new FoundationStep()
    ));

    private final List<TerrainStep> steps;

    TerrainStrategy(List<TerrainStep> steps) {
        this.steps = steps;
    }

    public List<TerrainStep> getSteps() {
        return steps;
    }

    /**
     * Runs every step in this strategy against the given layout.
     * Errors in individual steps are logged but don't abort the
     * pipeline — a failing retaining wall shouldn't prevent foundations.
     */
    public void execute(ServerLevel level,
                        VillageLayout layout,
                        TerrainProfile profile,
                        VillageBiomeStyle style) {
        System.out.println("TerrainStrategy: executing " + name()
                + " (" + steps.size() + " steps)");
        for (TerrainStep step : steps) {
            try {
                step.execute(level, layout, profile, style);
            } catch (Exception e) {
                System.out.println("TerrainStrategy: step '"
                        + step.name() + "' failed: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Parses a JSON string into a strategy, falling back to FLAT.
     */
    public static TerrainStrategy fromName(String name) {
        if (name == null) return FLAT;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("TerrainStrategy: unknown strategy '"
                    + name + "', falling back to FLAT");
            return FLAT;
        }
    }
}