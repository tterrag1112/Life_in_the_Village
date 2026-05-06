package tterrag1112.life_in_the_village.Village.Planning.Adaptive;

/**
 * Thrown by recipe stubs in Phase A. Every concrete BaseRecipe
 * subclass throws this until its Phase C/D port lands. The planner
 * catches it, marks the layout unplannable with a clear reason, and
 * MeasureCommand's classifier maps it to
 * {@code FailureMode.RECIPE_NOT_PORTED}.
 */
public class RecipeNotPortedException extends RuntimeException {
    public RecipeNotPortedException(String recipeName) {
        super("Recipe '" + recipeName + "' not yet ported to "
              + "declarative blueprint — Phase A architectural "
              + "cutover, recipe migration is Phase C/D. Spawning "
              + "this shape will fail until the recipe is ported.");
    }
}
