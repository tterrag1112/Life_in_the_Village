package tterrag1112.life_in_the_village.Npc.Tasks.Blacksmith;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production.BlacksmithCrafts;
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
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.Tasks.TaskPriority;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * T1 — generates / refreshes the BLACKSMITH's board onto its business
 * (or, lacking one, its building) {@link IssuerRef}.
 *
 * <h3>The model the pilot demonstrates</h3>
 * <ul>
 *   <li><b>Toolmaking is primary.</b> One {@code ProvideItem(tool, qty)}
 *       task per craftable output the smith makes, NORMAL tier, urgency
 *       from the deficit (need − stock). These outrank the iron reserve.</li>
 *   <li><b>Iron is a low-priority reserve, NOT a gate.</b> A single
 *       {@code MaintainStock(IRON_INGOT, target, buffer)} at LOW tier.
 *       Iron is otherwise obtained lazily as a dependency of a tool task
 *       (see {@link CraftToolFulfillment}), so it never pre-empts
 *       toolmaking.</li>
 * </ul>
 *
 * <h3>Need source (flagged: partial in T1)</h3>
 * A real village tool-demand signal (miners + guards + farmers needing
 * tools) is NOT cleanly reachable from this layer in T1, so the target
 * is the existing {@link BlacksmithCrafts#STOCK_QUOTAS} quota. T5 wires
 * the full need source; until then "need" == the quota floor.
 *
 * <h3>Idempotent refresh</h3>
 * Each task has a STABLE id (derived from issuer + objective signature),
 * so a refresh updates the existing task's priority in place instead of
 * piling up duplicates. Tasks for outputs at/over their need are removed
 * (unless claimed/in-flight).
 */
public final class BlacksmithTaskSource implements TaskSource {

    /** Iron reserve target + refill buffer (the MaintainStock parameters). */
    public static final int IRON_RESERVE_TARGET = 32;
    public static final int IRON_RESERVE_BUFFER = 8;

    private final IssuerRef issuer;
    private final Building workBuilding;

    private BlacksmithTaskSource(IssuerRef issuer, Building workBuilding) {
        this.issuer = issuer;
        this.workBuilding = workBuilding;
    }

    /**
     * Resolve the source for {@code npc}, or empty if it isn't a
     * blacksmith with an assigned BLACKSMITH building. The board the
     * source writes MUST be one the NPC reads via
     * {@link TaskContext#memberships()}: BUSINESS(businessId) when the
     * NPC has a business, else the NPC's own personal board (NPC(uuid)).
     * See {@link #resolveIssuer}.
     */
    public static Optional<BlacksmithTaskSource> forNpc(ServerLevel level, TownspersonMob npc) {
        if (npc.getProfession() != Profession.BLACKSMITH) return Optional.empty();
        Building b = ProductionHelpers
                .findAssignedBuilding(npc, level, BuildingType.BLACKSMITH)
                .orElse(null);
        if (b == null) return Optional.empty();
        IssuerRef ref = resolveIssuer(npc);
        return Optional.of(new BlacksmithTaskSource(ref, b));
    }

    /**
     * The board the NPC both reads (via {@link TaskContext#memberships()})
     * and this source writes. Prefer the BUSINESS board when the NPC has a
     * business id; otherwise the NPC's own personal board (NPC(uuid)),
     * which is always in the membership set. A building-keyed board would
     * be invisible to the NPC (the building is not a household and the
     * membership set has no building level), so it is deliberately not
     * used. Never null (NPC UUID always resolves).
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
        TaskSavedData data = TaskSavedData.get(level);
        TaskBoard board = data.board(issuer);

        // ── Primary: one ProvideItem(tool) per craftable output, NORMAL ──────
        for (ProductionRecipe r : BlacksmithCrafts.crafting()) {
            Item out = r.output();
            int need = BlacksmithCrafts.STOCK_QUOTAS.getOrDefault(out, 0);
            if (need <= 0) continue; // not a tracked output → no quota → no task
            int stock = BuildingStorageAccess.countItem(level, workBuilding, out);
            int deficit = need - stock;

            Objective obj = new Objective.ProvideItem(out, need);
            TaskId id = stableId(issuer, "provide:" + key(out));
            if (deficit <= 0) {
                removeIfUnclaimed(board, id, data);
                continue;
            }
            float urgency = Mth.clamp((float) deficit / need, 0f, 1f);
            upsert(board, data, id, obj, new Priority(TaskPriority.NORMAL, urgency));
        }

        // ── Reserve: MaintainStock(IRON_INGOT), LOW (never a gate) ───────────
        {
            Objective obj = new Objective.MaintainStock(
                    Items.IRON_INGOT, IRON_RESERVE_TARGET, IRON_RESERVE_BUFFER);
            TaskId id = stableId(issuer, "maintain:" + key(Items.IRON_INGOT));
            int stock = BuildingStorageAccess.countItem(level, workBuilding, Items.IRON_INGOT);
            // Only keep the reserve task alive while stock has dropped below the
            // refill trigger (target − buffer); above it, the reserve is met.
            if (stock < IRON_RESERVE_TARGET - IRON_RESERVE_BUFFER) {
                float urgency = Mth.clamp(
                        (float) (IRON_RESERVE_TARGET - stock) / IRON_RESERVE_TARGET, 0f, 1f);
                upsert(board, data, id, obj, new Priority(TaskPriority.LOW, urgency));
            } else {
                removeIfUnclaimed(board, id, data);
            }
        }
    }

    // ── Upsert / remove helpers ──────────────────────────────────────────────

    /** Create the task if absent; otherwise just refresh its priority (and
     *  re-open it if a previous attempt terminated). */
    private void upsert(TaskBoard board, TaskSavedData data, TaskId id,
                        Objective obj, Priority priority) {
        Optional<Task> existing = board.get(id);
        if (existing.isPresent()) {
            Task t = existing.get();
            // Refresh priority only while the task is still actionable; do not
            // disturb a claimed/in-progress task's assignment.
            t.setPriority(priority);
            data.markChanged();
            return;
        }
        Task t = new Task(id, issuer, obj, priority, TaskFilter.ANY,
                new tterrag1112.life_in_the_village.Npc.Tasks.Assignment(),
                java.util.List.of(), 0L, null);
        data.addTask(issuer, t);
    }

    /** Remove a satisfied task unless it is currently claimed / in flight. */
    private void removeIfUnclaimed(TaskBoard board, TaskId id, TaskSavedData data) {
        board.get(id).ifPresent(t -> {
            if (t.assignment().claimants().isEmpty()) {
                board.remove(id);
                data.markChanged();
            }
        });
    }

    // ── Stable id derivation ─────────────────────────────────────────────────

    static TaskId stableId(IssuerRef issuer, String objKey) {
        String seed = issuer.key() + "|" + objKey;
        return new TaskId(UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static String key(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
