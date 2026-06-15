package tterrag1112.life_in_the_village.Npc.Tasks.Household;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
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
import tterrag1112.life_in_the_village.Npc.Tasks.Producer.ProductionTaskIds;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T2 — the HOUSEHOLD-scope task source. The household analogue of
 * {@code ProductionTaskSource}: on the dispatcher's lazy refresh cadence it
 * emits the household's food need onto its {@code IssuerRef(HOUSEHOLD, houseId)}
 * board as a {@code MaintainStock(BREAD, familySize × 4)} task.
 *
 * <p>The acting NPC supplies the household: the house id is the NPC's
 * {@code getHouseId()} (== {@link HouseholdData#getBuildingId()}, == the
 * household board key emitted by {@code TaskContext.memberships()}). familySize
 * is the household member count. A satisfied need (bread &ge; target) removes
 * the task unless it is claimed / in flight. STABLE ids keep a refresh
 * idempotent (priority updated in place, no duplicate pile-up), exactly like
 * the producer source.</p>
 *
 * <p>Either or both household fulfillments may be unable to act (no baker / no
 * coin); when neither can, the task simply sits OPEN on the board and is
 * retried each refresh — it never gates anything else.</p>
 */
public final class HouseholdTaskSource implements TaskSource {

    private final IssuerRef issuer;
    private final Building house;
    private final int familySize;

    private HouseholdTaskSource(IssuerRef issuer, Building house, int familySize) {
        this.issuer = issuer;
        this.house = house;
        this.familySize = familySize;
    }

    /**
     * Resolve the source for {@code npc} (its household + house building), or
     * empty if the NPC has no house / household / resolvable building.
     */
    public static Optional<HouseholdTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        UUID houseId = npc.getHouseId().orElse(null);
        if (houseId == null) return Optional.empty();
        VillageSavedData data = VillageSavedData.get(level);
        Building house = data.getBuildingById(houseId).orElse(null);
        if (house == null) return Optional.empty();
        HouseholdData household = data.getHouseholdForBuilding(houseId).orElse(null);
        int familySize = household != null
                ? Math.max(1, household.getMemberNpcIds().size()) : 1;
        IssuerRef issuer = new IssuerRef(LevelKind.HOUSEHOLD, houseId);
        return Optional.of(new HouseholdTaskSource(issuer, house, familySize));
    }

    /** Convenience used by the dispatcher's refresh: resolve + generate. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    public IssuerRef issuer() { return issuer; }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        TaskSavedData data = TaskSavedData.get(level);
        TaskBoard board = data.board(issuer);

        int target = familySize * HouseholdFood.PER_MEMBER_THRESHOLD;
        TaskId id = ProductionTaskIds.stable(issuer,
                "household-food:" + ProductionTaskIds.key(HouseholdFood.FOOD_ITEM));
        int stock = BuildingStorageAccess.countItem(level, house, HouseholdFood.FOOD_ITEM);

        if (target <= 0 || stock >= target) {
            removeIfUnclaimed(board, id, data);
            return;
        }
        // NORMAL tier (a real family need, like the producer's finals), urgency
        // from the deficit so a starving household sorts above a topped-up one.
        float urgency = Mth.clamp((float) (target - stock) / target, 0f, 1f);
        upsert(board, data, id,
                new Objective.MaintainStock(HouseholdFood.FOOD_ITEM, target, HouseholdFood.BUFFER),
                new Priority(TaskPriority.NORMAL, urgency));
    }

    // ── Upsert / remove helpers (mirror ProductionTaskSource) ────────────────

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
