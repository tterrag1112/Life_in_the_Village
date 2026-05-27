package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.Farmer.FarmRole;
import tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionRoleManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Npc.Specialization.FarmerSpecialtyMultiplier;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Buildings.FarmPlot;
import tterrag1112.life_in_the_village.Village.Roster.AnimalRosterDefinitions;
import tterrag1112.life_in_the_village.Village.Roster.BuildingRoster;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

/**
 * Phase 6.7.3 — opportunistic beekeeper-tier work for FARMERs whose
 * {@link FarmRole} is pinned to {@link FarmRole#BEEKEEPER} (via locked
 * FARMER_BEEKEEPER specialization, see 6.7.1.7). Walks to the bound
 * APIARY plot, picks a hive whose {@link BeehiveBlock#HONEY_LEVEL} is
 * at the maximum, performs a tool-aware harvest (HONEYCOMB via shears,
 * HONEY_BOTTLE via glass bottle, or alternating when both are
 * available), and deposits the output into farmhouse storage.
 *
 * <h3>Output selection</h3>
 * <p>Per Phase 6.7.3.5: which output is produced depends on the
 * SHEPHERD's inventory and the family's stock state:</p>
 * <ul>
 *   <li>Has shears + HONEYCOMB below quota → HONEYCOMB eligible.</li>
 *   <li>Has glass bottle + HONEY_BOTTLE below quota → HONEY_BOTTLE eligible.</li>
 *   <li>Both eligible → alternate based on {@code lastHarvest} (per-NPC
 *       instance state).</li>
 *   <li>Only one eligible → that one is produced.</li>
 *   <li>Neither eligible → cycle skipped (no actionable work).</li>
 * </ul>
 *
 * <p>Stock quotas mirror the family-stock pattern used by homestead
 * behaviors. HONEYCOMB threshold (16) is higher than HONEY_BOTTLE (8)
 * because shears are durable while glass bottles are consumed per
 * bottling — the lower bottle quota avoids over-purchase of bottles.</p>
 *
 * <h3>Physical interaction model</h3>
 * <p>Mirrors {@link ShepherdBehavior}'s direct-state approach rather
 * than invoking vanilla's {@code BeehiveBlock.shear*} / right-click
 * harvest semantics:</p>
 * <ul>
 *   <li>{@link BeehiveBlock#HONEY_LEVEL} reset to 0 via direct
 *       {@code setBlock} on the modified BlockState.</li>
 *   <li>Output stack constructed manually and added to NPC
 *       inventory.</li>
 *   <li>Shears damage applied via {@link ToolUseSupport} for HONEYCOMB
 *       harvest; glass bottle consumed via direct inventory shrink for
 *       HONEY_BOTTLE harvest.</li>
 *   <li>Bee aggression: vanilla anger is bypassed because we don't
 *       invoke the interaction path. v1 simplification — once Track E
 *       APIARY plots gain campfire placement, the smoke check below
 *       becomes load-bearing; for now {@link #campfireNearby} logs a
 *       diagnostic but doesn't gate harvest.</li>
 * </ul>
 *
 * <h3>Mutual exclusion</h3>
 * <p>Registered alongside ShepherdBehavior + FarmerBehavior in the
 * FARMER WORK priority-0 bucket; WALK_TARGET memory contention
 * (VALUE_ABSENT requirement) keeps them mutually exclusive.
 * FarmerBehavior defers via {@link #hasActionableWork} as a safety
 * belt.</p>
 */
