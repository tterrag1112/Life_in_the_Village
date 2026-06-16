package tterrag1112.life_in_the_village.Npc.Tasks.Farm;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRoleAssigner;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Companies.BusinessSavedData;
import tterrag1112.life_in_the_village.Guilds.Companies.EmploymentTier;
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
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * G1 — task source for crop field work (harvest / replant / till / compost).
 *
 * <p>On each refresh cadence tick it:
 * <ol>
 *   <li>Resolves the farmer's assigned farmhouse and its CROP_FIELD plots
 *       (same accessor and filter as {@code FarmerBehavior.analyze()}).</li>
 *   <li>Runs a daily {@link FarmRoleAssigner#assignRoles} call, mirroring
 *       the {@code ROLE_ASSIGN_INTERVAL} cadence from {@code FarmerBehavior}.</li>
 *   <li>Emits ONE task per (plot, verb) using a STABLE id keyed by
 *       verb+plotId so repeated refreshes never stack duplicates.</li>
 *   <li>Stale tasks (verb no longer applicable) are removed via
 *       {@code removeIfUnclaimed} — claimed/in-flight tasks survive.</li>
 * </ol>
 *
 * <h3>Verbs and priorities (G0 tier convention)</h3>
 * <ul>
 *   <li>{@link FarmVerb#HARVEST}  — mature crops present; NORMAL tier.</li>
 *   <li>{@link FarmVerb#REPLANT}  — empty farmland + seeds available; LOW tier.</li>
 *   <li>{@link FarmVerb#TILL}     — tillable dirt surfaces present; LOW tier.</li>
 *   <li>{@link FarmVerb#COMPOST}  — soil below fallow-exit, BONE_MEAL present,
 *       past cooldown; LOW tier with urgency scaled by soil deficit.</li>
 * </ul>
 *
 * <h3>Role gating</h3>
 * Same role checks as {@code FarmerBehavior}: GENERALIST / CROP_SPECIALIST /
 * HARVESTER may harvest; GENERALIST / CROP_SPECIALIST / PLANTER may replant/till;
 * only FERTILIZER (non-apprentice) may compost.
 */
public final class FarmTaskSource implements TaskSource {

    /** Mirrors FarmerBehavior.ROLE_ASSIGN_INTERVAL (one game-day). */
    private static final long ROLE_ASSIGN_INTERVAL = 24000L;
    /** Mirrors FarmerBehavior.COMPOST_PLOT_COOLDOWN. */
    private static final long COMPOST_PLOT_COOLDOWN = 3L * FarmPlot.DAY_TICKS;

    /**
     * Per-farmhouse last-assignment tick. A static map means a new FarmTaskSource
     * instance (created each refresh) sees the prior tick. Resets on server
     * restart, causing one extra FarmRoleAssigner call at startup — acceptable.
     */
    private static final ConcurrentHashMap<UUID, Long> lastRoleAssignByFarmhouse =
            new ConcurrentHashMap<>();

    private final IssuerRef issuer;
    private final Building farmhouse;
    private final TownspersonMob farmer;

    private FarmTaskSource(IssuerRef issuer, Building farmhouse, TownspersonMob farmer) {
        this.issuer    = issuer;
        this.farmhouse = farmhouse;
        this.farmer    = farmer;
    }

    /**
     * Resolve the source for {@code npc}, or empty if not a FARMER or has no
     * assigned FARMHOUSE.
     */
    public static Optional<FarmTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        if (npc.getProfession() != Profession.FARMER) return Optional.empty();
        Building fh = npc.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id))
                .filter(b -> b.getType() == BuildingType.FARMHOUSE)
                .orElse(null);
        if (fh == null) return Optional.empty();
        return Optional.of(new FarmTaskSource(resolveIssuer(npc), fh, npc));
    }

    /** Convenience entry point used by {@code DoTaskBehavior.refreshSources}. */
    public static void generateFor(ServerLevel level, TownspersonMob npc, TaskContext ctx) {
        forNpc(level, npc).ifPresent(src -> src.generate(ctx));
    }

    /** Same board resolution as {@code ProductionTaskSource} and {@code ScribeCommissionTaskSource}. */
    static IssuerRef resolveIssuer(TownspersonMob npc) {
        UUID businessId = npc.getBusinessId().orElse(null);
        if (businessId != null) return new IssuerRef(LevelKind.BUSINESS, businessId);
        return new IssuerRef(LevelKind.NPC, npc.getUUID());
    }

    public IssuerRef issuer() { return issuer; }

    // ── Diagnostic helper (read-only, used by TaskDebugCommand) ─────────────

    /**
     * Per-plot eligibility snapshot used by {@code TaskDebugCommand}'s farm
     * disposition block.  Read-only — mirrors the verb-eligibility checks in
     * {@link #generate} without mutating any board state.
     *
     * @param level     server level (for block lookups and storage counts)
     * @param farmhouse the resolved FARMHOUSE building (for seed + bone-meal stock)
     * @param farmer    the farmer NPC (for personal-inventory seed check)
     * @param plot      the non-fallow CROP_FIELD plot to inspect
     * @param now       current game tick (for compost-cooldown check)
     */
    public static PlotEligibility plotEligibilityFor(
            ServerLevel level, Building farmhouse, TownspersonMob farmer,
            FarmPlot plot, long now) {
        boolean mature        = hasMatureCrop(level, plot);
        boolean emptyFarmland = hasEmptyFarmland(level, plot);
        // Seed check requires no instance — farmhouse and farmer already resolved.
        boolean seedsAvail = false;
        if (emptyFarmland) {
            Item seedItem = plot.getCropType().resolveSeedItem();
            int personal  = countSeedsStatic(farmer, seedItem);
            int storage   = BuildingStorageAccess.countItem(level, farmhouse, seedItem);
            seedsAvail    = (personal + storage) > 0;
        }
        boolean tillable = !plot.getTillableSurfaces(level).isEmpty();
        boolean compost  = plot.getSoilQuality() < FarmPlot.SOIL_FALLOW_EXIT
                && (now - plot.getLastCompostedTick()) >= COMPOST_PLOT_COOLDOWN
                && BuildingStorageAccess.countItem(level, farmhouse, Items.BONE_MEAL) > 0;
        return new PlotEligibility(mature, emptyFarmland, seedsAvail, tillable, compost);
    }

    /** Static seed-count helper used by {@link #plotEligibilityFor}; avoids
     *  needing a FarmTaskSource instance for the diagnostic path. */
    private static int countSeedsStatic(TownspersonMob farmer, Item seedItem) {
        int total = 0;
        net.minecraft.world.SimpleContainer inv = farmer.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == seedItem) total += stack.getCount();
        }
        return total;
    }

    /**
     * Read-only per-plot eligibility snapshot returned by
     * {@link #plotEligibilityFor}.
     *
     * @param mature         at least one CropBlock at max age in the plot
     * @param emptyFarmland  at least one farmland block with air above
     * @param seedsAvailable seeds for the plot's crop type exist (personal inv or farmhouse storage)
     * @param tillable       at least one dirt/grass surface that can be tilled
     * @param compostEligible soil below SOIL_FALLOW_EXIT, bone-meal available, past cooldown
     */
    public record PlotEligibility(
            boolean mature,
            boolean emptyFarmland,
            boolean seedsAvailable,
            boolean tillable,
            boolean compostEligible) {}

    @Override
    public void generate(TaskContext ctx) {
        ServerLevel level = ctx.level();
        VillageSavedData data = VillageSavedData.get(level);
        long now = level.getGameTime();

        // ── FarmRoleAssigner daily cadence ────────────────────────────────────
        long lastAssign = lastRoleAssignByFarmhouse.getOrDefault(farmhouse.getId(), 0L);
        if (lastAssign == 0L || now - lastAssign >= ROLE_ASSIGN_INTERVAL) {
            FarmRoleAssigner.assignRoles(level, farmhouse);
            lastRoleAssignByFarmhouse.put(farmhouse.getId(), Math.max(now, 1L));
        }

        // ── Gather crop plots (mirrors FarmerBehavior.analyze allPlots) ───────
        List<FarmPlot> allPlots = data.getFarmPlotsForFarmhouse(farmhouse.getId())
                .stream()
                .filter(p -> p.getSubtype() == FarmPlot.PlotSubtype.CROP_FIELD)
                .peek(p -> p.tickFallowRecovery(now))
                .filter(p -> !p.isFallow())
                .collect(Collectors.toList());

        TaskSavedData taskData = TaskSavedData.get(level);
        TaskBoard board = taskData.board(issuer);

        if (allPlots.isEmpty()) {
            pruneAllFarmTasks(board, taskData);
            return;
        }

        // ── Role gate (mirrors FarmerBehavior analyze role routing) ──────────
        FarmRole role = ProfessionRoleManager.getRole(farmer, FarmRole.class);
        boolean canHarvest = canHarvest(role);
        boolean canPlant   = canPlant(role);

        // Spec-biased animal focus: treat like ANIMAL_TENDER for crop routing
        boolean specBiasAnimal = (role == FarmRole.GENERALIST)
                && farmer.getSpecializationComponent().currentId()
                        .map(id -> id.equals(NpcSpecializationTypes.FARMER_ANIMAL_FOCUS.name()))
                        .orElse(false);
        boolean animalRole = (role == FarmRole.ANIMAL_SPECIALIST
                || role == FarmRole.ANIMAL_TENDER
                || specBiasAnimal);

        if (animalRole) {
            // Pure animal workers do no crop tasks
            pruneAllFarmTasks(board, taskData);
            return;
        }

        // FERTILIZER: no harvest/replant/till; only compost
        if (role == FarmRole.FERTILIZER) {
            canHarvest = false;
            canPlant   = false;
        }

        // APPRENTICE: limit to assigned plot only
        boolean isApprentice = isApprenticeTier(level);
        if (isApprentice) {
            UUID assignedPlotId = farmer.getAssignedPlotId().orElse(null);
            if (assignedPlotId == null) {
                pruneAllFarmTasks(board, taskData);
                return;
            }
            final UUID pid = assignedPlotId;
            allPlots = allPlots.stream()
                    .filter(p -> p.getId().equals(pid))
                    .collect(Collectors.toList());
            if (allPlots.isEmpty()) {
                pruneAllFarmTasks(board, taskData);
                return;
            }
        }

        // Task filter: FARMER profession required, no workstation check (farm is outdoor)
        TaskFilter farmerFilter = new TaskFilter(
                Optional.empty(), 0, Optional.empty(),
                Optional.of(Profession.FARMER), Optional.empty(), false);

        // Track which ids are live this cycle so stale tasks can be pruned below
        Set<TaskId> live = new HashSet<>();

        for (FarmPlot plot : allPlots) {
            UUID plotId     = plot.getId();
            BlockPos origin = plot.getOrigin();
            GlobalPos gpos  = GlobalPos.of(level.dimension(), origin);

            // ── HARVEST ───────────────────────────────────────────────────────
            TaskId harvestId = ProductionTaskIds.stable(issuer, "farm_harvest:" + plotId);
            if (canHarvest) {
                boolean hasMature = hasMatureCrop(level, plot);
                if (hasMature) {
                    live.add(harvestId);
                    upsert(board, taskData, harvestId,
                            new Objective.PerformService(FarmVerb.HARVEST,
                                    Optional.of(plotId.toString()), Optional.of(gpos)),
                            new Priority(TaskPriority.NORMAL, 0f), farmerFilter);
                } else {
                    removeIfUnclaimed(board, harvestId, taskData);
                }
            } else {
                removeIfUnclaimed(board, harvestId, taskData);
            }

            // ── REPLANT ───────────────────────────────────────────────────────
            // Includes farmland with air above; seed availability gate mirrors
            // FarmerBehavior.hasAnyFarmingSeed.
            TaskId replantId = ProductionTaskIds.stable(issuer, "farm_replant:" + plotId);
            if (canPlant) {
                boolean hasEmptyFarmland = hasEmptyFarmland(level, plot);
                boolean hasSeed = hasEmptyFarmland && hasSeedsAvailable(level, plot);
                TaskId acquireSeedId = ProductionTaskIds.stable(
                        issuer, "farm_seed:" + plotId);
                if (hasEmptyFarmland && hasSeed) {
                    live.add(replantId);
                    upsert(board, taskData, replantId,
                            new Objective.PerformService(FarmVerb.REPLANT,
                                    Optional.of(plotId.toString()), Optional.of(gpos)),
                            new Priority(TaskPriority.LOW, 0f), farmerFilter);
                    // Seeds now available — remove any pending acquire task
                    removeIfUnclaimed(board, acquireSeedId, taskData);
                } else if (hasEmptyFarmland) {
                    // Empty farmland but no seeds: emit a seed-buy task.
                    // qty = max(1, farmlandSize - currentFarmhouseSeeds) mirrors
                    // legacy FarmerBehavior.buySeeds qty formula.
                    Item seedItem = plot.getCropType().resolveSeedItem();
                    int farmlandSize = plot.getFarmlandBlocks(level).size();
                    int storedSeeds  = BuildingStorageAccess.countItem(level, farmhouse, seedItem);
                    int qty = Math.max(1, farmlandSize - storedSeeds);
                    live.add(acquireSeedId);
                    upsert(board, taskData, acquireSeedId,
                            new Objective.Acquire(seedItem, qty),
                            new Priority(TaskPriority.LOW, 0f), farmerFilter);
                    removeIfUnclaimed(board, replantId, taskData);
                } else {
                    removeIfUnclaimed(board, replantId, taskData);
                    removeIfUnclaimed(board, acquireSeedId, taskData);
                }
            } else {
                removeIfUnclaimed(board, replantId, taskData);
            }

            // ── TILL ──────────────────────────────────────────────────────────
            // Emitted when tillable dirt/grass surfaces exist, whether or not
            // seeds are available. The executor will till then stop (no plant)
            // when seeds run out. Complements rather than duplicates replant —
            // replant includes inline tilling when it arrives at a dirt position,
            // but the till task handles pure-till preparatory work.
            TaskId tillId = ProductionTaskIds.stable(issuer, "farm_till:" + plotId);
            if (canPlant) {
                boolean hasTillable = !plot.getTillableSurfaces(level).isEmpty();
                if (hasTillable) {
                    live.add(tillId);
                    upsert(board, taskData, tillId,
                            new Objective.PerformService(FarmVerb.TILL,
                                    Optional.of(plotId.toString()), Optional.of(gpos)),
                            new Priority(TaskPriority.LOW, 0f), farmerFilter);
                } else {
                    removeIfUnclaimed(board, tillId, taskData);
                }
            } else {
                removeIfUnclaimed(board, tillId, taskData);
            }

            // ── COMPOST ───────────────────────────────────────────────────────
            // Only for non-apprentice FERTILIZER role. Requires BONE_MEAL stock,
            // soil below SOIL_FALLOW_EXIT, and past the per-plot cooldown.
            TaskId compostId = ProductionTaskIds.stable(issuer, "farm_compost:" + plotId);
            if (!isApprentice && role == FarmRole.FERTILIZER) {
                boolean compostEligible =
                        plot.getSoilQuality() < FarmPlot.SOIL_FALLOW_EXIT
                        && (now - plot.getLastCompostedTick()) >= COMPOST_PLOT_COOLDOWN
                        && BuildingStorageAccess.countItem(level, farmhouse, Items.BONE_MEAL) > 0;
                if (compostEligible) {
                    live.add(compostId);
                    float urgency = Mth.clamp(
                            (FarmPlot.SOIL_FALLOW_EXIT - plot.getSoilQuality())
                                    / FarmPlot.SOIL_FALLOW_EXIT,
                            0f, 1f);
                    upsert(board, taskData, compostId,
                            new Objective.PerformService(FarmVerb.COMPOST,
                                    Optional.of(plotId.toString()), Optional.of(gpos)),
                            new Priority(TaskPriority.LOW, urgency), farmerFilter);
                } else {
                    removeIfUnclaimed(board, compostId, taskData);
                }
            } else {
                removeIfUnclaimed(board, compostId, taskData);
            }
        }

        // ── Surplus sell tasks (one per sellable crop that is over its keep-floor) ──
        // Emitted outside the per-plot loop because surplus is a farmhouse-level
        // concern, not per-plot. Mirrors legacy FarmerBehavior.computeSurplusToSell
        // and tryHandOffToSell (which issued these imperatively; here we use the
        // task system so the dusk-yield applies uniformly).
        // Keep-floors: WHEAT=32, CARROT=16, POTATO=16, BEETROOT=16, seeds=8.
        // Sellable items mirror FarmerBehavior.sellableOutputs().
        Map<Item, Integer> sellKeepFloors = Map.of(
                Items.WHEAT,           32,
                Items.CARROT,          16,
                Items.POTATO,          16,
                Items.BEETROOT,        16,
                Items.WHEAT_SEEDS,      8,
                Items.BEETROOT_SEEDS,   8);
        for (Map.Entry<Item, Integer> entry : sellKeepFloors.entrySet()) {
            Item cropItem = entry.getKey();
            int  keepFloor = entry.getValue();
            int  stock = BuildingStorageAccess.countItem(level, farmhouse, cropItem);
            String itemKey = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(cropItem).toString();
            TaskId sellId = ProductionTaskIds.stable(issuer, "farm_sell:" + itemKey);
            if (stock > keepFloor) {
                live.add(sellId);
                upsert(board, taskData, sellId,
                        new Objective.SellSurplus(cropItem),
                        new Priority(TaskPriority.LOW, 0f), farmerFilter);
            } else {
                removeIfUnclaimed(board, sellId, taskData);
            }
        }

        // ── Prune stale farm tasks (plots no longer eligible) ─────────────────
        for (Task t : List.copyOf(board.all())) {
            if (!(t.objective() instanceof Objective.PerformService ps)) continue;
            if (!FarmVerb.isCropVerb(ps.kind())) continue;
            if (live.contains(t.id())) continue;
            if (!t.assignment().claimants().isEmpty()) continue;
            board.remove(t.id());
            taskData.markChanged();
        }
    }

    // ── Scan helpers (mirrors FarmerBehavior scan methods) ───────────────────

    private static boolean hasMatureCrop(ServerLevel level, FarmPlot plot) {
        for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            BlockState state = level.getBlockState(cropPos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                return true;
            }
        }
        return false;
    }

    /** True when at least one farmland block has air above (empty, ready to replant). */
    private static boolean hasEmptyFarmland(ServerLevel level, FarmPlot plot) {
        for (BlockPos farmland : plot.getFarmlandBlocks(level)) {
            BlockPos cropPos = farmland.above();
            if (level.getBlockState(cropPos).isAir()) return true;
        }
        return false;
    }

    /**
     * Mirrors {@code FarmerBehavior.hasAnyFarmingSeed}: checks personal inventory
     * AND farmhouse storage for at least one seed matching the plot's current crop type.
     */
    private boolean hasSeedsAvailable(ServerLevel level, FarmPlot plot) {
        Item seedItem = plot.getCropType().resolveSeedItem();
        int personal = countSeedsInPersonalInventory(seedItem);
        int storage  = BuildingStorageAccess.countItem(level, farmhouse, seedItem);
        return (personal + storage) > 0;
    }

    private int countSeedsInPersonalInventory(Item seedItem) {
        int total = 0;
        SimpleContainer inv = farmer.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == seedItem) total += stack.getCount();
        }
        return total;
    }

    // ── Role helpers (verbatim from FarmerBehavior) ───────────────────────────

    private static boolean canHarvest(FarmRole role) {
        if (role == null) return true;
        return role == FarmRole.GENERALIST
                || role == FarmRole.CROP_SPECIALIST
                || role == FarmRole.HARVESTER;
    }

    private static boolean canPlant(FarmRole role) {
        if (role == null) return true;
        return role == FarmRole.GENERALIST
                || role == FarmRole.CROP_SPECIALIST
                || role == FarmRole.PLANTER;
    }

    /** Mirrors {@code FarmerBehavior.isApprenticeTier()}. */
    private boolean isApprenticeTier(ServerLevel level) {
        return isApprenticeTierFor(level, farmhouse, farmer);
    }

    /**
     * Read-only apprentice check used by both {@link #generate} and
     * {@code TaskDebugCommand}'s farm diagnostic.  Checks the
     * {@link EmploymentTier} of {@code farmer} within the business that owns
     * {@code farmhouse} — NOT {@code FarmRole.APPRENTICE}.
     */
    public static boolean isApprenticeTierFor(
            ServerLevel level, Building farmhouse, TownspersonMob farmer) {
        BusinessSavedData bdata = BusinessSavedData.get(level);
        for (var business : bdata.getAllBusinesses()) {
            if (!business.getBuildingIds().contains(farmhouse.getId())) continue;
            return business.getWorkerTier(farmer.getUUID())
                    .map(t -> t == EmploymentTier.APPRENTICE)
                    .orElse(false);
        }
        return false;
    }

    // ── Board mutation helpers (mirror ProductionTaskSource pattern) ──────────

    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        Objective obj, Priority priority, TaskFilter filter) {
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            Task t = existing.get();
            // If the task reached a terminal state (FAILED from a mid-day
            // executor abandon, or DONE), revive it as a fresh OPEN task so it
            // becomes claimable again on the next source refresh. This makes the
            // "source refresh can recreate" promise correct for stable-id tasks.
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

    /** Remove all unclaimed farm-verb tasks from the board. */
    private void pruneAllFarmTasks(TaskBoard board, TaskSavedData taskData) {
        boolean changed = false;
        for (Task t : List.copyOf(board.all())) {
            if (!t.assignment().claimants().isEmpty()) continue;
            boolean isFarmTask = false;
            if (t.objective() instanceof Objective.PerformService ps
                    && FarmVerb.isCropVerb(ps.kind())) {
                isFarmTask = true;
            } else if (t.objective() instanceof Objective.SellSurplus
                    || t.objective() instanceof Objective.Acquire) {
                // Sell/seed tasks are also farm-owned on this board; prune them too
                isFarmTask = true;
            }
            if (!isFarmTask) continue;
            board.remove(t.id());
            changed = true;
        }
        if (changed) taskData.markChanged();
    }
}
