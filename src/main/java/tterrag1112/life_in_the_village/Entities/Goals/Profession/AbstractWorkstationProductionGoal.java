// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/AbstractWorkstationProductionGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionHelpers;
import tterrag1112.life_in_the_village.Village.Economy.Resources.ProductionRecipe;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Base class for profession goals that produce items at a single workstation.
 *
 * <h3>Pattern</h3>
 * Any profession that follows the "gather inputs from building → walk to
 * workstation → craft for N ticks → deposit output" flow can extend this
 * class. Blacksmith (with two stations) is the exception and has a
 * custom goal, but Carpenter, Miller, Weaver, Fletcher, Tailor, and
 * similar professions all fit this pattern.
 *
 * <h3>Phase state machine</h3>
 * <pre>
 *   IDLE → GATHERING → WALKING_TO_STATION → WORKING → DEPOSITING → IDLE
 * </pre>
 *
 * <h3>Subclass contract</h3>
 * Override the abstract methods to configure profession-specific behaviour:
 * <ul>
 *   <li>{@link #requiredBuildingType()} — which building this profession works in</li>
 *   <li>{@link #findWorkstation(ServerLevel, Building)} — which block to stand at</li>
 *   <li>{@link #chooseRecipe(ServerLevel, Building)} — what to produce</li>
 *   <li>{@link #calculateBatchSize(ServerLevel, ProductionRecipe)} — how much</li>
 * </ul>
 * Optional hooks:
 * <ul>
 *   <li>{@link #workSound()} — sound played during work pulses</li>
 *   <li>{@link #workToolItem()} — item held in main hand during work</li>
 *   <li>{@link #onProductionComplete} — XP awards, business-level hooks, etc.</li>
 * </ul>
 */
public abstract class AbstractWorkstationProductionGoal extends Goal {

    // ── Configuration ─────────────────────────────────────────────────
    protected static final int    IDLE_COOLDOWN_TICKS = 600;
    protected static final double INTERACT_RANGE_SQ   = 9.0;
    protected static final int    WORK_PULSE_INTERVAL = 20;
    protected static final int    DEPOSIT_MIN_EMPTY_SLOTS = 3;

    // ── State ─────────────────────────────────────────────────────────
    protected final TownspersonMob entity;
    protected Phase phase = Phase.IDLE;
    protected int idleCooldown = 0;
    protected int workTimer = 0;
    protected int workDuration = 0;

    protected Building         workBuilding;
    protected BlockPos         workstation;
    protected ProductionRecipe currentRecipe;
    protected int              currentBatchSize;

    protected enum Phase {
        IDLE,
        GATHERING,
        WALKING_TO_STATION,
        WORKING,
        DEPOSITING
    }

    protected AbstractWorkstationProductionGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // =========================================================================
    // Abstract hooks — subclasses must implement
    // =========================================================================

    /** The BuildingType this profession works in (e.g. CARPENTRY, BLACKSMITH). */
    protected abstract BuildingType requiredBuildingType();

    /** Find the workstation block position within the building. */
    protected abstract Optional<BlockPos> findWorkstation(ServerLevel level,
                                                          Building building);

    /**
     * Choose the recipe to execute next, or {@link Optional#empty()} if
     * nothing to do.
     */
    protected abstract Optional<ProductionRecipe> chooseRecipe(ServerLevel level,
                                                               Building building);

    /**
     * Compute the batch size for the chosen recipe. Return 0 if the NPC
     * can't actually execute even one batch.
     */
    protected abstract int calculateBatchSize(ServerLevel level,
                                              ProductionRecipe recipe);

    // =========================================================================
    // Optional hooks — subclasses can override
    // =========================================================================

    /** Sound played during work pulses. Default: anvil. */
    protected SoundEvent workSound() { return SoundEvents.ANVIL_USE; }

    /** Item held in main hand during work. Default: none. */
    protected Item workToolItem() { return Items.AIR; }

    /**
     * Called after a production batch finishes, before transitioning
     * to the deposit phase. Use for XP awards, business-level hooks, etc.
     */
    protected void onProductionComplete(ServerLevel level,
                                        ProductionRecipe recipe,
                                        int batchSize) {}

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        if (entity.isWorkingBlocked()) return false;
        if (idleCooldown > 0) { idleCooldown--; return false; }
        if (!(entity.level() instanceof ServerLevel level)) return false;

        workBuilding = ProductionHelpers
                .findAssignedBuilding(entity, level, requiredBuildingType())
                .orElse(null);
        return workBuilding != null;
    }

    @Override
    public void start() {
        phase = Phase.IDLE;
        analyze();
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        switch (phase) {
            case IDLE              -> { /* handled by canUse */ }
            case GATHERING         -> gather(level);
            case WALKING_TO_STATION -> walkToStation();
            case WORKING           -> work(level);
            case DEPOSITING        -> deposit(level);
        }
    }

    @Override
    public void stop() { goIdle(); }

    // =========================================================================
    // Phase: ANALYZING (called from start())
    // =========================================================================

    protected void analyze() {
        if (!(entity.level() instanceof ServerLevel level)) { goIdle(); return; }
        if (workBuilding == null) { goIdle(); return; }

        Optional<BlockPos> station = findWorkstation(level, workBuilding);
        if (station.isEmpty()) { goIdle(); return; }
        workstation = station.get();

        Optional<ProductionRecipe> recipe = chooseRecipe(level, workBuilding);
        if (recipe.isEmpty()) { goIdle(); return; }
        currentRecipe = recipe.get();

        currentBatchSize = calculateBatchSize(level, currentRecipe);
        if (currentBatchSize <= 0) { goIdle(); return; }

        phase = Phase.GATHERING;
    }

    // =========================================================================
    // Phase: GATHERING
    // =========================================================================

    protected void gather(ServerLevel level) {
        entity.setCurrentActivity("Gathering materials");

        int totalNeeded = currentBatchSize * currentRecipe.inputCount();
        if (!ProductionHelpers.takeFromBuilding(
                level, workBuilding, currentRecipe.input(), totalNeeded)) {
            goIdle();
            return;
        }
        entity.getPersonalInventory().addItem(
                new ItemStack(currentRecipe.input(), totalNeeded));

        Item tool = workToolItem();
        if (tool != Items.AIR) {
            entity.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(tool));
        }
        phase = Phase.WALKING_TO_STATION;
    }

    // =========================================================================
    // Phase: WALKING_TO_STATION
    // =========================================================================

    protected void walkToStation() {
        if (workstation == null) { goIdle(); return; }
        if (ProductionHelpers.moveToOrArrived(entity, workstation, INTERACT_RANGE_SQ)) {
            workTimer = 0;
            workDuration = currentRecipe.ticks() * currentBatchSize;
            phase = Phase.WORKING;
        }
    }

    // =========================================================================
    // Phase: WORKING
    // =========================================================================

    protected void work(ServerLevel level) {
        entity.setCurrentActivity("Crafting " + currentRecipe.output());

        workTimer++;
        if (workTimer % WORK_PULSE_INTERVAL == 0) {
            ProductionHelpers.performWorkPulse(entity, level, workstation, workSound());
        }

        if (workTimer >= workDuration) {
            // Consume inputs
            ProductionHelpers.removeFromInventory(entity, currentRecipe.input(),
                    currentBatchSize * currentRecipe.inputCount());

            // Produce outputs
            entity.getPersonalInventory().addItem(
                    new ItemStack(currentRecipe.output(),
                            currentBatchSize * currentRecipe.outputCount()));

            // Hook for subclasses (XP, business level, etc.)
            onProductionComplete(level, currentRecipe, currentBatchSize);

            workTimer = 0;
            phase = Phase.DEPOSITING;
        }
    }

    // =========================================================================
    // Phase: DEPOSITING
    // =========================================================================

    protected void deposit(ServerLevel level) {
        entity.setCurrentActivity("Depositing goods");
        if (workBuilding == null) { goIdle(); return; }

        BlockPos target = workBuilding.getShape().getOrigin();
        if (!ProductionHelpers.moveToOrArrived(entity, target, INTERACT_RANGE_SQ)) {
            return;
        }

        Set<Item> keepItems = workToolItem() != Items.AIR
                ? Set.of(workToolItem()) : Set.of();
        ProductionHelpers.depositAllToBuilding(level, workBuilding, entity, keepItems);

        goIdle();
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    protected void goIdle() {
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN_TICKS;
        currentRecipe = null;
        currentBatchSize = 0;
        workTimer = 0;
        workDuration = 0;
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
    }
}