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
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions;
import tterrag1112.life_in_the_village.Village.Roster.BuildingRoster;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * G2 / G2b — task source for animal-work tasks.
 *
 * <p>Emits tasks based on the farmer's {@link FarmRole}:</p>
 * <ul>
 *   <li><b>ANIMAL_SPECIALIST, ANIMAL_TENDER, FERTILIZER, GENERALIST+ANIMAL_FOCUS</b>
 *       → one {@link FarmVerb#ANIMAL_TEND} task per farmhouse.</li>
 *   <li><b>SHEPHERD</b>
 *       → one {@link FarmVerb#SHEAR} task keyed by the SHEEP roster's bound pen.</li>
 *   <li><b>BEEKEEPER</b>
 *       → one {@link FarmVerb#COLLECT_HONEY} task keyed by the BEE roster's bound apiary.</li>
 * </ul>
 *
 * <p>The three branches are mutually exclusive by role: a farmer can only
 * be in one role at a time, so exactly one of the three task types is ever
 * emitted by a given NPC. Stale tasks from the other branches are pruned
 * via {@code removeIfUnclaimed}.</p>
 *
 * <h3>ANIMAL_TEND role gate (mirrors FarmerBehavior.analyze :487–497)</h3>
 * ANIMAL_SPECIALIST, ANIMAL_TENDER, FERTILIZER, or GENERALIST+FARMER_ANIMAL_FOCUS.
 * SHEPHERD and BEEKEEPER are explicitly excluded here — they get their own
 * species tasks, not the generic tend task.
 *
 * <h3>SHEAR / COLLECT_HONEY trigger predicates (mirror hasActionableWork)</h3>
 * SHEAR: role==SHEPHERD, SHEEP roster with countAdults()>0, shears in inventory.
 * COLLECT_HONEY: role==BEEKEEPER, BEE roster with countAdults()>0, at least one
 * of (shears + HONEYCOMB below quota) or (glass_bottle + HONEY_BOTTLE below quota).
 */
public final class AnimalTaskSource implements TaskSource {

    private static final int HONEYCOMB_STOCK_QUOTA    = 16;
    private static final int HONEY_BOTTLE_STOCK_QUOTA = 8;

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

    //@Override
    public IssuerRef issuer() { return issuer; }

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        FarmRole role = ProfessionRoleManager.getRole(farmer, FarmRole.class);

        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.board(issuer);

        // ── Stable task ids for all three task types ──────────────────────────
        TaskId tendId  = ProductionTaskIds.stable(issuer, "animal_tend:" + farmhouse.getId());
        TaskId shearId = ProductionTaskIds.stable(issuer, "shear:" + farmhouse.getId());
        TaskId honeyId = ProductionTaskIds.stable(issuer, "collect_honey:" + farmhouse.getId());

        // ── Determine which branch applies ────────────────────────────────────
        boolean specBiasAnimal = (role == FarmRole.GENERALIST)
                && farmer.getSpecializationComponent().currentId()
                        .map(id -> id.equals(NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name()))
                        .orElse(false);
        boolean animalRole = role == FarmRole.ANIMAL_SPECIALIST
                || role == FarmRole.ANIMAL_TENDER
                || role == FarmRole.FERTILIZER
                || specBiasAnimal;
        boolean isShepherd  = role == FarmRole.SHEPHERD;
        boolean isBeekeeper = role == FarmRole.BEEKEEPER;

        RosterSavedData rdata = RosterSavedData.get(level);
        BlockPos origin = farmhouse.getShape().getOrigin();
        GlobalPos gpos  = GlobalPos.of(level.dimension(), origin);
        TaskFilter farmerFilter = new TaskFilter(
                Optional.empty(), 0, Optional.empty(),
                Optional.of(Profession.FARMER), Optional.empty(), false);

        // ── Branch: generic ANIMAL_TEND ───────────────────────────────────────
        if (animalRole) {
            removeIfUnclaimed(board, shearId, taskData);
            removeIfUnclaimed(board, honeyId, taskData);
            boolean hasRoster = !rdata.getRostersForBuilding(farmhouse.getId()).isEmpty();
            if (!hasRoster) {
                removeIfUnclaimed(board, tendId, taskData);
                return;
            }
            upsert(board, taskData, tendId,
                    new Objective.PerformService(FarmVerb.ANIMAL_TEND,
                            Optional.of(farmhouse.getId().toString()), Optional.of(gpos)),
                    new Priority(TaskPriority.NORMAL, 0f),
                    farmerFilter);
            return;
        }

        // ── Branch: SHEAR (SHEPHERD role) ─────────────────────────────────────
        if (isShepherd) {
            removeIfUnclaimed(board, tendId, taskData);
            removeIfUnclaimed(board, honeyId, taskData);

            BuildingRoster sheepRoster = rdata
                    .getRoster(farmhouse.getId(), AnimalRosterDefinitions.SHEEP).orElse(null);
            boolean canShear = sheepRoster != null
                    && sheepRoster.countAdults() > 0
                    && hasItem(farmer, net.minecraft.world.item.Items.SHEARS);
            if (!canShear) {
                removeIfUnclaimed(board, shearId, taskData);
                return;
            }
            upsert(board, taskData, shearId,
                    new Objective.PerformService(FarmVerb.SHEAR,
                            Optional.of(farmhouse.getId().toString()), Optional.of(gpos)),
                    new Priority(TaskPriority.NORMAL, 0f),
                    farmerFilter);
            return;
        }

        // ── Branch: COLLECT_HONEY (BEEKEEPER role) ────────────────────────────
        if (isBeekeeper) {
            removeIfUnclaimed(board, tendId, taskData);
            removeIfUnclaimed(board, shearId, taskData);

            BuildingRoster beeRoster = rdata
                    .getRoster(farmhouse.getId(), AnimalRosterDefinitions.BEE).orElse(null);
            boolean hasAdults = beeRoster != null && beeRoster.countAdults() > 0;
            boolean canMakeComb = hasAdults
                    && hasItem(farmer, net.minecraft.world.item.Items.SHEARS)
                    && BuildingStorageAccess.countItem(level, farmhouse,
                            net.minecraft.world.item.Items.HONEYCOMB) < HONEYCOMB_STOCK_QUOTA;
            boolean canMakeBottle = hasAdults
                    && hasItem(farmer, net.minecraft.world.item.Items.GLASS_BOTTLE)
                    && BuildingStorageAccess.countItem(level, farmhouse,
                            net.minecraft.world.item.Items.HONEY_BOTTLE) < HONEY_BOTTLE_STOCK_QUOTA;
            if (!canMakeComb && !canMakeBottle) {
                removeIfUnclaimed(board, honeyId, taskData);
                return;
            }
            upsert(board, taskData, honeyId,
                    new Objective.PerformService(FarmVerb.COLLECT_HONEY,
                            Optional.of(farmhouse.getId().toString()), Optional.of(gpos)),
                    new Priority(TaskPriority.NORMAL, 0f),
                    farmerFilter);
            return;
        }

        // ── No applicable role — prune all three ──────────────────────────────
        removeIfUnclaimed(board, tendId, taskData);
        removeIfUnclaimed(board, shearId, taskData);
        removeIfUnclaimed(board, honeyId, taskData);
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

    private static boolean hasItem(TownspersonMob entity, net.minecraft.world.item.Item item) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) return true;
        }
        return false;
    }
}
