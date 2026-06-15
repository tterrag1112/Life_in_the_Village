package tterrag1112.life_in_the_village.Npc.Tasks.Producer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Tasks.Assignment;
import tterrag1112.life_in_the_village.Npc.Tasks.IssuerRef;
import tterrag1112.life_in_the_village.Npc.Tasks.LevelKind;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Priority;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskBoard;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskFilter;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskId;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSavedData;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskSource;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The generic producer task source. Generalized from the blacksmith pilot's
 * {@code BlacksmithTaskSource}; parameterized by a {@link ProductionTaskSpec}
 * so adding a production profession is data, not a new source class.
 *
 * <h3>What it generates, on the dispatcher's lazy refresh cadence</h3>
 * <ul>
 *   <li><b>Finals</b> &mdash; one {@code ProvideItem(output, target)} per final
 *       output under its target, NORMAL tier, urgency from the deficit. The
 *       target is the village demand figure for that item (from
 *       {@link VillageDemand#targetsFor}) when the item is tracked, or the
 *       spec's hardcoded {@link ProductionTaskSpec#quota} when it is not.
 *       These outrank the intermediate reserve so production is primary.</li>
 *   <li><b>Intermediates</b> &mdash; one {@code MaintainStock(item, quota,
 *       buffer)} per self-produced intermediate that has dropped below its
 *       refill trigger (quota &minus; buffer), LOW tier. Intermediates are
 *       not tracked by village demand and always use the spec's hardcoded
 *       quota. Otherwise iron-style intermediates are obtained lazily as a
 *       dependency of a final (see {@link CraftOutputFulfillment}), so the
 *       reserve never gates production.</li>
 *   <li><b>Surplus</b> &mdash; one {@code SellSurplus(item)} per sellable output
 *       whose stock exceeds the spec quota + buffer, LOW tier with the lowest
 *       urgency, so selling only runs once production + acquisition are
 *       satisfied. Surplus headroom is relative to the hardcoded spec quota,
 *       not demand, so the sell window is stable.</li>
 * </ul>
 *
 * <p>All tasks use STABLE ids ({@link ProductionTaskIds}) so a refresh updates
 * priority in place rather than piling duplicates; satisfied tasks are removed
 * unless claimed / in flight.</p>
 */
public final class ProductionTaskSource implements TaskSource {

    private final ProductionTaskSpec spec;
    private final IssuerRef issuer;
    private final Building workBuilding;

    private ProductionTaskSource(ProductionTaskSpec spec, IssuerRef issuer, Building workBuilding) {
        this.spec = spec;
        this.issuer = issuer;
        this.workBuilding = workBuilding;
    }

    /**
     * Resolve the source for {@code npc} under {@code spec}, or empty if the NPC
     * is the wrong profession or has no assigned building of the spec's type.
     */
    public static Optional<ProductionTaskSource> forNpc(ServerLevel level, TownspersonMob npc,
                                                        ProductionTaskSpec spec) {
        if (npc.getProfession() != spec.profession()) return Optional.empty();
        Building b = ProductionHelpers
                .findAssignedBuilding(npc, level, spec.buildingType())
                .orElse(null);
        if (b == null) return Optional.empty();
        return Optional.of(new ProductionTaskSource(spec, resolveIssuer(npc), b));
    }

    /**
     * The board the NPC both reads (via {@link TaskContext#memberships()}) and
     * this source writes: the BUSINESS board when the NPC has a business id,
     * else its own personal NPC board. Never null. (Identical resolution to the
     * blacksmith pilot.)
     */
    static IssuerRef resolveIssuer(TownspersonMob npc) {
        UUID businessId = npc.getBusinessId().orElse(null);
        if (businessId != null) return new IssuerRef(LevelKind.BUSINESS, businessId);
        return new IssuerRef(LevelKind.NPC, npc.getUUID());
    }

    public IssuerRef issuer() { return issuer; }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.board(issuer);

        // ── Resolve village demand targets for finals (T5a) ───────────────────
        // Resolve the acting NPC's village once, before the finals loop.
        // Primary: assignedVillageName → getVillageByName.
        // Fallback: getVillageAt(blockPosition).
        // If no village resolves, demandTargets is empty and every final falls
        // back to its hardcoded spec quota — identical to pre-T5a behaviour.
        TownspersonMob actor = ctx.npc().orElse(null);
        Map<Item, Integer> demandTargets = Map.of();
        if (actor != null) {
            VillageSavedData vdata = VillageSavedData.get(level);
            Village village = actor.getAssignedVillageName()
                    .flatMap(vdata::getVillageByName)
                    .or(() -> vdata.getVillageAt(actor.blockPosition()))
                    .orElse(null);
            if (village != null) {
                demandTargets = VillageDemand.targetsFor(level, village, vdata);
            }
        }

        // ── Primary: one ProvideItem(final) per under-target final, NORMAL ────
        // Skill-aware (Blacksmith-skill-fix): only emit a ProvideItem for a
        // final the worker can actually make right now (skill gate only — the
        // lazy-Acquire path still handles missing inputs). The board therefore
        // EXPANDS as the worker levels up rather than carrying permanently
        // unfulfillable tasks. Default meetsSkillFor=true makes this a no-op
        // for specs whose finals are not skill-gated. (npc is always present
        // for an NPC-backed source; the null-guard preserves old behavior for
        // any future player-backed context.)
        //
        // T5a: the target for each final is:
        //   demand map value  — when the item is tracked by VillageDemand
        //   spec.quota(out)   — fallback for items not in the demand map
        // This means food staples, building materials, seeds, and profession-
        // driven tools (iron_sword/pickaxe/hoe) get population-scaled targets;
        // everything else (armor, candles, carpets, …) keeps its hardcoded quota.
        for (Item out : spec.finalOutputs()) {
            int demandVal = demandTargets.getOrDefault(out, -1);
            int target = (demandVal >= 0) ? demandVal : spec.quota(out);
            if (target <= 0) continue;
            TaskId id = ProductionTaskIds.stable(issuer, "provide:" + ProductionTaskIds.key(out));
            if (actor != null && !spec.meetsSkillFor(level, actor, out)) {
                // Under-skilled for this final right now — drop the task (unless
                // claimed / in flight) so the board reflects what's makeable.
                removeIfUnclaimed(board, id, taskData);
                continue;
            }
            int stock = BuildingStorageAccess.countItem(level, workBuilding, out);
            int deficit = target - stock;
            if (deficit <= 0) {
                removeIfUnclaimed(board, id, taskData);
                continue;
            }
            float urgency = Mth.clamp((float) deficit / target, 0f, 1f);
            upsert(board, taskData, id, new Objective.ProvideItem(out, target),
                    new Priority(TaskPriority.NORMAL, urgency));
        }

        // ── Reserve: MaintainStock(intermediate), LOW (never a gate) ─────────
        // Intermediates (iron ingots, flour, wax, …) are not tracked by village
        // demand; they always use the spec's hardcoded quota.
        for (Item inter : spec.intermediateOutputs()) {
            int target = spec.quota(inter);
            if (target <= 0) continue;
            int buffer = spec.buffer(inter);
            TaskId id = ProductionTaskIds.stable(issuer, "maintain:" + ProductionTaskIds.key(inter));
            int stock = BuildingStorageAccess.countItem(level, workBuilding, inter);
            if (stock < target - buffer) {
                float urgency = Mth.clamp((float) (target - stock) / target, 0f, 1f);
                upsert(board, taskData, id, new Objective.MaintainStock(inter, target, buffer),
                        new Priority(TaskPriority.LOW, urgency));
            } else {
                removeIfUnclaimed(board, id, taskData);
            }
        }

        // ── Surplus: SellSurplus(item) per output over quota+buffer, lowest ──
        // Surplus headroom is relative to the hardcoded spec quota (not demand)
        // so the sell window is stable and doesn't balloon with population.
        for (Item out : spec.sellableOutputs()) {
            int quota = spec.quota(out);
            int keep = quota + Math.max(0, spec.buffer(out));
            int stock = BuildingStorageAccess.countItem(level, workBuilding, out);
            TaskId id = ProductionTaskIds.stable(issuer, "sell:" + ProductionTaskIds.key(out));
            if (quota > 0 && stock > keep) {
                // LOW tier, urgency 0 so it sorts below every production /
                // acquisition task: surplus is sold only once everything else
                // is satisfied ("if all tasks are done, sell the extra").
                upsert(board, taskData, id, new Objective.SellSurplus(out),
                        new Priority(TaskPriority.LOW, 0f));
            } else {
                removeIfUnclaimed(board, id, taskData);
            }
        }
    }

    // ── Upsert / remove helpers (verbatim from the blacksmith pilot) ─────────

    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        Objective obj, Priority priority) {
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            existing.get().setPriority(priority);
            data.markChanged();
            return;
        }
        Task t = new Task(id, issuer, obj, priority, TaskFilter.ANY,
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
