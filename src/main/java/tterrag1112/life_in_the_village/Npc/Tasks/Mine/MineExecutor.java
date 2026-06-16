package tterrag1112.life_in_the_village.Npc.Tasks.Mine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Tasks.Objective;
import tterrag1112.life_in_the_village.Npc.Tasks.Task;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskActor;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskContext;
import tterrag1112.life_in_the_village.Npc.Tasks.TaskExecutor;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.MiningYieldData;
import tterrag1112.life_in_the_village.Village.Economy.Resources.MiningYieldRegistry;

/**
 * G5a — executor for a {@code mine} task.
 *
 * <h3>Phase machine (behavior-faithful port of MinerBehavior)</h3>
 * <ol>
 *   <li><b>INIT</b> — first tick: resolve mine and compute mine center;
 *       equip pickaxe; schedule first yield. FAILED if mine or pickaxe
 *       missing (mirrors {@code MinerBehavior.checkExtraStartConditions}
 *       and {@code MinerBehavior.walkToMine:needsPickaxe} gate).</li>
 *   <li><b>WALKING_TO_MINE</b> — navigate to mine center. Uses
 *       {@link NpcBehaviorHelpers#walkTo} (WALK_TARGET memory), same as
 *       other executors. {@code INTERACT_RANGE_SQ = 9} matches legacy.</li>
 *   <li><b>MINING</b> — pickaxe swing + stone-hit sound every 20 ticks;
 *       look-at mine wall every 40 ticks; roll yield when timer hits
 *       {@code nextYieldTick}. Yield: {@code MiningYieldRegistry.INSTANCE
 *       .getDefault().rollYield(random)}, amount scaled by mine level.
 *       Same yield table, same rarity weights, same level scaling as
 *       {@code MinerBehavior.mine}.</li>
 *   <li><b>DEPOSITING</b> — walk to mine origin; deposit full personal
 *       inventory via {@code BuildingStorageAccess.storeItem}; carry-pose
 *       while hauling. Mirrors {@code MinerBehavior.deposit} exactly.</li>
 *   <li><b>DONE</b> — return DONE after one complete mining→deposit cycle
 *       so dusk-yield applies. Source re-emits the task next refresh.</li>
 * </ol>
 *
 * <h3>Constants (behavior-faithful)</h3>
 * <ul>
 *   <li>{@code DEPOSIT_THRESHOLD = 16} — triggers deposit phase.</li>
 *   <li>{@code INTERACT_RANGE_SQ = 9} — arrival radius.</li>
 *   <li>Yield table: {@code MiningYieldRegistry.INSTANCE.getDefault()}
 *       — same singleton, same {@code rollYield} + {@code tickInterval}
 *       calls.</li>
 * </ul>
 *
 * <h3>Selling</h3>
 * The legacy {@code MinerBehavior} has NO selling logic. The
 * {@code ProfessionBrainFactory} comment mentioning
 * "CARGO_DESTINATION sell handoff" is stale — no sell code exists in the
 * behavior. No selling is added here. Flagged out-of-scope for G5b.
 *
 * <h3>XP</h3>
 * The legacy {@code MinerBehavior} awards NO skill XP. There is no
 * {@code SkillXp.award} call anywhere in the behavior. No XP is awarded
 * here either — faithful port.
 */
