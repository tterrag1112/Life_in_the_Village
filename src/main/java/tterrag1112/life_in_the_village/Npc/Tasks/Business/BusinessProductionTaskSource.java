package tterrag1112.life_in_the_village.Npc.Tasks.Business;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Priority;
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskIds;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskFilter;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskId;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSource;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PB2 — task source for business-driven production dispatch.
 *
 * <p>Runs during {@link tterrag1112.life_in_the_village.Npc.Tasks.DoTaskBehavior
 * DoTaskBehavior.refreshSources} for every COMPANY_WORKER NPC. For each
 * PRODUCER-role worker in the resolved business that has a non-empty
 * {@code assignedItemId} with a craftable recipe in {@link SkillRecipes},
 * this source upserts one {@link Objective.ProvideItem} task onto the
 * business board with:
 * <ul>
 *   <li>A STABLE id (idempotent: multiple workers triggering refreshes never
 *       create duplicates for the same goal item).</li>
 *   <li>A {@link TaskFilter} carrying the recipe's primary skill + minimum
 *       level, so only a sufficiently-skilled employee can claim it.</li>
 *   <li>Urgency proportional to the stock deficit vs. dailyTargetCount.</li>
 * </ul>
 *
 * <p>When a worker's goal is cleared (empty assignedItemId) or the item has
 * no recipe, the task is removed via {@code removeIfUnclaimed} so the board
 * stays clean. Already-claimed / in-flight tasks are never dropped.</p>
 *
 * <h3>Issuer</h3>
 * Always {@code IssuerRef(BUSINESS, businessId)}: this source only runs for
 * COMPANY_WORKERs that have a resolved business, and business-scope tasks
 * are exactly what those workers see via
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.TaskContext#memberships()}.
 *
 * <h3>Inputs-missing behaviour</h3>
 * The source emits the task unconditionally (stock vs. target only); the
 * {@link BusinessCraftFulfillment#canFulfill} gate re-checks stock at
 * claim time. If inputs are missing, {@code ContextPlanExecutor} will
 * return FAILED at execution start, releasing the claim so the task
 * becomes re-claimable. Recursive input resolution is deferred to PB4.
 */
public final class BusinessProductionTaskSource implements TaskSource {

    private final IssuerRef issuer;
    private final Business  business;
    private final Building  workBuilding;

    private BusinessProductionTaskSource(IssuerRef issuer, Business business,
                                         Building workBuilding) {
        this.issuer      = issuer;
        this.business    = business;
        this.workBuilding = workBuilding;
    }

    /**
     * Resolve the source for {@code npc}, or empty if:
     * <ul>
     *   <li>the NPC is not a COMPANY_WORKER;</li>
     *   <li>no business record is found for the NPC's businessId;</li>
     *   <li>the NPC's {@code assignedBuildingId} does not resolve to a Building.</li>
     * </ul>
     */
    public static Optional<BusinessProductionTaskSource> forNpc(ServerLevel level,
                                                                 TownspersonMob npc) {
        if (npc.getProfession() != Profession.COMPANY_WORKER) return Optional.empty();
        UUID bizId = npc.getBusinessId().orElse(null);
        if (bizId == null) return Optional.empty();

        BusinessSavedData cdata = BusinessSavedData.get(level);
        Business biz = cdata.getBusinessForWorker(npc.getUUID()).orElse(null);
        if (biz == null) return Optional.empty();

        // Resolve the worker's assigned building (deposit target + stock count).
        Business.BusinessWorker worker = biz.getWorker(npc.getUUID()).orElse(null);
        if (worker == null) return Optional.empty();

        Building building = VillageSavedData.get(level)
                .getBuildingById(worker.assignedBuildingId())
                .orElse(null);
        if (building == null) return Optional.empty();

        IssuerRef ref = new IssuerRef(LevelKind.BUSINESS, bizId);
        return Optional.of(new BusinessProductionTaskSource(ref, biz, building));
    }

    /** Convenience entry point called by {@code DoTaskBehavior.refreshSources}. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.board(issuer);

        // Iterate every PRODUCER worker in the business and emit one ProvideItem
        // task per non-empty, recipe-backed goal item. Stable ids ensure multiple
        // PRODUCER workers with the same goal item produce exactly one task on
        // the board (subsequent refreshes upsert priority in place).
        for (Business.BusinessWorker w : business.getWorkers()) {
            if (w.role() != Business.WorkerRole.PRODUCER) continue;
            if (w.assignedItemId().isEmpty()) continue;

            Item goalItem = resolveItem(w.assignedItemId());
            if (goalItem == null) continue;

            int target = Math.max(1, w.dailyTargetCount());
            TaskId id = ProductionTaskIds.stable(issuer,
                    "business-produce:" + ProductionTaskIds.key(goalItem));

            ProductionRecipe recipe = recipeFor(goalItem);
            if (recipe == null) {
                // No recipe — remove stale task and idle. GUI-picker-vs-recipe
                // mismatch validation is PB3.
                removeIfUnclaimed(board, id, taskData);
                continue;
            }

            // Skill gate carried on the task filter so under-skilled employees
            // cannot claim it.
            TaskFilter filter = skillFilterFor(recipe);

            // Urgency: stock deficit relative to target, clamped to [0,1].
            int stock = BuildingStorageAccess.countItem(level, workBuilding, goalItem);
            int deficit = target - stock;
            if (deficit <= 0) {
                removeIfUnclaimed(board, id, taskData);
                continue;
            }
            float urgency = Mth.clamp((float) deficit / target, 0f, 1f);

            upsert(board, taskData, id,
                    new Objective.ProvideItem(goalItem, target),
                    new Priority(TaskPriority.NORMAL, urgency),
                    filter);
        }
    }

    // ── Recipe helpers ────────────────────────────────────────────────────────

    /**
     * Finds the first {@link ProductionRecipe} in {@link SkillRecipes} whose
     * output matches {@code item}, scanning all skills. Returns {@code null} if
     * no recipe is registered for this item.
     */
    static ProductionRecipe recipeFor(Item item) {
        for (Skill skill : Skill.values()) {
            for (ProductionRecipe r : SkillRecipes.forSkill(skill)) {
                if (r.output() == item) return r;
            }
        }
        return null;
    }

    /**
     * Resolves an item from its namespaced-id string. Returns {@code null} if
     * blank or unregistered.
     */
    static Item resolveItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        try {
            return BuiltInRegistries.ITEM
                    .get(Identifier.parse(itemId))
                    .map(h -> h.value())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds a {@link TaskFilter} from the recipe's primary skill requirement.
     * Returns {@link TaskFilter#ANY} if the recipe has no skill gate.
     */
    static TaskFilter skillFilterFor(ProductionRecipe recipe) {
        if (recipe.skillRequirements().isEmpty()) return TaskFilter.ANY;
        var entry = recipe.skillRequirements().entrySet().iterator().next();
        return new TaskFilter(
                Optional.of(entry.getKey()),
                entry.getValue(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);
    }

    // ── Upsert / remove helpers (mirrors ProductionTaskSource) ───────────────

    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        Objective obj, Priority priority, TaskFilter filter) {
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            Task t = existing.get();
            if (t.assignment().isTerminal()) {
                board.remove(id);
                // fall through to create a fresh task below
            } else {
                t.setPriority(priority);
                data.markChanged();
                return;
            }
        }
        Task t = new Task(id, issuer, obj, priority, filter,
                new Assignment(), List.of(), 0L, null);
        data.addTask(issuer, t);
    }

    private void removeIfUnclaimed(TaskBoard board, TaskId id, TaskSavedData data) {
        board.get(id).ifPresent(t -> {
            if (t.assignment().claimants().isEmpty()) {
                board.remove(id);
                data.markChanged();
            }
        });
    }
}
