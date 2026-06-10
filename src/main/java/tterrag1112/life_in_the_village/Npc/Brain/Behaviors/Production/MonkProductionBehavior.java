package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts.MonasticCraft;
import tterrag1112.life_in_the_village.Npc.Religion.ScriptureFactory;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Religion Rework R6b/R6c — the MONASTERY production context: the monk's WORK
 * behavior. A second context over the shared {@link ContextProductionBehavior}
 * primitive — "skills = what you CAN do; context = what you DO with them".
 *
 * <p>During its work schedule the monk produces, from its <b>developed skills</b>
 * that the <b>monastery's amenities</b> support, the craft the monastery <b>most
 * needs</b> (R6c need-priority: among the skilled + amenity-supported + in-stock
 * options below their target, the one with the greatest store shortfall),
 * depositing to the monastery shared store and gaining the skill XP. A monk with
 * no matching skill/amenity produces nothing yet — {@link
 * tterrag1112.life_in_the_village.Npc.Religion.MonasteryDeveloper} steers idle
 * initiates toward needed, supported crafts over time.</p>
 *
 * <p>The craft definitions live in {@link MonasticCrafts} (shared with the
 * developer). Crafts are by SKILL, never a fixed "monk makes X" list.</p>
 */
public class MonkProductionBehavior extends ContextProductionBehavior {

    @Override
    protected Optional<Plan> selectPlan(ServerLevel level, TownspersonMob entity) {
        Building monastery = entity.getAssignedBuildingId()
                .flatMap(VillageSavedData.get(level)::getBuildingById).orElse(null);
        if (monastery == null) return Optional.empty();

        // Among the monk's skilled + amenity-supported + below-target crafts whose
        // inputs are on hand, prefer the one the monastery MOST needs (greatest
        // store shortfall). CRAFTS order breaks ties (stable scan).
        MonasticCraft best = null;
        BlockPos bestPos = null;
        int bestNeed = 0;
        for (MonasticCraft c : MonasticCrafts.CRAFTS) {
            if (entity.getSkills().getLevel(c.skill()) < c.minLevel()) continue;

            BlockPos pos = null;
            if (!c.amenities().isEmpty()) {
                pos = MonasticCrafts.amenityPos(level, monastery, c);
                if (pos == null) continue;
            }
            if (!hasAllInputs(level, monastery, c.recipe())) continue;

            int need = MonasticCrafts.need(level, monastery, c); // quota − stock
            if (need <= 0) continue;                              // monastery wants it?
            if (need > bestNeed) {                                // strictly-greater: stable
                bestNeed = need;
                best = c;
                bestPos = pos;
            }
        }
        if (best == null) return Optional.empty();
        return Optional.of(new Plan(monastery, bestPos, best.recipe(), best.skill(),
                best.xpPerBatch(), best.activityLabel()));
    }

    /**
     * D3 — a monk copying a manuscript (COPY_MANUSCRIPT) produces the monastery's
     * faith <b>scripture</b> (a readable WRITTEN_BOOK) into the store, rather than
     * a plain BOOK — monks copying scripture, on-theme. Every other craft (and a
     * monastery with no resolvable faith) falls back to the default plain output.
     */
    @Override
    protected ItemStack producedStack(ServerLevel level, TownspersonMob entity,
                                      Building building, ProductionRecipe recipe) {
        if (recipe == SkillRecipes.COPY_MANUSCRIPT) {
            Village village = entity.getAssignedVillageName()
                    .flatMap(VillageSavedData.get(level)::getVillageByName).orElse(null);
            String faith = village == null ? null
                    : BuildingFaith.resolveFaith(level, village, building);
            if (faith != null) {
                Optional<ItemStack> scripture = ScriptureFactory.scriptureStack(
                        level, faith, Optional.of(entity.getUUID()), level.getGameTime());
                if (scripture.isPresent()) return scripture.get();
            }
        }
        return super.producedStack(level, entity, building, recipe);
    }
}
