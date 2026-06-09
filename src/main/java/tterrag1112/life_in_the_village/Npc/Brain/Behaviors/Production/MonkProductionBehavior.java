package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.List;
import java.util.Optional;

/**
 * Religion Rework R6b — the MONASTERY production context: the monk's WORK
 * behavior (replacing the R6a placeholder). A second context over the shared
 * {@link ContextProductionBehavior} primitive — proving the M1/M2 "skills = what
 * you CAN do; context = what you DO with them" design generalizes.
 *
 * <p>During its work schedule the monk produces, from its <b>developed skills</b>
 * that the <b>monastery's amenities</b> support, picking the first craft (skill ≥
 * level + amenity present + inputs available + the monastery wants the good —
 * lightly need-gated by a per-craft quota), running its {@link SkillRecipes}
 * recipe, and depositing to the <b>monastery shared store</b> (the monk's
 * assigned building), gaining the skill XP. A monk with no matching skill/amenity
 * simply produces nothing yet — R6c develops its skills and adds need-driven
 * assignment + mealtime distribution + the monastery economy.</p>
 *
 * <p>Crafts are by SKILL, never a fixed "monk makes X" list. This phase's rows
 * all use EXISTING skills (candles/honey/books/herbal); mead awaits a BREWING
 * skill (deferred — a new {@code Skill} value needs its own exhaustive-switch +
 * cascade sweep, not warranted for one craft here).</p>
 */
public class MonkProductionBehavior extends ContextProductionBehavior {

    /** One monastic craft. The monastery "wants" the good while its stock is
     *  below {@code monasteryQuota} (the light need-gate; need-driven assignment
     *  is R6c). {@code amenities} empty = no workstation. */
    private record MonasticCraft(Skill skill, int minLevel, List<AmenityType> amenities,
                                 ProductionRecipe recipe, int monasteryQuota,
                                 int xpPerBatch, String activityLabel) {}

    /** The monastic crafts. By skill + amenity — a monk only makes what its
     *  skills AND the monastery's facilities support. */
    private static final List<MonasticCraft> TABLE = List.of(
            new MonasticCraft(Skill.CANDLEMAKING, 1, List.of(),
                    SkillRecipes.MAKE_CANDLE, 16, 2, "Making candles"),
            new MonasticCraft(Skill.BEEKEEPING, 1, List.of(AmenityType.APIARY),
                    SkillRecipes.HARVEST_HONEY, 16, 2, "Tending the apiary"),
            new MonasticCraft(Skill.LITERACY, 1, List.of(AmenityType.LECTERN),
                    SkillRecipes.COPY_MANUSCRIPT, 8, 2, "Copying a manuscript"),
            new MonasticCraft(Skill.VILLAGE_MEDICINE, 1, List.of(AmenityType.BREWING_STAND),
                    SkillRecipes.BREW_TONIC, 8, 2, "Brewing a tonic"));

    @Override
    protected Optional<Plan> selectPlan(ServerLevel level, TownspersonMob entity) {
        Building monastery = entity.getAssignedBuildingId()
                .flatMap(VillageSavedData.get(level)::getBuildingById).orElse(null);
        if (monastery == null) return Optional.empty();

        for (MonasticCraft c : TABLE) {
            if (entity.getSkills().getLevel(c.skill()) < c.minLevel()) continue;

            // Workstation amenity (the monastery must support the craft).
            BlockPos pos = null;
            if (!c.amenities().isEmpty()) {
                pos = firstAmenityPos(level, monastery, c.amenities());
                if (pos == null) continue;
            }

            // Inputs available in the monastery store (generic multi-input).
            if (!hasAllInputs(level, monastery, c.recipe())) continue;

            // Motive — the monastery wants the good (stock below its quota).
            if (BuildingStorageAccess.countItem(level, monastery, c.recipe().output())
                    >= c.monasteryQuota()) continue;

            return Optional.of(new Plan(monastery, pos, c.recipe(), c.skill(),
                    c.xpPerBatch(), c.activityLabel()));
        }
        return Optional.empty();
    }
}