public final class MineExecutor implements TaskExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MineExecutor.class);

    /** Mirrors MinerBehavior.DEPOSIT_THRESHOLD. */
    private static final int DEPOSIT_THRESHOLD = 16;
    /** Mirrors MinerBehavior.INTERACT_RANGE_SQ. */
    private static final int INTERACT_RANGE_SQ = 9;
    /** Walk speed for WALK_TARGET. */
    private static final float WALK_SPEED = 1.0f;

    private enum Phase { INIT, WALKING_TO_MINE, MINING, DEPOSITING, DONE }

    private Phase   phase       = Phase.INIT;
    private int     miningTimer = 0;
    private int     nextYieldTick = 100; // mirrors MinerBehavior.start: short initial delay
    private Building mine;
    private BlockPos mineCenter;

    @Override
    public Result tick(Task task, TaskActor actor, TaskContext ctx) {
        ServerLevel    level = ctx.level();
        TownspersonMob npc   = ctx.npc().orElse(null);
        if (npc == null) return Result.FAILED;

        return switch (phase) {
            case INIT            -> tickInit(level, npc, task);
            case WALKING_TO_MINE -> tickWalkToMine(level, npc);
            case MINING          -> tickMining(level, npc);
            case DEPOSITING      -> tickDepositing(level, npc);
            case DONE            -> Result.DONE;
        };
    }

    // ── Phase: INIT ───────────────────────────────────────────────────────────

    private Result tickInit(ServerLevel level, TownspersonMob npc, Task task) {
        // Resolve the mine building from the task ref (mine UUID in ps.ref()).
        if (!(task.objective() instanceof Objective.PerformService ps)) return Result.FAILED;
        mine = ps.ref()
                .flatMap(s -> {
                    try {
                        java.util.UUID id = java.util.UUID.fromString(s);
                        return VillageSavedData.get(level).getBuildingById(id);
                    } catch (IllegalArgumentException e) {
                        return java.util.Optional.empty();
                    }
                })
                .filter(b -> b.getType() == BuildingType.MINE)
                .orElse(null);
        if (mine == null) return Result.FAILED;

        BlockPos origin = mine.getShape().getOrigin();
        if (origin == null) return Result.FAILED;
        // Compute mine center — mirrors MinerBehavior.checkExtraStartConditions:minePos
        mineCenter = origin.offset(
                mine.getShape().getWidth() / 2, 1,
                mine.getShape().getLength() / 2);

        // Equip pickaxe — mirrors MinerBehavior.start
        npc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));

        // Gate: miner needs a pickaxe — mirrors MinerBehavior.walkToMine:needsPickaxe
        if (npc.needsPickaxe()) {
            LOGGER.debug("MineExecutor: {} needs a pickaxe before mining", npc.getNpcName());
            cleanup(npc);
            return Result.FAILED;
        }

        // Schedule first yield — mirrors MinerBehavior.start:nextYieldTick
        MiningYieldData data = MiningYieldRegistry.INSTANCE.getDefault();
        if (data != null) {
            MiningYieldData.YieldEntry first = data.rollYield(npc.getRandom());
            nextYieldTick = first.tickInterval(mine.getLevel());
        }
        miningTimer = 0;

        // Clear NO_ACTIONABLE_WORK — mirrors MinerBehavior.start
        npc.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());

        phase = Phase.WALKING_TO_MINE;
        return Result.RUNNING;
    }

    // ── Phase: WALKING_TO_MINE ────────────────────────────────────────────────

    private Result tickWalkToMine(ServerLevel level, TownspersonMob npc) {
        if (mineCenter == null) return Result.FAILED;
        double distSq = npc.distanceToSqr(
                mineCenter.getX(), mineCenter.getY(), mineCenter.getZ());
        if (distSq > INTERACT_RANGE_SQ) {
            NpcBehaviorHelpers.walkTo(npc, mineCenter, WALK_SPEED);
        } else {
            npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            miningTimer = 0;
            phase = Phase.MINING;
        }
        return Result.RUNNING;
    }

    // ── Phase: MINING ─────────────────────────────────────────────────────────

    private Result tickMining(ServerLevel level, TownspersonMob npc) {
        miningTimer++;

        // Pickaxe swing + sound every 20 ticks — mirrors MinerBehavior.mine
        if (miningTimer % 20 == 0) {
            npc.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, npc.blockPosition(),
                    SoundEvents.STONE_HIT, SoundSource.NEUTRAL,
                    0.5f, 0.8f + npc.getRandom().nextFloat() * 0.4f);
        }
        // Look at mine wall every 40 ticks — mirrors MinerBehavior.mine
        if (miningTimer % 40 == 0 && mineCenter != null) {
            npc.getLookControl().setLookAt(mineCenter.getX(), mineCenter.getY() - 1, mineCenter.getZ());
        }

        // Yield when timer hits nextYieldTick — mirrors MinerBehavior.mine
        if (miningTimer >= nextYieldTick) {
            MiningYieldData yieldData = MiningYieldRegistry.INSTANCE.getDefault();
            if (yieldData == null) {
                cleanup(npc);
                return Result.DONE;
            }
            MiningYieldData.YieldEntry entry = yieldData.rollYield(npc.getRandom());
            int amount = entry.roll(npc.getRandom(), mine.getLevel());

            npc.getPersonalInventory().addItem(new ItemStack(entry.item(), amount));

            LOGGER.debug("MineExecutor: {} yielded {}x {} ({}) next in {} ticks",
                    npc.getNpcName(), amount, entry.item().getDescriptionId(),
                    entry.rarity(), entry.tickInterval(mine.getLevel()));

            miningTimer = 0;
            nextYieldTick = entry.tickInterval(mine.getLevel());
            usePickaxe(level, npc);

            // Transition to DEPOSITING at threshold — mirrors MinerBehavior.mine
            if (isAboveThreshold(npc)) {
                phase = Phase.DEPOSITING;
            }
        }
        return Result.RUNNING;
    }

    // ── Phase: DEPOSITING ─────────────────────────────────────────────────────

    private Result tickDepositing(ServerLevel level, TownspersonMob npc) {
        if (mine == null) { cleanup(npc); return Result.DONE; }

        // Carry-pose while hauling — mirrors MinerBehavior.deposit
        ItemStack hauling = firstNonEmptyStack(npc);
        if (!hauling.isEmpty()) {
            npc.getBrain().setMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get(), hauling.copy());
        }

        // Walk to mine origin (container location) — mirrors MinerBehavior.deposit
        BlockPos target = mine.getShape().getOrigin();
        if (target == null) { cleanup(npc); return Result.DONE; }

        double distSq = npc.distanceToSqr(target.getX(), target.getY(), target.getZ());
        if (distSq > INTERACT_RANGE_SQ) {
            NpcBehaviorHelpers.walkTo(npc, target, WALK_SPEED);
            return Result.RUNNING;
        }

        // Arrived — deposit all inventory items — mirrors MinerBehavior.deposit
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        var inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            boolean stored = BuildingStorageAccess.storeItem(level, mine, stack.copy());
            if (stored) inv.setItem(i, ItemStack.EMPTY);
        }

        npc.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());

        // One complete cycle done — DONE so dusk-yield applies.
        // MineTaskSource re-emits on the next refresh if still work-time.
        cleanup(npc);
        phase = Phase.DONE;
        return Result.DONE;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Durability damage to the equipped pickaxe — mirrors
     * {@code MinerBehavior.usePickaxe}.
     */
    private static void usePickaxe(ServerLevel level, TownspersonMob npc) {
        var inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(ItemTags.PICKAXES)) {
                npc.setItemInHand(InteractionHand.MAIN_HAND, stack);
                final int slot = i;
                stack.hurtAndBreak(1, level, null,
                        item -> inv.setItem(slot, ItemStack.EMPTY));
                return;
            }
        }
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** True when total carried items >= DEPOSIT_THRESHOLD — mirrors MinerBehavior. */
    private static boolean isAboveThreshold(TownspersonMob npc) {
        var inv = npc.getPersonalInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            total += inv.getItem(i).getCount();
        }
        return total >= DEPOSIT_THRESHOLD;
    }

    /** First non-empty stack in personal inventory (for carry display). */
    private static ItemStack firstNonEmptyStack(TownspersonMob npc) {
        var inv = npc.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) return s;
        }
        return ItemStack.EMPTY;
    }

    /** Clear navigation + carry display on stop/done. */
    private static void cleanup(TownspersonMob npc) {
        npc.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        npc.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        npc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }
}
