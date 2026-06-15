package tterrag1112.life_in_the_village.Npc.Tasks.Household;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.List;

/**
 * T2 — the household food <b>data contract</b>, the household-scope analogue
 * of a {@code ProductionTaskSpec}. The household has no profession; this holds
 * the small set of constants the household food task + its two fulfillments
 * share, so the source / bake / buy classes stay declarative.
 *
 * <p>The single migrated household need for the pilot is BREAD upkeep. The
 * legacy {@code HomeProductionBehavior} BAKING row's parameters are preserved
 * verbatim: per-member threshold 4, the {@code WHEAT_TO_BREAD} HOME recipe,
 * the SMOKER/FURNACE amenity preference, BAKING skill min level 1, 2 XP per
 * batch.</p>
 */
public final class HouseholdFood {

    private HouseholdFood() {}

    /** The household food good the task maintains. */
    public static final Item FOOD_ITEM = Items.BREAD;

    /** Per-member bread target (the legacy BAKING row's perMemberThreshold). */
    public static final int PER_MEMBER_THRESHOLD = 4;

    /** No refill buffer: refill whenever stock is below target, matching the
     *  legacy {@code countItem(...) >= familySize*4} family-need gate. */
    public static final int BUFFER = 0;

    /** The HOME bread recipe (verbatim from the legacy BAKING row). */
    public static final ProductionRecipe BREAD_RECIPE = SkillRecipes.WHEAT_TO_BREAD;

    /** Bake skill + its minimum level (legacy BAKING row). */
    public static final tterrag1112.life_in_the_village.Npc.Skills.Skill BAKE_SKILL =
            tterrag1112.life_in_the_village.Npc.Skills.Skill.BAKING;
    public static final int BAKE_MIN_LEVEL = 1;
    public static final int BAKE_XP_PER_BATCH = 2;

    /** Workstation amenity preference for baking (legacy BAKING row). */
    public static final List<AmenityType> BAKE_AMENITIES =
            List.of(AmenityType.SMOKER, AmenityType.FURNACE);

    /** Activity label shown while baking (legacy BAKING row). */
    public static final String BAKE_LABEL = "Baking bread";
}