public class BeekeeperBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float WALK_SPEED = 0.65f;
    private static final int CLOSE_ENOUGH_APIARY = 3;
    private static final int CLOSE_ENOUGH_HIVE = 1;
    private static final double ARRIVAL_DIST_SQ_APIARY = 16.0;
    private static final double ARRIVAL_DIST_SQ_HIVE = 4.0;
    private static final int MAX_RUN = 6000;
    private static final int HARVEST_TICKS = 40;
    private static final int BEEKEEPING_XP_PER_ACTION = 2;
    /** HONEYCOMB family-stock cap. Higher than the bottle quota
     *  because shears are durable; sustained honeycomb production
     *  doesn't drain a consumable input. */
    private static final int HONEYCOMB_STOCK_QUOTA = 16;
    /** HONEY_BOTTLE family-stock cap. Lower than the honeycomb quota
     *  because each bottling consumes one glass bottle; restricting
     *  the quota avoids over-buying bottles from market. */
    private static final int HONEY_BOTTLE_STOCK_QUOTA = 8;
    /** Vanilla hive full-honey state. */
    private static final int HONEY_LEVEL_FULL = 5;
    /** Smoke-detection radius. Vanilla campfire-anti-anger uses 5
     *  blocks directly below; we check a small box around and below
     *  the hive for any campfire (lit or otherwise). */
    private static final int CAMPFIRE_SCAN_RADIUS = 5;

    private enum Phase {
        WALKING_TO_APIARY,
        APPROACHING_HIVE,
        HARVESTING,
        DEPOSITING,
        DONE
    }

    private enum Output { HONEYCOMB, HONEY_BOTTLE }

    private Phase phase;
    private BlockPos apiaryAnchor;
    private BlockPos targetHive;
    private Building farmhouse;
    private FarmPlot apiaryPlot;
    private Output chosenOutput;
    private Output lastHarvest; // per-NPC instance state for alternation
    private long startTick;
    private int subTimer;

    public BeekeeperBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    /**
     * Static probe for FarmerBehavior's defer guard. Returns true when
     * this NPC has actionable beekeeper work right now — FARMER
     * profession, BEEKEEPER role, bee roster exists, and at least one
     * of (shears, glass_bottle) is in inventory while the matching
     * stock is below quota. Whether a physically-full hive exists is
     * NOT checked here (avoiding the block scan on every FARMER tick);
     * the per-tick {@link #checkExtraStartConditions} handles that.
     */
    public static boolean hasActionableWork(ServerLevel level, TownspersonMob entity) {
        if (entity.getProfession() != Profession.FARMER
                && entity.getProfession() != Profession.FARMHAND) return false;
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);
        if (role != FarmRole.BEEKEEPER) return false;
        Building farmhouse = entity.getAssignedBuildingId()
                .flatMap(VillageSavedData.get(level)::getBuildingById).orElse(null);
        if (farmhouse == null) return false;
        BuildingRoster roster = RosterSavedData.get(level)
                .getRoster(farmhouse.getId(), AnimalRosterDefinitions.BEE).orElse(null);
        if (roster == null) return false;
        if (roster.countAdults() == 0) return false;

        // At least one usable (tool, output, quota) combo must be live.
        boolean canMakeComb = hasItem(entity, Items.SHEARS)
                && countItemInBuilding(level, farmhouse, Items.HONEYCOMB)
                        < HONEYCOMB_STOCK_QUOTA;
        boolean canMakeBottle = hasItem(entity, Items.GLASS_BOTTLE)
                && countItemInBuilding(level, farmhouse, Items.HONEY_BOTTLE)
                        < HONEY_BOTTLE_STOCK_QUOTA;
        return canMakeComb || canMakeBottle;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!entity.isWorkTime()) return false;
        if (!hasActionableWork(level, entity)) return false;

        VillageSavedData vdata = VillageSavedData.get(level);
        Building fh = entity.getAssignedBuildingId().flatMap(vdata::getBuildingById)
                .orElse(null);
        if (fh == null) return false;
        BuildingRoster roster = RosterSavedData.get(level)
                .getRoster(fh.getId(), AnimalRosterDefinitions.BEE).orElse(null);
        if (roster == null) return false;

        // BEE roster's boundPlotId points at its APIARY plot. APIARY
        // plots don't currently rotate (no analog of PastureRotation
        // for apiaries yet), so the binding is fixed at seed time.
        FarmPlot plot = vdata.getFarmPlotById(roster.boundPlotId().orElse(null))
                .orElse(null);
        if (plot == null || plot.getSubtype() != FarmPlot.PlotSubtype.APIARY) {
            return false;
        }

        // Pick output mode + verify a full hive exists in the plot.
        Output mode = chooseOutput(level, fh, entity);
        if (mode == null) return false;
        BlockPos hive = findFullHive(level, plot);
        if (hive == null) return false;

        this.farmhouse    = fh;
        this.apiaryPlot   = plot;
        this.apiaryAnchor = plot.getOrigin();
        this.targetHive   = hive;
        this.chosenOutput = mode;
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != null && phase != Phase.DONE
                && (gameTime - startTick) < MAX_RUN;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        startTick = gameTime;
        subTimer = 0;
        phase = Phase.WALKING_TO_APIARY;
        entity.setCurrentActivity("Tending hives");
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(apiaryAnchor, WALK_SPEED, CLOSE_ENOUGH_APIARY));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case WALKING_TO_APIARY -> tickWalkingToApiary(level, entity);
            case APPROACHING_HIVE  -> tickApproachingHive(level, entity);
            case HARVESTING        -> tickHarvesting(level, entity, gameTime);
            case DEPOSITING        -> tickDepositing(level, entity, gameTime);
            case DONE              -> { /* canStillUse short-circuits */ }
        }
    }

    private void tickWalkingToApiary(ServerLevel level, TownspersonMob entity) {
        double distSq = entity.distanceToSqr(
                apiaryAnchor.getX() + 0.5, apiaryAnchor.getY(), apiaryAnchor.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ_APIARY) {
            // Re-pick a hive — the one chosen at start may have been
            // emptied by something else (player, world tick). If the
            // original is still full, we'll get it back here.
            BlockPos hive = findFullHive(level, apiaryPlot);
            if (hive == null) {
                entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                phase = Phase.DONE;
                return;
            }
            this.targetHive = hive;
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(hive, WALK_SPEED, CLOSE_ENOUGH_HIVE));
            phase = Phase.APPROACHING_HIVE;
            subTimer = 0;
            return;
        }
        if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickApproachingHive(ServerLevel level, TownspersonMob entity) {
        // Re-verify hive still full — race against external harvest.
        BlockState s = level.getBlockState(targetHive);
        if (!(s.getBlock() instanceof BeehiveBlock)
                || s.getValue(BeehiveBlock.HONEY_LEVEL) < HONEY_LEVEL_FULL) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.DONE;
            return;
        }
        double distSq = entity.distanceToSqr(
                targetHive.getX() + 0.5, targetHive.getY(), targetHive.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ_HIVE) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.HARVESTING;
            subTimer = 0;
            return;
        }
        if (subTimer > 400 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickHarvesting(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getLookControl().setLookAt(
                targetHive.getX() + 0.5, targetHive.getY() + 0.5, targetHive.getZ() + 0.5);
        if (subTimer < HARVEST_TICKS) return;

        BlockState hiveState = level.getBlockState(targetHive);
        if (!(hiveState.getBlock() instanceof BeehiveBlock)
                || hiveState.getValue(BeehiveBlock.HONEY_LEVEL) < HONEY_LEVEL_FULL) {
            phase = Phase.DONE;
            return;
        }

        // v1 smoke note — not gating, just diagnosing. Track E plot
        // designer should add campfires so this logs "smoked".
        boolean smoked = campfireNearby(level, targetHive);

        Item produced;
        int count;
        if (chosenOutput == Output.HONEYCOMB) {
            // Damage shears via the canonical helper.
            ToolUseSupport.useToolFromInventory(entity,
                    s -> s.getItem() == Items.SHEARS, level, InteractionHand.MAIN_HAND);
            produced = Items.HONEYCOMB;
            count = 3; // vanilla shear yields 3 honeycomb
            level.playSound(null, targetHive,
                    SoundEvents.BEEHIVE_SHEAR, SoundSource.NEUTRAL, 1.0f, 1.0f);
        } else {
            // Consume one glass bottle from inventory.
            if (!consumeOne(entity, Items.GLASS_BOTTLE)) {
                phase = Phase.DONE;
                return;
            }
            produced = Items.HONEY_BOTTLE;
            count = 1;
            level.playSound(null, targetHive,
                    SoundEvents.BOTTLE_FILL_DRAGONBREATH, SoundSource.NEUTRAL, 1.0f, 1.0f);
        }

        // Reset HONEY_LEVEL via direct state mutation. This bypasses
        // vanilla's BeehiveBlock interaction path (which would trigger
        // bee anger without smoke); v1 accepts that simplification.
        level.setBlock(targetHive,
                hiveState.setValue(BeehiveBlock.HONEY_LEVEL, 0), 3);
        entity.getPersonalInventory().addItem(new ItemStack(produced, count));
        this.lastHarvest = chosenOutput;

        LOGGER.info("[BeekeeperBehavior] {} (BEEKEEPING {}) harvested {} from hive @ {} "
                + "(apiary {}, smoke={})",
                entity.getNpcName(),
                entity.getSkills().getLevel(Skill.BEEKEEPING),
                produced, targetHive.toShortString(),
                apiaryPlot.getId(), smoked);

        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(farmhouse.getShape().getOrigin(),
                        WALK_SPEED, CLOSE_ENOUGH_APIARY));
        phase = Phase.DEPOSITING;
        subTimer = 0;
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        BlockPos fhOrigin = farmhouse.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                fhOrigin.getX() + 0.5, fhOrigin.getY(), fhOrigin.getZ() + 0.5);
        if (distSq > ARRIVAL_DIST_SQ_APIARY) {
            if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
                // Couldn't reach farmhouse — produced item stays in
                // personal inventory; deposit retries next cycle.
                phase = Phase.DONE;
            }
            return;
        }
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

        Item producedItem = chosenOutput == Output.HONEYCOMB
                ? Items.HONEYCOMB : Items.HONEY_BOTTLE;
        var inv = entity.getPersonalInventory();
        int deposited = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != producedItem) continue;
            int before = s.getCount();
            BuildingStorageAccess.storeItem(level, farmhouse, s);
            int placed = before - s.getCount();
            if (placed > 0) {
                deposited += placed;
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            }
        }
        // FARMER_BEEKEEPER spec +50% bonus (FarmerSpecialtyMultiplier
        // recognizes BEEKEEPING + ANIMAL_HUSBANDRY targets per 6.7.1.6).
        float xp = BEEKEEPING_XP_PER_ACTION
                * FarmerSpecialtyMultiplier.of(entity, Skill.BEEKEEPING);
        SkillXp.award(entity, Skill.BEEKEEPING, xp, gameTime);
        LOGGER.info("[BeekeeperBehavior] {} deposited {} {} at farmhouse",
                entity.getNpcName(), deposited, producedItem);
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        this.targetHive = null;
        this.chosenOutput = null;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Output chooseOutput(ServerLevel level, Building fh, TownspersonMob entity) {
        boolean canMakeComb = hasItem(entity, Items.SHEARS)
                && countItemInBuilding(level, fh, Items.HONEYCOMB)
                        < HONEYCOMB_STOCK_QUOTA;
        boolean canMakeBottle = hasItem(entity, Items.GLASS_BOTTLE)
                && countItemInBuilding(level, fh, Items.HONEY_BOTTLE)
                        < HONEY_BOTTLE_STOCK_QUOTA;
        if (canMakeComb && canMakeBottle) {
            return lastHarvest == Output.HONEYCOMB ? Output.HONEY_BOTTLE : Output.HONEYCOMB;
        }
        if (canMakeComb) return Output.HONEYCOMB;
        if (canMakeBottle) return Output.HONEY_BOTTLE;
        return null;
    }

    /**
     * Scans the apiary plot bounds for a hive block (BEE_NEST or
     * BEEHIVE — both extend BeehiveBlock) at maximum HONEY_LEVEL.
     * Returns null when no full hive exists. Search is bounded by the
     * plot's radius around its origin; vertical range covers the
     * realiser's expected hive Y (centroid surface).
     */
    private static BlockPos findFullHive(ServerLevel level, FarmPlot plot) {
        BlockPos o = plot.getOrigin();
        int r = Math.max(plot.getRadius(), 4);
        // Scan a fairly tight Y band — the realiser places hives at
        // surface level of the centroid, so a +/- 4 band catches
        // sloped plots without wasting iterations.
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    cur.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    BlockState s = level.getBlockState(cur);
                    if (!(s.getBlock() instanceof BeehiveBlock)) continue;
                    if (s.getValue(BeehiveBlock.HONEY_LEVEL) >= HONEY_LEVEL_FULL) {
                        return cur.immutable();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks for any campfire (lit or unlit) in a small box around
     * and below the hive — vanilla's anti-anger criterion is "lit
     * campfire within 5 blocks directly below the hive", which
     * Track E APIARY placement should mirror. Returns true if any
     * CampfireBlock is found in the scan box. Diagnostic only in v1
     * (not gating).
     */
    private static boolean campfireNearby(ServerLevel level, BlockPos hive) {
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -CAMPFIRE_SCAN_RADIUS; dy <= 0; dy++) {
                    cur.set(hive.getX() + dx, hive.getY() + dy, hive.getZ() + dz);
                    if (level.getBlockState(cur).getBlock() instanceof CampfireBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasItem(TownspersonMob entity, Item item) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == item) return true;
        }
        return false;
    }

    /** Removes one of {@code item} from personal inventory. Returns
     *  false when none present (caller should defensively re-check). */
    private static boolean consumeOne(TownspersonMob entity, Item item) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            s.shrink(1);
            if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    private static int countItemInBuilding(ServerLevel level, Building b, Item item) {
        return BuildingStorageAccess.countItem(level, b, item);
    }
}
