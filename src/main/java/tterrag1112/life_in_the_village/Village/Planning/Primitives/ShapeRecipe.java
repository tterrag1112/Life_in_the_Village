package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes.*;
import tterrag1112.life_in_the_village.Village.Roads.Planning.GatewayDescriptor;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

import java.util.List;

/**
 * Composes primitives into a complete layout for a village shape.
 *
 * <p>Add a new shape by writing a new {@code ShapeRecipe} and wiring it
 * into {@link #forShape}. No other code changes needed.
 */
public interface ShapeRecipe {

    /**
     * Reads buildings from {@link PlanContext#remaining}, adds road
     * primitives to the layout, and places building slots. Sets
     * {@code layout.mainGateEndpoint} if the shape has a main road.
     */
    void compose(PlanContext pctx);

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