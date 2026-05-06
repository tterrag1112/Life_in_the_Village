package tterrag1112.life_in_the_village.Village.Planning.Primitives.Recipes;

import tterrag1112.life_in_the_village.Village.Planning.Adaptive.LayoutBlueprint;
import tterrag1112.life_in_the_village.Village.Planning.Adaptive.RecipeNotPortedException;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.BaseRecipe;
import tterrag1112.life_in_the_village.Village.Planning.Primitives.PlanContext;

/**
 * Phase A stub. Phase C ports this recipe to the declarative
 * blueprint pattern and runs it as the byte-equality smoke test for
 * the entire adaptive flow (per spec §11 "Phase C — Port RADIAL").
 */
public class RadialRecipe extends BaseRecipe {

    @Override
    public LayoutBlueprint compose(PlanContext pctx) {
        throw new RecipeNotPortedException("RADIAL");
    }
}
