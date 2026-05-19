// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/Profession/Builder/BuilderMaintenanceGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Builder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingCondition;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;
import tterrag1112.life_in_the_village.Village.Decoration.VillageBiomeStyle;
import tterrag1112.life_in_the_village.Village.Decoration.VillageWeathering;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.*;

/**
 * Causes a BUILDER NPC to periodically walk to nearby weathered or
 * dilapidated buildings and perform repair work on them.
 *
 * <h3>Behaviour</h3>
 * <ol>
 *   <li>Scans village buildings for the worst-condition one.</li>
 *   <li>Navigates to the target building's entrance.</li>
 *   <li>Performs a "repair" animation (holding an axe/pickaxe, playing sounds)
 *       for {@code REPAIR_DURATION_TICKS}.</li>
 *       blocks in a radius and advances the building's
 *       {@link BuildingCondition} toward {@code MAINTAINED}.</li>
 * </ol>
 *
 * <h3>Condition tracking</h3>
 * {@link BuildingCondition} is stored on {@link Building} via a new
 * {@code condition} field (see integration note below). Until that field
 * is added to Building, this goal uses a local in-memory map keyed by
 * building UUID so it still compiles and functions immediately.
 */
public class BuilderMaintenanceGoal extends Goal {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** How often to scan for repair targets — every 5 in-game minutes. */
    private static final int  SCAN_INTERVAL       = 6000;
    /** How long the builder stands at the building performing repairs. */
    private static final int  REPAIR_DURATION     = 1200; // 1 in-game minute
    /** How close the builder must be before "working" starts. */
    private static final double INTERACT_RANGE_SQ  = 25.0; // 5 blocks

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private enum Phase { IDLE, WALKING, REPAIRING }

    private final TownspersonMob entity;
    private Phase  phase        = Phase.IDLE;
    private int    idleTimer    = 0;
    private int    repairTimer  = 0;
    private Building targetBuilding = null;



    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public BuilderMaintenanceGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!entity.isWorkTime()) return false;
        idleTimer++;
        if (idleTimer < SCAN_INTERVAL) return false;
        idleTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;
        targetBuilding = findRepairTarget(level);
        return targetBuilding != null;
    }

    @Override
    public void start() {
        phase       = Phase.WALKING;
        repairTimer = 0;
        entity.setCurrentActivity("Heading to maintain " + targetBuilding.getName());
        entity.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(Items.IRON_AXE));

        BlockPos dest = targetBuilding.getShape().getOrigin();
        entity.getNavigation().moveTo(
                dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0);
    }

    @Override
    public boolean canContinueToUse() {
        if (!entity.isWorkTime()) return false;
        return phase != Phase.IDLE && targetBuilding != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        switch (phase) {
            case WALKING  -> tickWalking(level);
            case REPAIRING -> tickRepairing(level);
            default -> {}
        }
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        entity.clearCurrentActivity();
        phase = Phase.IDLE;
    }

    // -------------------------------------------------------------------------
    // Phase: WALKING
    // -------------------------------------------------------------------------

    private void tickWalking(ServerLevel level) {
        if (targetBuilding == null) { stop(); return; }

        BlockPos dest = targetBuilding.getShape().getOrigin();
        double distSq = entity.distanceToSqr(
                dest.getX(), dest.getY(), dest.getZ());

        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getNavigation().stop();
            phase = Phase.REPAIRING;
            entity.setCurrentActivity("Maintaining " + targetBuilding.getName());
        } else if (!entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(
                    dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, 1.0);
        }
    }

    // -------------------------------------------------------------------------
    // Phase: REPAIRING
    // -------------------------------------------------------------------------

    private void tickRepairing(ServerLevel level) {
        repairTimer++;

        // Animation — look at the building and play sounds periodically
        if (repairTimer % 40 == 0) {
            BlockPos dest = targetBuilding.getShape().getOrigin();
            entity.getLookControl().setLookAt(
                    dest.getX(), dest.getY() + 1, dest.getZ());
            entity.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.WOOD_HIT, SoundSource.NEUTRAL,
                    0.5f, 0.8f + level.getRandom().nextFloat() * 0.4f);
        }

        if (repairTimer < REPAIR_DURATION) return;

        // Do the actual repair
        performRepair(level);
        phase = Phase.IDLE;
        targetBuilding = null;
    }

    // -------------------------------------------------------------------------
    // Repair logic
    // -------------------------------------------------------------------------

    private void performRepair(ServerLevel level) {
        if (targetBuilding == null) return;

        VillageBiomeStyle style = VillageBiomeStyle.detect(
                level, targetBuilding.getShape().getOrigin());

        // Reverse-apply weathering in a radius around the building
        // VillageWeathering.repair rolls back mossy blocks, cracks, and vines
        VillageWeathering.repair(level, targetBuilding, style,
                level.getRandom());


        // Update the building's condition in VillageSavedData
        VillageSavedData data = VillageSavedData.get(level);
        data.getBuildingById(targetBuilding.getId()).ifPresent(b -> {
            BuildingCondition current = b.getCondition();
            BuildingCondition repaired = current.repair();
            b.setCondition(repaired);
            data.markDirty();
        });

        entity.setCurrentActivity("Finished maintaining " + targetBuilding.getName());

        // Play a completion sound
        level.playSound(null, entity.blockPosition(),
                SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.6f, 1.0f);
    }

    // -------------------------------------------------------------------------
    // Target selection
    // -------------------------------------------------------------------------

    /**
     * Finds the highest-priority building in the village that needs repair.
     * Priority: RUINED > DILAPIDATED > WEATHERED (if not recently maintained).
     */
    private Building findRepairTarget(ServerLevel level) {
        return findPendingRepair(entity, level);
    }

    /**
     * Static cross-check used by {@link BuilderRepaintGoal} so the
     * maintenance goal stays dominant when both share a priority slot.
     * Returns the highest-priority building needing repair, or
     * {@code null} when nothing is pending.
     */
    public static Building findPendingRepair(TownspersonMob npc,
                                             ServerLevel level) {
        VillageSavedData data = VillageSavedData.get(level);
        Village village = npc.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .orElse(null);
        if (village == null) return null;

        return village.getBuildingIds().stream()
                .map(data::getBuildingById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(b -> b.getType() != BuildingType.TOWN_HALL)
                .max(Comparator.comparingInt(b ->
                        conditionPriority(b.getCondition())))
                .filter(b -> b.getCondition() != BuildingCondition.MAINTAINED
                        && b.getCondition() != BuildingCondition.NEW)
                .orElse(null);
    }

    private static int conditionPriority(BuildingCondition condition) {
        return switch (condition) {
            case RUINED      -> 4;
            case DILAPIDATED -> 3;
            case WEATHERED   -> 2;
            case NEW         -> 1;
            case MAINTAINED  -> 0;
        };
    }
}