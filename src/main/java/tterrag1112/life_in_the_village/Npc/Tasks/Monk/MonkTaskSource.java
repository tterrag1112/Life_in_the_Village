package tterrag1112.life_in_the_village.Npc.Tasks.Monk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts;
import tterrag1112.life_in_the_village.Npc.Religion.MonasticCrafts.MonasticCraft;
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
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * G5a — task source for monastic-craft tasks.
 *
 * <p>On each refresh it:
 * <ol>
 *   <li>Resolves the monk's assigned MONASTERY building.</li>
 *   <li>Checks whether any MonasticCraft is currently runnable (skill ≥
 *       minLevel + amenity supported + store below quota). Uses the same
 *       logic as the legacy {@code MonkProductionBehavior.selectPlan}
 *       need-priority scan, but only as a GATE — the actual craft
 *       selection happens inside {@link MonkCraftExecutor} at execution
 *       time against live world state.</li>
 *   <li>If any craft is available: emits / upserts one
 *       {@link MonkVerb#MONASTIC_CRAFT} {@code PerformService} task with
 *       stable id {@code monastic_craft:monasteryId}, NORMAL tier, and
 *       {@code at} = monastery origin.</li>
 *   <li>If no craft is available: prunes the unclaimed task via
 *       {@link #removeIfUnclaimed}.</li>
 * </ol>
 *
 * <h3>Issuer</h3>
 * BUSINESS if the monk has one, else NPC (mirrors FarmTaskSource /
 * PriestTaskSource pattern). The monastery itself has {@link LevelKind#MONASTERY}
 * in the level kind enum but monks do not yet publish a MONASTERY-keyed
 * membership in {@link tterrag1112.life_in_the_village.Npc.Tasks.TaskContext#memberships};
 * NPC / BUSINESS issuer keeps the task visible via the existing board scan.
 */
public final class MonkTaskSource implements TaskSource {

    private final IssuerRef      issuer;
    private final Building       monastery;
    private final TownspersonMob monk;

    private MonkTaskSource(IssuerRef issuer, Building monastery, TownspersonMob monk) {
        this.issuer    = issuer;
        this.monastery = monastery;
        this.monk      = monk;
    }

    /**
     * Resolve the source for {@code npc}, or empty if not a MONK or has
     * no assigned MONASTERY building.
     */
    public static Optional<MonkTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        if (npc.getProfession() != Profession.MONK) return Optional.empty();
        Building b = npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(bld -> bld.getType() == BuildingType.MONASTERY)
                .orElse(null);
        if (b == null) return Optional.empty();
        return Optional.of(new MonkTaskSource(resolveIssuer(npc), b, npc));
    }

    /** Convenience entry point used by {@code DoTaskBehavior.refreshSources}. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    /** BUSINESS if the monk has one, else NPC. Mirrors FarmTaskSource. */
    static IssuerRef resolveIssuer(TownspersonMob npc) {
        UUID businessId = npc.getBusinessId().orElse(null);
        if (businessId != null) return new IssuerRef(LevelKind.BUSINESS, businessId);
        return new IssuerRef(LevelKind.NPC, npc.getUUID());
    }

    @Override
    public IssuerRef issuer() { return issuer; }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();

        // Check whether any craft is currently runnable for this monk at
        // this monastery. Gate only — the executor re-selects at runtime.
        boolean hasCraft = hasCraftAvailable(level);

        TaskSavedData data  = TaskSavedData.get(level);
        TaskBoard     board = data.board(issuer);

        TaskId id = ProductionTaskIds.stable(issuer,
                MonkVerb.MONASTIC_CRAFT + ":" + monastery.getId());

        if (!hasCraft) {
            removeIfUnclaimed(board, data, id);
            return;
        }

        // at = monastery origin for the executor's walk-to phase
        BlockPos origin = monastery.getShape().getOrigin();
        GlobalPos gpos = GlobalPos.of(level.dimension(),
                origin != null ? origin : BlockPos.ZERO);

        TaskFilter monkFilter = new TaskFilter(
                Optional.empty(), 0, Optional.empty(),
                Optional.of(Profession.MONK), Optional.empty(), false);

        upsert(board, data, id,
                new Objective.PerformService(MonkVerb.MONASTIC_CRAFT,
                        Optional.of(monastery.getId().toString()),
                        Optional.of(gpos)),
                new Priority(TaskPriority.NORMAL, 0f),
                monkFilter);
    }

    /**
     * Returns true if at least one MonasticCraft is currently runnable for
     * this monk at this monastery: skill ≥ minLevel, amenity supported,
     * inputs on hand, store below quota (need > 0).
     *
     * <p>Mirrors the scan loop in {@code MonkProductionBehavior.selectPlan}
     * but without the "pick greatest-need" step — the executor does that at
     * execution time with fresh world state. This gate prevents emitting a
     * task when the monk genuinely has nothing to do.</p>
     */
    private boolean hasCraftAvailable(ServerLevel level) {
        for (MonasticCraft c : MonasticCrafts.CRAFTS) {
            if (monk.getSkills().getLevel(c.skill()) < c.minLevel()) continue;
            if (!MonasticCrafts.isSupported(level, monastery, c)) continue;
            if (!hasAllInputs(level, c)) continue;
            if (MonasticCrafts.need(level, monastery, c) <= 0) continue;
            return true;
        }
        return false;
    }

    private boolean hasAllInputs(ServerLevel level, MonasticCraft c) {
        for (var e : c.recipe().inputs().entrySet()) {
            if (tterrag1112.life_in_the_village.Village.BuildingStorageAccess
                    .countItem(level, monastery, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ── Board mutation helpers (mirrors PriestTaskSource) ────────────────────

    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        Objective obj, Priority priority, TaskFilter filter) {
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            Task t = existing.get();
            if (t.assignment().isTerminal()) {
                board.remove(id);
                // fall through to create fresh
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

    private void removeIfUnclaimed(TaskBoard board, TaskSavedData data, TaskId id) {
        board.get(id).ifPresent(t -> {
            if (t.assignment().claimants().isEmpty()) {
                board.remove(id);
                data.markChanged();
            }
        });
    }
}
