package tterrag1112.life_in_the_village.Npc.Tasks.Monk;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Homestead.ContextProductionBehavior.Plan;
import tterrag1112.life_in_the_village.Npc.Religion.BuildingFaith;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts.MonasticCraft;
import tterrag1112.life_in_the_village.Npc.Religion.ScriptureFactory;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor.AdapterFactory;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextPlanExecutor.PlanFn;
import tterrag1112.life_in_the_village.Npc.Tasks.Execution.ContextProductionAdapter;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;
import tterrag1112.life_in_the_village.Village.Economy.Resources.SkillRecipes;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * G5a — executor for a {@code monastic_craft} task.
 *
 * <p>Ports {@code MonkProductionBehavior.selectPlan} and the
 * {@code producedStack} scripture override into the Task System.
 * Delegates the walk→produce→consume→deposit→XP state machine to
 * {@link ContextPlanExecutor} driven via a scripture-aware
 * {@link ContextProductionAdapter} subclass.</p>
 *
 * <h3>Plan selection (behavior-faithful port of selectPlan:39-72)</h3>
 * {@link #buildPlan} scans {@code MonasticCrafts.CRAFTS} in order, applying
 * the same gates (skill ≥ minLevel, amenity supported, inputs on hand, need
 * &gt; 0), and picks the craft with the greatest store shortfall (quota − stock),
 * using strictly-greater comparison for stable tiebreak. Returns a legacy
 * 6-arg {@link Plan} (batch=1, no multipliers, no ledger) so the deposit path
 * in {@code ContextProductionBehavior} runs byte-identically to the original.
 *
 * <h3>Scripture override (behavior-faithful port of producedStack:80-95 / D3)</h3>
 * For {@code COPY_MANUSCRIPT}: if the monastery's village has a resolvable
 * faith, produce the faith scripture ({@link ScriptureFactory#scriptureStack})
 * instead of the plain book. Injected via the {@link AdapterFactory} hook in
 * {@link ContextPlanExecutor} as a {@link ScriptureAdapter} that overrides
 * {@code producedStack}.
 *
 * <h3>Monastery-store destination</h3>
 * The 6-arg {@link Plan} sets {@code building = monastery}; since
 * {@code ContextProductionBehavior.outputBuilding} defaults to
 * {@code plan.building()}, items deposit to the monastery store — same as
 * the legacy behavior.
 */
public final class MonkCraftExecutor implements TaskExecutor {

    private ContextPlanExecutor delegate;

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        if (delegate == null) {
            delegate = new ContextPlanExecutor(new MonkPlanFn(), SCRIPTURE_FACTORY);
        }
        return delegate.tick(task, actor, ctx);
    }

    // ── Plan selection ────────────────────────────────────────────────────────

    private static final class MonkPlanFn implements PlanFn {
        @Override
        public Optional<Plan> build(ServerLevel level, TownspersonMob npc, Task task) {
            if (!(task.objective() instanceof Objective.PerformService)) return Optional.empty();
            return buildPlan(level, npc);
        }
    }

    /**
     * Faithful port of {@code MonkProductionBehavior.selectPlan}.
     * Resolves the monastery, scans CRAFTS in stable order, and returns
     * the Plan for the craft with the greatest need, or empty if none.
     */
    static Optional<Plan> buildPlan(ServerLevel level, TownspersonMob monk) {
        Building monastery = monk.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .orElse(null);
        if (monastery == null) return Optional.empty();

        MonasticCraft best     = null;
        BlockPos      bestPos  = null;
        int           bestNeed = 0;

        for (MonasticCraft c : MonasticCrafts.CRAFTS) {
            if (monk.getSkills().getLevel(c.skill()) < c.minLevel()) continue;

            BlockPos pos = null;
            if (!c.amenities().isEmpty()) {
                pos = MonasticCrafts.amenityPos(level, monastery, c);
                if (pos == null) continue;         // amenity required but absent
            }
            if (!hasAllInputs(level, monastery, c)) continue;

            int need = MonasticCrafts.need(level, monastery, c);
            if (need <= 0) continue;
            if (need > bestNeed) {                 // strictly-greater: stable scan
                bestNeed = need;
                best     = c;
                bestPos  = pos;
            }
        }
        if (best == null) return Optional.empty();

        // 6-arg Plan: batch=1, no multipliers, no ledger.
        // building = monastery → deposit goes to monastery store.
        return Optional.of(new Plan(monastery, bestPos, best.recipe(),
                best.skill(), best.xpPerBatch(), best.activityLabel()));
    }

    private static boolean hasAllInputs(ServerLevel level, Building monastery, MonasticCraft c) {
        for (var e : c.recipe().inputs().entrySet()) {
            if (BuildingStorageAccess.countItem(level, monastery, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ── Scripture-aware adapter ───────────────────────────────────────────────

    /**
     * An {@link AdapterFactory} that produces a {@link ScriptureAdapter} —
     * a subclass of {@link ContextProductionAdapter} that overrides
     * {@code producedStack} to emit the monastery's faith scripture for
     * {@code COPY_MANUSCRIPT}.
     */
    private static final AdapterFactory SCRIPTURE_FACTORY =
            (planSupplier, level, npc) -> new ScriptureAdapter(planSupplier, level, npc);

    /**
     * Subclass of {@link ContextProductionAdapter} that overrides
     * {@code producedStack} to port the D3 scripture override from
     * {@code MonkProductionBehavior.producedStack}:
     *
     * <p>When the recipe is {@link SkillRecipes#COPY_MANUSCRIPT} and the
     * monastery's village has a resolvable faith, produce the scripture
     * {@link ItemStack} from {@link ScriptureFactory#scriptureStack}.
     * Every other craft (and a monastery with no resolvable faith) falls
     * back to the default plain output — byte-identical to the original.</p>
     */
    private static final class ScriptureAdapter extends ContextProductionAdapter {

        private final ServerLevel    level;
        private final TownspersonMob monk;

        ScriptureAdapter(Supplier<Optional<Plan>> planSupplier,
                         ServerLevel level, TownspersonMob monk) {
            super(planSupplier);
            this.level = level;
            this.monk  = monk;
        }

        @Override
        protected ItemStack producedStack(ServerLevel lvl, TownspersonMob entity,
                                          Building building, ProductionRecipe recipe) {
            // D3 scripture override: faithful port of MonkProductionBehavior.producedStack
            if (recipe == SkillRecipes.COPY_MANUSCRIPT) {
                Village village = monk.getAssignedVillageName()
                        .flatMap(n -> VillageSavedData.get(level).getVillageByName(n))
                        .orElse(null);
                String faith = village == null ? null
                        : BuildingFaith.resolveFaith(level, village, building);
                if (faith != null) {
                    Optional<ItemStack> scripture = ScriptureFactory.scriptureStack(
                            level, faith, Optional.of(monk.getUUID()), level.getGameTime());
                    if (scripture.isPresent()) return scripture.get();
                }
            }
            return super.producedStack(lvl, entity, building, recipe);
        }
    }
}
