package tterrag1112.life_in_the_village.Npc.Tasks.Business;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor;
import tterrag1112.life_in_the_village.Npc.Tasks.Fulfillment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Village.AmenityType;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PB2 — the craft fulfillment for business-issued {@link Objective.ProvideItem}
 * tasks. Handles the COMPANY_WORKER case that
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.Producer.CraftOutputFulfillment
 * CraftOutputFulfillment} cannot: a profession-agnostic crafting path scoped
 * exclusively to business-issued tasks.
 *
 * <h3>Mutual exclusivity with CraftOutputFulfillment</h3>
 * {@code CraftOutputFulfillment} gates on {@code npc.getProfession() ==
 * spec.profession()}, where every registered spec is keyed to a real craft
 * profession — never COMPANY_WORKER. So a COMPANY_WORKER NPC can never satisfy
 * {@code CraftOutputFulfillment.canFulfill}; the two are strictly partitioned
 * on profession. A BLACKSMITH employee hired into a business CAN see both: if
 * the task was business-issued ({@code issuer.level()==BUSINESS}), this
 * fulfillment wins the score when it is higher; if it was village/NPC issued,
 * CraftOutputFulfillment wins. The {@link Assignment} claim is exclusive (one
 * claimant), so no double-execution risk.
 *
 * <h3>canFulfill gates</h3>
 * <ol>
 *   <li>Objective is ProvideItem.</li>
 *   <li>Task issuer is a BUSINESS board.</li>
 *   <li>Acting NPC has a {@code businessId} matching the task's issuer.</li>
 *   <li>A recipe exists in {@link tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes
 *       SkillRecipes} for the goal item.</li>
 *   <li>The NPC meets the recipe's skill requirement.</li>
 *   <li>A workstation for the recipe's first amenity is present in the
 *       assigned building (same check as {@code craftPlan}).</li>
 * </ol>
 *
 * <h3>executor</h3>
 * Builds a {@link Plan} (9-arg shape: batch 1, multipliers=true, ledger=true,
 * no fuel) and drives {@link ContextPlanExecutor} — reusing the shared
 * production state machine verbatim. Inputs are consumed from and output
 * deposited into the business building storage.
 */
public final class BusinessCraftFulfillment implements Fulfillment {

    // Score constant: sufficient to be the sole craft path for COMPANY_WORKER
    // business tasks. For BLACKSMITH employees on a business task this competes
    // with CraftOutputFulfillment (score 10.0); a slightly lower score here
    // lets the profession-specialized path win for multi-spec employees (they
    // have a richer plan from their own spec). COMPANY_WORKER always uses this.
    private static final double SCORE = 9.0;

    @Override
    public boolean canFulfill(Task task, TaskActor actor, TaskContext ctx) {
        if (!(task.objective() instanceof Objective.ProvideItem provide)) return false;

        // Task must be business-issued.
        IssuerRef issuer = task.issuer();
        if (issuer.level() != LevelKind.BUSINESS) return false;

        // Acting NPC must have a businessId matching this task's business.
        TownspersonMob npc = ctx.npc().orElse(null);
        if (npc == null) return false;
        UUID npcBizId = npc.getBusinessId().orElse(null);
        if (npcBizId == null || !npcBizId.equals(issuer.id())) return false;

        // Recipe must exist in SkillRecipes.
        ProductionRecipe recipe = BusinessProductionTaskSource.recipeFor(provide.item());
        if (recipe == null) return false;

        // NPC must meet the recipe's skill requirements.
        for (Map.Entry<Skill, Integer> req : recipe.skillRequirements().entrySet()) {
            if (ctx.skillLevel(req.getKey()) < req.getValue()) return false;
        }

        // A workstation must be present in the assigned building.
        ServerLevel level = ctx.level();
        Building building = resolveBuilding(level, npc).orElse(null);
        if (building == null) return false;
        return findStation(level, building, recipe) != null;
    }

    @Override
    public double score(Task task, TaskActor actor, TaskContext ctx) {
        return SCORE;
    }

    @Override
    public TaskExecutor executor() {
        return new ContextPlanExecutor((level, npc, task) -> {
            if (!(task.objective() instanceof Objective.ProvideItem provide)) {
                return Optional.empty();
            }
            Building building = resolveBuilding(level, npc).orElse(null);
            if (building == null) return Optional.empty();

            ProductionRecipe recipe = BusinessProductionTaskSource.recipeFor(provide.item());
            if (recipe == null) return Optional.empty();

            BlockPos station = findStation(level, building, recipe);
            if (station == null) return Optional.empty();

            // Derive the production skill from the recipe's primary requirement,
            // falling back to CRAFTING if none is specified.
            Skill skill = primarySkill(recipe);
            int xp = 5; // modest flat XP per batch — no spec-defined table yet

            // 9-arg Plan: batch=1, multipliers=true, ledger=true, no fuel.
            // Inputs consumed from + output deposited into the business building.
            return Optional.of(new Plan(building, station, recipe, skill, xp,
                    "Crafting " + provide.item().getName().getString(),
                    1, true, true));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the building the NPC is assigned to (the business's production
     * building). Reads from the NPC's {@code assignedBuildingId} memory, which
     * the {@code BusinessWorkerBehavior} start gate already validated.
     * Falls back to an empty Optional if the building is gone.
     */
    private static Optional<Building> resolveBuilding(ServerLevel level, TownspersonMob npc) {
        return npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id));
    }

    /**
     * Finds a usable workstation for {@code recipe} in {@code building}.
     * Tries common amenity types in priority order: CRAFTING_TABLE, ANVIL,
     * FURNACE. Returns the first found position, or null if none.
     *
     * <p>This is a best-effort heuristic for PB2; a proper recipe→amenity
     * mapping belongs in a future recipe enrichment pass (PB3 or later).</p>
     */
    private static BlockPos findStation(ServerLevel level, Building building,
                                        ProductionRecipe recipe) {
        // Try CRAFTING_TABLE first (most recipes), then ANVIL (smithing),
        // then FURNACE (smelting). The first present amenity wins.
        for (AmenityType type : List.of(
                AmenityType.CRAFTING_TABLE,
                AmenityType.ANVIL,
                AmenityType.FURNACE)) {
            BlockPos pos = AmenityType.firstPresent(level, building, List.of(type));
            if (pos != null) return pos;
        }
        // No known workstation — craft in-place (null workstationPos is accepted
        // by ContextProductionBehavior; it skips the walk-to-workstation phase).
        return null;
    }

    /**
     * The primary skill to award XP for (and to use as the activity skill
     * label). Returns the first key from the recipe's skill requirements, or
     * a sensible default.
     */
    private static Skill primarySkill(ProductionRecipe recipe) {
        if (!recipe.skillRequirements().isEmpty()) {
            return recipe.skillRequirements().keySet().iterator().next();
        }
        // Ungated recipe — default to CRAFTING which every NPC has.
        return Skill.CRAFTING;
    }
}
