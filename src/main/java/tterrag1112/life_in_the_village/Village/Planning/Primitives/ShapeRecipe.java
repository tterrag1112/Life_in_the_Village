package tterrag1112.life_in_the_village.Village.Planning.Primitives;

import tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes.*;
import tterrag1112.life_in_the_village.Village.VillageTypeData.ShapeType;

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
            default -> {
                System.out.println("ShapeRecipe: " + shape
                        + " not yet implemented — falling back to RADIAL");
                yield new RadialRecipe();
            }
        };
    }
}