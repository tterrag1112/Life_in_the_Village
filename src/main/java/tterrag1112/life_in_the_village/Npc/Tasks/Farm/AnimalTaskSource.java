package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes;
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
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * G2 — task source for generic animal-tending work (pasture rotation,
 * ANIMAL_HUSBANDRY XP, passive disease recovery).
 *
 * <p>Emits ONE {@link FarmVerb#ANIMAL_TEND} task per farmhouse when the
 * farmer's {@link FarmRole} qualifies for animal work and the farmhouse
 * has at least one animal roster. A single task per farmhouse is correct
 * because the executor handles pen selection internally via
 * {@link tterrag1112.life_in_the_village.Village.Roster.PastureRotation}.</p>
 *
 * <h3>Role gate (mirrors FarmerBehavior.analyze lines 487–497 exactly)</h3>
 * ANIMAL_SPECIALIST, ANIMAL_TENDER, FERTILIZER, or
 * GENERALIST+FARMER_ANIMAL_FOCUS specialization. Note FERTILIZER: in the
 * legacy behavior it was routed to TENDING_ANIMALS only when no compost work
 * was available. In the task system both tasks coexist on the board and the
 * board's ranking resolves priority — a FERTILIZER with bone-meal will pick
 * the compost task (NORMAL tier); when compost is done or no bone-meal exists
 * they pick animal_tend. Behaviour is equivalent, and simpler.
 *
 * <h3>Roster gate</h3>
 * At least one {@link tterrag1112.life_in_the_village.Village.Roster.BuildingRoster}
 * must exist for the farmhouse. No roster → no animal_tend task.
 */
public final class AnimalTaskSource implements TaskSource {

    private final IssuerRef issuer;
    private final Building  farmhouse;
    private final TownspersonMob farmer;

    private AnimalTaskSource(IssuerRef issuer, Building farmhouse, TownspersonMob farmer) {
        this.issuer    = issuer;
        this.farmhouse = farmhouse;
        this.farmer    = farmer;
    }

    /**
     * Resolve the source for {@code npc}, or empty if not a FARMER or has no
     * assigned FARMHOUSE. Same gate as {@link FarmTaskSource#forNpc}.
     */
    public static Optional<AnimalTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        if (npc.getProfession() != Profession.FARMER) return Optional.empty();
        Building fh = npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);
        if (fh == null) return Optional.empty();
        return Optional.of(new AnimalTaskSource(resolveIssuer(npc), fh, npc));
    }

    /** Convenience entry point used by {@code DoTaskBehavior.refreshSources}. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    /** Same board resolution as FarmTaskSource. */
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

        // ── Role gate (verbatim from FarmerBehavior.analyze :487-497) ────────
        FarmRole role = ProfessionRoleManager.getRole(farmer, FarmRole.class);
        boolean specBiasAnimal = (role == FarmRole.GENERALIST)
                && farmer.getSpecializationComponent().currentId()
                        .map(id -> id.equals(NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name()))
                        .orElse(false);
        boolean animalRole = role == FarmRole.ANIMAL_SPECIALIST
                || role == FarmRole.ANIMAL_TENDER
                || role == FarmRole.FERTILIZER
                || specBiasAnimal;

        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.board(issuer);
        TaskId tendId  = ProductionTaskIds.stable(issuer, "animal_tend:" + farmhouse.getId());

        if (!animalRole) {
            removeIfUnclaimed(board, tendId, taskData);
            return;
        }

        // ── Roster gate: at least one animal roster must exist ────────────────
        RosterSavedData rdata = RosterSavedData.get(level);
        boolean hasRoster = !rdata.getRostersForBuilding(farmhouse.getId()).isEmpty();
        if (!hasRoster) {
            removeIfUnclaimed(board, tendId, taskData);
            return;
        }

        // ── Emit one animal_tend task for this farmhouse ──────────────────────
        BlockPos origin = farmhouse.getShape().getOrigin();
        GlobalPos gpos  = GlobalPos.of(level.dimension(), origin);
        TaskFilter farmerFilter = new TaskFilter(
                Optional.empty(), 0, Optional.empty(),
                Optional.of(Profession.FARMER), Optional.empty(), false);

        upsert(board, taskData, tendId,
                new Objective.PerformService(FarmVerb.ANIMAL_TEND,
                        Optional.of(farmhouse.getId().toString()), Optional.of(gpos)),
                new Priority(TaskPriority.NORMAL, 0f),
                farmerFilter);
    }

    // ── Board mutation helpers (mirror FarmTaskSource) ────────────────────────

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
