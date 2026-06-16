package tterrag1112.life_in_the_village.Npc.Tasks.Mine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
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
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * G5a — task source for mining tasks.
 *
 * <p>On each refresh it:
 * <ol>
 *   <li>Resolves the miner's assigned MINE building.</li>
 *   <li>Emits / upserts one {@link MineVerb#MINE} {@code PerformService}
 *       task with stable id {@code mine:mineId}, NORMAL tier, and
 *       {@code at} = mine center (width/2 offset, matching
 *       {@code MinerBehavior.checkExtraStartConditions:minePos}).</li>
 *   <li>Prunes the task via {@link #removeIfUnclaimed} when the miner has
 *       no mine (shouldn't happen while migrated, but safe).</li>
 * </ol>
 *
 * <h3>Start-gate fidelity</h3>
 * The legacy {@code MinerBehavior.checkExtraStartConditions} starts whenever
 * the miner is on work-time and has a mine. The dusk-yield gate in
 * {@link tterrag1112.life_in_the_village.Npc.Tasks.DoTaskBehavior} provides
 * the work-time check; we always emit when a mine is assigned. This matches
 * the legacy trigger faithfully — the miner mines during work-time.
 *
 * <h3>Issuer</h3>
 * BUSINESS if the miner has one, else NPC (mirrors FarmTaskSource).
 */
public final class MineTaskSource implements TaskSource {

    private final IssuerRef      issuer;
    private final Building       mine;
    private final TownspersonMob miner;

    private MineTaskSource(IssuerRef issuer, Building mine, TownspersonMob miner) {
        this.issuer = issuer;
        this.mine   = mine;
        this.miner  = miner;
    }

    /**
     * Resolve the source for {@code npc}, or empty if not a MINER or has
     * no assigned MINE building.
     */
    public static Optional<MineTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        if (npc.getProfession() != Profession.MINER) return Optional.empty();
        Building b = npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(bld -> bld.getType() == BuildingType.MINE)
                .orElse(null);
        if (b == null) return Optional.empty();
        return Optional.of(new MineTaskSource(resolveIssuer(npc), b, npc));
    }

    /** Convenience entry point used by {@code DoTaskBehavior.refreshSources}. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    /** BUSINESS if the miner has one, else NPC. Mirrors FarmTaskSource. */
    static IssuerRef resolveIssuer(TownspersonMob npc) {
        UUID businessId = npc.getBusinessId().orElse(null);
        if (businessId != null) return new IssuerRef(LevelKind.BUSINESS, businessId);
        return new IssuerRef(LevelKind.NPC, npc.getUUID());
    }

    //@Override
    public IssuerRef issuer() { return issuer; }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        TaskSavedData data  = TaskSavedData.get(level);
        TaskBoard     board = data.board(issuer);

        TaskId id = ProductionTaskIds.stable(issuer, MineVerb.MINE + ":" + mine.getId());

        // Compute the mine center — mirrors MinerBehavior.checkExtraStartConditions:minePos
        BlockPos origin = mine.getShape().getOrigin();
        if (origin == null) {
            removeIfUnclaimed(board, data, id);
            return;
        }
        BlockPos center = origin.offset(
                mine.getShape().getWidth() / 2, 1,
                mine.getShape().getLength() / 2);
        GlobalPos gpos = GlobalPos.of(level.dimension(), center);

        TaskFilter minerFilter = new TaskFilter(
                Optional.empty(), 0, Optional.empty(),
                Optional.of(Profession.MINER), Optional.empty(), false);

        upsert(board, data, id,
                new Objective.PerformService(MineVerb.MINE,
                        Optional.of(mine.getId().toString()),
                        Optional.of(gpos)),
                new Priority(TaskPriority.NORMAL, 0f),
                minerFilter);
    }

    // ── Board mutation helpers ────────────────────────────────────────────────

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
