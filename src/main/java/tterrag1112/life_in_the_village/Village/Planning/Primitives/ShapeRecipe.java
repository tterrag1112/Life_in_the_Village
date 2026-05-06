package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import tterrag1112.life_in_the_village.Village.Planning.Adaptive.LayoutBlueprint;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes.*;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayDescriptor;
import tterrag1112.life_in_the_village.Village.Roads.Planning.VillageEdgeDescriptor;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.List;

/**
 * Composes primitives into a complete layout for a village shape.
 *
 * <p>Add a new shape by writing a new {@code ShapeRecipe} and wiring it
 * into {@link #forShape}. No other code changes needed.
 *
 * <p>Phase A: {@code compose} now returns a {@link LayoutBlueprint} —
 * a declarative structure the planner realises against world geometry
 * after the recipe returns. Recipes no longer compute slot positions
 * or invoke road primitives directly.
 */
public interface ShapeRecipe {

    /**
     * Returns a declarative blueprint describing the village's roads,
     * plazas, sectors, slot intentions, and named anchors. The planner
     * realises the blueprint post-compose: runs road primitives,
     * generates plaza polygons, registers sectors, runs the
     * SlotEmitter.
     *
     * <p>Phase A recipes throw {@link
     * tterrag1112.life_in_the_village.Village.Planning.Adaptive
     * .RecipeNotPortedException} until their Phase C/D ports land.
     */
    LayoutBlueprint compose(PlanContext pctx);

    /**
     * Returns gateway descriptors for this layout after {@link #compose} has run.
     *
     * <p>The default reads gate positions from the layout (set by the recipe via
     * {@code layout.addGatePosition}).  The PRIMARY descriptor corresponds to
     * {@code layout.getMainGateEndpoint()}; all other positions become SIDE.
     *
     * <p>Recipes that override this (LINEAR, ROADSIDE, CHAIN) provide explicit
     * two-gateway behavior; all other recipes inherit the default, which returns
     * a single PRIMARY from whatever gate positions the recipe recorded.
     *
     * <p>Called by {@link tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayPopulator}
     * via {@link tterrag1112.life_in_the_village.Village.VillageSpawner} after
     * compose completes. Must not throw.
     */
    default List<GatewayDescriptor> describeGateways(PlanContext pctx) {
        return GatewayDescriptor.deriveFromLayout(pctx);
    }

    /**
     * Returns internal road edge descriptors for this layout after {@link #compose} has run.
     *
     * <p>The default returns an empty list, meaning no internal road edges are committed.
     * Recipes for layouts with a traversable through-road (LINEAR, ROADSIDE, CHAIN) override
     * this to return descriptors so the village road graph gets its internal edges.
     *
     * <p>Called by {@link tterrag1112.life_in_the_village.Village.Roads.Planning.InternalRoadCommitter}
     * after {@link tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayPopulator} has
     * committed the gateway nodes.  Must not throw.
     */
    default List<VillageEdgeDescriptor> describeInternalRoads(PlanContext pctx) {
        return List.of();
    }

    static ShapeRecipe forShape(ShapeType shape) {
        return switch (shape) {
            case RADIAL -> new RadialRecipe();
            case RIVERINE -> new RiverineRecipe();
            case LINEAR -> new LinearRecipe();
            case PLAZA -> new PlazaRecipe();
            case CROSSROADS -> new CrossroadsRecipe();
            case CLUSTERED -> new ClusteredRecipe();
            case CHAIN -> new ChainRecipe();
            case HILLTOP -> new HilltopRecipe();
            case ROADSIDE -> new RoadsideRecipe();
            case GROVE -> new GroveRecipe();
            case SPRAWL -> new SprawlRecipe();
            case DOCKSIDE -> new DocksideRecipe();
            case DUAL_PLAZA -> new DualPlazaRecipe();
            case OUTPOST -> new OutpostRecipe();
            case TERRACED -> new TerracedRecipe();
            case ENCLAVE -> new EnclaveRecipe();
            case DUMBELL -> new DumbellRecipe();
            default -> {
                System.out.println("ShapeRecipe: " + shape
                        + " not yet implemented — falling back to RADIAL");
                yield new RadialRecipe();
            }
        };
    }
}