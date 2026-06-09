package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Hobby.NpcHobbyPreference;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Npc.Schedule.ScheduleResolver;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.List;
import java.util.Optional;

/**
 * Production Architecture M2 — the HOME production context. Collapses the four
 * hand-written {@code Home{Baking,Milling,Weaving,Candlemaking}Behavior} classes
 * into one context over the {@link ContextProductionBehavior} primitive (R6b):
 * the home context exercises the family's developed skills, gated by family need
 * + an economic/hobby motive, depositing to the household.
 *
 * <p>Adding a home craft is a {@link #TABLE} row, not a new behavior class.</p>
 *
 * <p><b>Behavior-preserving:</b> {@link #selectPlan} is the verbatim M2 selection
 * (skill ≥ level + amenity present + inputs available + family-need + economic/
 * hobby motive), iterated in the original registration order (baking, milling,
 * weaving, candlemaking), first qualifying row wins. The shared phase machine
 * lives in the base. The four crafts are indistinguishable from M1/M2.</p>
 */
public class HomeProductionBehavior extends ContextProductionBehavior {

    /**
     * One home craft. Inputs / output / ticks come from {@link #recipe}; the
     * family-need good is {@code recipe.output()}. {@code amenities} is the
     * preference-ordered workstation list (empty = no workstation, produce at
     * the house). {@code hobbyId} null = LEISURE alone is the non-economic
     * motive (milling has no dedicated hobby).
     */
    public record HomeCraft(Skill skill, int minLevel, List<AmenityType> amenities,
                            ProductionRecipe recipe, int perMemberThreshold,
                            long economicThreshold, String hobbyId,
                            int xpPerBatch, String activityLabel) {}

    /** The four initial rows — the exact parameters of the former behaviors. */
    private static final List<HomeCraft> TABLE = List.of(
            new HomeCraft(Skill.BAKING, 1, List.of(AmenityType.SMOKER, AmenityType.FURNACE),
                    SkillRecipes.WHEAT_TO_BREAD, 4, 150L, "home_cooking", 2, "Baking bread"),
            new HomeCraft(Skill.MILLING, 1, List.of(AmenityType.GRINDSTONE),
                    SkillRecipes.GRIND_WHEAT, 4, 150L, null, 2, "Milling flour"),
            new HomeCraft(Skill.WEAVING, 1, List.of(AmenityType.LOOM),
                    SkillRecipes.SPIN_STRING, 2, 150L, "home_weaving", 2, "Spinning wool"),
            new HomeCraft(Skill.CANDLEMAKING, 1, List.of(),
                    SkillRecipes.MAKE_TORCH, 4, 150L, "home_chandlery", 2, "Making torches"));

    @Override
    protected Optional<Plan> selectPlan(ServerLevel level, TownspersonMob entity) {
        VillageSavedData data = VillageSavedData.get(level);
        Building h = entity.getHouseId().flatMap(data::getBuildingById).orElse(null);
        if (h == null) return Optional.empty();
        HouseholdData household = entity.getHouseId()
                .flatMap(data::getHouseholdForBuilding).orElse(null);
        int familySize = household != null
                ? Math.max(1, household.getMemberNpcIds().size()) : 1;
        long wallet = entity.getWallet().toBronze();
        long pool = household != null ? household.getPooledWealth() : 0L;
        long combinedWealth = wallet + pool;
        DayPhase curPhase = ScheduleResolver.phaseAt(entity, level.getGameTime());
        NpcHobbyPreference pref = entity.getHobbyPreference();

        for (HomeCraft c : TABLE) {
            if (entity.getSkills().getLevel(c.skill()) < c.minLevel()) continue;

            // Workstation amenity (preference-ordered; empty list = none needed).
            BlockPos pos = null;
            if (!c.amenities().isEmpty()) {
                pos = firstAmenityPos(level, h, c.amenities());
                if (pos == null) continue;
            }

            // Inputs available (generic multi-input).
            if (!hasAllInputs(level, h, c.recipe())) continue;

            // Family need — the recipe's output below the per-member threshold.
            int threshold = familySize * c.perMemberThreshold();
            if (BuildingStorageAccess.countItem(level, h, c.recipe().output()) >= threshold) {
                continue;
            }

            // Motive — economic OR the row's hobby (or bare LEISURE when null).
            boolean economicMotive = combinedWealth < c.economicThreshold();
            boolean hobbyMotive = curPhase == DayPhase.LEISURE && (c.hobbyId() == null
                    || (pref.hasCurrent() && c.hobbyId().equals(pref.currentHobby())));
            if (!economicMotive && !hobbyMotive) continue;

            // First qualifying row wins (original sequential resolution).
            return Optional.of(new Plan(h, pos, c.recipe(), c.skill(),
                    c.xpPerBatch(), c.activityLabel()));
        }
        return Optional.empty();
    }
}
