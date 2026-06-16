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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PB2/PB4 — task source for business-driven production dispatch.
 *
 * <h3>PB2 behaviour (single-level, unchanged)</h3>
 * For each PRODUCER-role worker in the business that has a non-empty
 * {@code assignedItemId} with a craftable recipe in {@link SkillRecipes},
 * this source upserts one {@link Objective.ProvideItem} task onto the business
 * board with a stable id, a skill-gated {@link TaskFilter}, and urgency
 * proportional to the stock deficit vs. dailyTargetCount.
 *
 * <h3>PB4 extension — recursive DAG decomposition</h3>
 * When a goal item's recipe has inputs that are themselves craftable (have a
 * recipe in {@link SkillRecipes}) and are not currently satisfied from business
 * storage, this source emits child {@link Objective.ProvideItem} tasks for those
 * inputs and wires them as {@link Task#dependencies()} of the parent. The
 * dependency mechanism in {@link tterrag1112.life_in_the_village.Npc.Tasks.DoTaskBehavior}
 * ensures child nodes execute before the parent.
 *
 * <h3>DAG ordering via {@code Task.dependencies}</h3>
 * {@code DoTaskBehavior.pickTop} skips a task until every dependency task on the
 * board is DONE (or absent — absent deps are considered satisfied). Child tasks
 * complete first (crafted → DONE), the parent becomes claimable, a worker crafts
 * the final output. No new fulfillment is needed: every DAG node is a
 * {@link Objective.ProvideItem} handled by {@link BusinessCraftFulfillment}.
 *
 * <h3>Depth cap and cycle guard (mandatory)</h3>
 * {@link #MAX_DAG_DEPTH} (6) caps the recursion so pathological recipe networks
 * don't overflow the stack. A per-resolution path {@code Set<Item>} detects
 * cycles: if an input's recipe would re-enter an item already on the current
 * resolution path, that input is treated as a raw leaf and no child task is
 * emitted.
 *
 * <h3>Raw leaves</h3>
 * Items with no recipe, or whose recipe is excluded by the depth/cycle guards,
 * are raw leaves. No task is emitted for them; they must be present in business
 * storage. If absent, the parent node's {@link BusinessCraftFulfillment#canFulfill}
 * will fail, and the task shows {@code BLOCKED_MISSING_INPUTS} in the PB3 GUI.
 *
 * <h3>Idempotency / stable ids</h3>
 * Task ids are deterministic: {@code "business-produce:<goalItem>:<nodeItem>"}.
 * Re-generation updates priority in place (non-terminal task) or replaces
 * terminal tasks with fresh ones. Storage-satisfied inputs have their tasks
 * removed via {@code removeIfUnclaimed}. The DAG converges; no duplicates
 * or thrashing.
 *
 * <h3>Multi-recipe tiebreak</h3>
 * When multiple recipes produce the same output, the reverse index keeps the
 * first one encountered across all skills (skills iterated in {@link Skill}
 * declaration order; within a skill, in registration order). This deterministic
 * first-match matches the PB2 {@code recipeFor} scan order exactly.
 *
 * <h3>Issuer</h3>
 * Always {@code IssuerRef(BUSINESS, businessId)}: this source only runs for
 * COMPANY_WORKERs that have a resolved business, and business-scope tasks
 * are exactly what those workers see via
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.TaskContext#memberships()}.
 */
public final class BusinessProductionTaskSource implements TaskSource {

    /** Maximum DAG depth (root = depth 0). Guards against deep/runaway recipe chains. */
    static final int MAX_DAG_DEPTH = 6;

    // ── Reverse recipe index (item → recipe) ─────────────────────────────────

    /**
     * Lazy-initialised reverse index from output {@link Item} to the first
     * {@link ProductionRecipe} that produces it, across all skills.
     *
     * <p>Built once on first access (after mod init, when {@link SkillRecipes}
     * is fully populated) and cached statically. Datapack reloads that change
     * the BLACKSMITHING JSON bin would invalidate this cache; for PB4 the JSON
     * bin is stable enough that a static cache is acceptable. A future
     * datapack-reload hook can call {@link #invalidateRecipeIndex()} if needed.</p>
     *
     * <p>Multi-recipe tiebreak: first-match in Skill.values() order, then
     * within-skill registration order. This is deterministic and matches the
     * pre-PB4 {@link #recipeFor} scan order exactly so the GUI picker and
     * diagnostics continue to work unchanged.</p>
     */
    private static volatile Map<Item, ProductionRecipe> recipeIndex;
    private static final Object INDEX_LOCK = new Object();

    static Map<Item, ProductionRecipe> recipeIndex() {
        Map<Item, ProductionRecipe> idx = recipeIndex;
        if (idx != null) return idx;
        synchronized (INDEX_LOCK) {
            idx = recipeIndex;
            if (idx != null) return idx;
            idx = buildRecipeIndex();
            recipeIndex = idx;
        }
        return idx;
    }

    /** Clears the cached reverse index so it is rebuilt on next access.
     *  Call this if SkillRecipes data changes at runtime (e.g. datapack reload). */
    public static void invalidateRecipeIndex() {
        synchronized (INDEX_LOCK) { recipeIndex = null; }
    }

    private static Map<Item, ProductionRecipe> buildRecipeIndex() {
        Map<Item, ProductionRecipe> idx = new LinkedHashMap<>();
        // Primary scan: all skill buckets in Skill.values() order, within-skill
        // in registration order. First-match wins (matches pre-PB4 recipeFor scan).
        for (Skill skill : Skill.values()) {
            for (ProductionRecipe r : SkillRecipes.forSkill(skill)) {
                idx.putIfAbsent(r.output(), r);
            }
        }
        // Supplemental intermediates (PB4): planks→sticks etc. These are not in
        // any skill bucket (intentionally: NPC profession behaviors must not see
        // them as standalone craft goals). Added after the primary scan so a skill
        // recipe for the same output always wins.
        for (ProductionRecipe r : SkillRecipes.productionIntermediates()) {
            idx.putIfAbsent(r.output(), r);
        }
        return Collections.unmodifiableMap(idx);
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private final IssuerRef issuer;
    private final Business  business;
    private final Building  workBuilding;

    private BusinessProductionTaskSource(IssuerRef issuer, Business business,
                                         Building workBuilding) {
        this.issuer       = issuer;
        this.business     = business;
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

    // ── TaskSource.generate ──────────────────────────────────────────────────

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.board(issuer);

        for (Business.BusinessWorker w : business.getWorkers()) {
            if (w.role() != Business.WorkerRole.PRODUCER) continue;
            if (w.assignedItemId().isEmpty()) continue;

            Item goalItem = resolveItem(w.assignedItemId());
            if (goalItem == null) continue;

            int target = Math.max(1, w.dailyTargetCount());

            ProductionRecipe recipe = recipeFor(goalItem);
            if (recipe == null) {
                // No recipe — remove stale goal task and idle.
                TaskId id = goalTaskId(goalItem);
                removeIfUnclaimed(board, id, taskData);
                continue;
            }

            // Check stock vs target.
            int stock = BuildingStorageAccess.countItem(level, workBuilding, goalItem);
            int deficit = target - stock;
            if (deficit <= 0) {
                // Goal satisfied — prune the whole DAG for this goal.
                removeGoalDag(board, taskData, goalItem, recipe);
                continue;
            }

            // PB4: recursively resolve the DAG. The result is a map of
            // nodeItem → list of child TaskIds it depends on, collected
            // bottom-up. We then upsert in topological order (leaves first,
            // root last) so dependencies are present when the root is created.
            Map<Item, List<TaskId>> dagDeps = new LinkedHashMap<>();
            Set<Item> resolutionPath = new HashSet<>();
            resolutionPath.add(goalItem);
            resolveNode(level, board, taskData, goalItem, recipe, target, goalItem,
                        dagDeps, resolutionPath, 0);

            // Upsert root (goal) node last, with its computed children as deps.
            float urgency = Mth.clamp((float) deficit / target, 0f, 1f);
            TaskFilter filter = skillFilterFor(recipe);
            TaskId rootId = goalTaskId(goalItem);
            List<TaskId> rootDeps = dagDeps.getOrDefault(goalItem, List.of());
            upsert(board, taskData, rootId,
                    new Objective.ProvideItem(goalItem, target),
                    new Priority(TaskPriority.NORMAL, urgency),
                    filter, rootDeps);
        }
    }

    // ── PB4 — recursive DAG resolver ─────────────────────────────────────────

    /**
     * Recursively resolves the production DAG for {@code nodeItem}.
     *
     * <p>For each input of {@code nodeRecipe}:
     * <ol>
     *   <li>If business storage already has enough → satisfied; remove any
     *       stale task and don't recurse.</li>
     *   <li>If the input has a recipe AND the depth/cycle guard allows →
     *       emit a child task and recurse into its inputs.</li>
     *   <li>Otherwise (raw leaf or guard triggered) → no task; parent will
     *       show {@code BLOCKED_MISSING_INPUTS} if the item isn't in storage.</li>
     * </ol>
     *
     * <p>The result is accumulated into {@code dagDeps}: nodeItem → list of
     * immediate child TaskIds. Callers must upsert the root node AFTER this
     * method returns so that all child task ids are stable.</p>
     *
     * @param level          server level (for storage queries)
     * @param board          the business task board (read/write)
     * @param taskData       persistence layer (for adds/removes)
     * @param nodeItem       the item being resolved at this level
     * @param nodeRecipe     the recipe producing {@code nodeItem}
     * @param neededQty      how many of {@code nodeItem} the parent needs
     * @param goalItem       the root goal item (for stable id prefix)
     * @param dagDeps        OUT: accumulated nodeItem → child TaskId list
     * @param resolutionPath items on the current resolution path (cycle guard)
     * @param depth          current recursion depth (depth cap)
     */
    private void resolveNode(ServerLevel level, TaskBoard board, TaskSavedData taskData,
                             Item nodeItem, ProductionRecipe nodeRecipe,
                             int neededQty, Item goalItem,
                             Map<Item, List<TaskId>> dagDeps,
                             Set<Item> resolutionPath, int depth) {

        List<TaskId> childIds = new ArrayList<>();

        for (Map.Entry<Item, Integer> inputEntry : nodeRecipe.inputs().entrySet()) {
            Item inputItem    = inputEntry.getKey();
            int  perBatch     = inputEntry.getValue();

            // How many batches of nodeRecipe do we need?
            int batchesNeeded = (int) Math.ceil((double) neededQty / nodeRecipe.outputCount());
            int totalInputNeeded = batchesNeeded * perBatch;

            // Storage check: how much of this input is already in the building?
            int inStorage = BuildingStorageAccess.countItem(level, workBuilding, inputItem);
            int stillNeeded = Math.max(0, totalInputNeeded - inStorage);

            // Stable id for this input child task (scoped to the goal).
            TaskId childId = childTaskId(goalItem, inputItem);

            if (stillNeeded <= 0) {
                // Input satisfied from storage → remove any stale task.
                removeIfUnclaimed(board, childId, taskData);
                continue;
            }

            // Check if this input is craftable (has a recipe) and can be recursed.
            ProductionRecipe inputRecipe = recipeFor(inputItem);

            if (inputRecipe == null
                    || depth >= MAX_DAG_DEPTH
                    || resolutionPath.contains(inputItem)) {
                // Raw leaf (no recipe), depth cap hit, or cycle detected.
                // Do NOT emit a task: the input must come from storage.
                // A stale task for this id (from a prior DAG that was deeper)
                // should be pruned.
                removeIfUnclaimed(board, childId, taskData);
                continue;
            }

            // Emit child task for this craftable input.
            // The child's qty is the number of craft outputs needed, rounded up
            // to the recipe's outputCount so we don't under-produce.
            int childQty = (int) Math.ceil((double) stillNeeded / inputRecipe.outputCount())
                           * inputRecipe.outputCount();
            TaskFilter childFilter = skillFilterFor(inputRecipe);

            // Recurse into child's inputs BEFORE upserting the child, so the
            // child's own dep ids are computed and recorded in dagDeps.
            Set<Item> childPath = new HashSet<>(resolutionPath);
            childPath.add(inputItem);
            resolveNode(level, board, taskData, inputItem, inputRecipe,
                        childQty, goalItem, dagDeps, childPath, depth + 1);

            // Upsert the child task with ITS children as dependencies.
            List<TaskId> grandchildIds = dagDeps.getOrDefault(inputItem, List.of());
            upsert(board, taskData, childId,
                    new Objective.ProvideItem(inputItem, childQty),
                    new Priority(TaskPriority.NORMAL, 1.0f), // max urgency for intermediates
                    childFilter, grandchildIds);

            childIds.add(childId);
        }

        // Record this node's children so the caller can wire them as deps of the parent.
        dagDeps.put(nodeItem, childIds);
    }

    /**
     * Removes the goal task AND all descendant child tasks for {@code goalItem}
     * (as many as the depth cap allows) when the goal is already satisfied by
     * storage. Uses {@code removeIfUnclaimed} so in-flight tasks are never dropped.
     *
     * <p>This is a best-effort pruning pass: it walks the same id space as
     * {@link #resolveNode} and removes any unclaimed tasks it finds. Claimed
     * (in-flight) tasks stay and will be pruned on the next refresh once done.</p>
     */
    private void removeGoalDag(TaskBoard board, TaskSavedData taskData,
                                Item goalItem, ProductionRecipe goalRecipe) {
        removeIfUnclaimed(board, goalTaskId(goalItem), taskData);
        // Prune child tasks up to MAX_DAG_DEPTH. We do a DFS walk of the recipe
        // DAG (same structure as resolveNode) and remove unclaimed nodes.
        pruneDag(board, taskData, goalItem, goalRecipe, new HashSet<>(), 0);
    }

    private void pruneDag(TaskBoard board, TaskSavedData taskData,
                           Item goalItem, ProductionRecipe recipe,
                           Set<Item> visited, int depth) {
        if (depth >= MAX_DAG_DEPTH) return;
        for (Item inputItem : recipe.inputs().keySet()) {
            if (!visited.add(inputItem)) continue; // cycle guard
            removeIfUnclaimed(board, childTaskId(goalItem, inputItem), taskData);
            ProductionRecipe inputRecipe = recipeFor(inputItem);
            if (inputRecipe != null) {
                pruneDag(board, taskData, goalItem, inputRecipe, visited, depth + 1);
            }
        }
    }

    // ── Stable id helpers ────────────────────────────────────────────────────

    /**
     * Stable id for the root goal task of {@code goalItem}.
     * Matches the PB2 id exactly so existing boards are compatible.
     */
    private TaskId goalTaskId(Item goalItem) {
        return ProductionTaskIds.stable(issuer,
                "business-produce:" + ProductionTaskIds.key(goalItem));
    }

    /**
     * Stable id for a child (intermediate) task of {@code inputItem} within
     * the DAG rooted at {@code goalItem}.
     *
     * <p>Scoped to the goal so two different goals that both need sticks get
     * separate child tasks (each with their own qty and deps). The id encodes
     * both the goal and the node so it is globally unique on the board.</p>
     */
    private TaskId childTaskId(Item goalItem, Item inputItem) {
        return ProductionTaskIds.stable(issuer,
                "business-produce:" + ProductionTaskIds.key(goalItem)
                + ":" + ProductionTaskIds.key(inputItem));
    }

    // ── Recipe helpers ────────────────────────────────────────────────────────

    /**
     * Finds the first {@link ProductionRecipe} in {@link SkillRecipes} whose
     * output matches {@code item}, using the PB4 reverse index.
     *
     * <p>Package-private so {@link BusinessCraftFulfillment} and the PB3 GUI
     * ({@code BusinessManagementScreen}) can call it directly — preserving the
     * same contract as before PB4.</p>
     */
    static ProductionRecipe recipeFor(Item item) {
        return recipeIndex().get(item);
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

    // ── Upsert / remove helpers ───────────────────────────────────────────────

    /**
     * Upsert a task onto the board.
     *
     * <ul>
     *   <li>If absent → create fresh with the given deps list.</li>
     *   <li>If present and non-terminal → update priority only.
     *       The deps list is fixed at creation and is stable (same stable ids
     *       each refresh), so it does not need to be re-set.</li>
     *   <li>If present and terminal → remove and recreate with fresh deps.</li>
     * </ul>
     */
    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        Objective obj, Priority priority, TaskFilter filter,
                        List<TaskId> deps) {
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            Task t = existing.get();
            if (t.assignment().isTerminal()) {
                board.remove(id);
                // Fall through to create a fresh task below.
            } else {
                t.setPriority(priority);
                data.markChanged();
                return;
            }
        }
        Task t = new Task(id, issuer, obj, priority, filter,
                new Assignment(), List.copyOf(deps), 0L, null);
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
