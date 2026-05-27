package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import tterrag1112.life_in_the_village.Village.Roster.PastureRotation;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

/**
 * Phase 6.7.2 — opportunistic shepherd-tier work for FARMERs whose
 * {@link FarmRole} pinned to {@link FarmRole#SHEPHERD} (via locked
 * FARMER_SHEPHERD specialization, see 6.7.1.7). Walks to the bound
 * pasture pen, performs a shear-and-deposit cycle, and rotates the
 * pen binding when grass quality drops.
 *
 * <h3>v1 scope simplifications</h3>
 * <ul>
 *   <li>Shearing is abstract: behavior arriving at the pen produces
 *       one wool stack into farmhouse storage rather than physically
 *       interacting with realized {@code Sheep} entities. Realized
 *       sheep are decorative (biome-color visual variety from
 *       {@link BuildingRoster#realizeNear}'s finalizeSpawn call), but
 *       the wool output is driven by the abstract roster machinery
 *       to keep the v1 design API-stable across Minecraft versions.
 *       A future content pass can layer actual SheepEntity.shear
 *       interaction on top.</li>
 *   <li>Wool color: deposits the species default WHITE_WOOL per
 *       {@link AnimalRosterDefinitions#SHEEP}. Biome-color variety
 *       lands when the abstract shear is upgraded to entity-shear.</li>
 *   <li>No predator response in v1 — handled separately by
 *       PredatorScanBehavior.</li>
 * </ul>
 *
 * <h3>Mutual exclusion with FarmerBehavior</h3>
 * <p>Both behaviors live in WORK priority 0 for FARMER. Registration
 * order puts ShepherdBehavior first so Brain selects it when its
 * gates pass; FarmerBehavior also explicitly defers via
 * {@link #hasActionableWork} so the SHEPHERD doesn't redundantly
 * enter generic-FARMER phases while shepherd work is pending.</p>
 */
public class ShepherdBehavior extends Behavior<TownspersonMob> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float WALK_SPEED = 0.65f;
    private static final int CLOSE_ENOUGH = 2;
    private static final double ARRIVAL_DIST_SQ = 9.0;
    private static final int MAX_RUN = 6000;
    private static final int SHEAR_TICKS = 60;
    private static final int SHEARING_XP_PER_ACTION = 2;
    /** Grass-quality threshold at or below which the current pen is
     *  considered exhausted; SHEPHERD triggers a rotation lookup. */
    private static final float ROTATION_QUALITY_THRESHOLD = 0.4f;
    /** Wool stack size per shearing pass. Matches vanilla's typical
     *  1-3 drop range, biased toward the lower end since this is a
     *  single "visit the pen and shear what you can" cycle, not a
     *  full sweep of the herd. */
    private static final int WOOL_PER_SHEAR = 2;

    private enum Phase { WALKING_TO_PEN, SHEARING, DEPOSITING, DONE }

    private Phase phase;
    private BlockPos penAnchor;
    private Building farmhouse;
    private BuildingRoster sheepRoster;
    private FarmPlot activePen;
    private long startTick;
    private int subTimer;

    public ShepherdBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    /**
     * Static probe for FarmerBehavior's defer guard. Returns true when
     * this NPC has actionable shepherd work right now — pinned to the
     * SHEPHERD role, FARMER profession, has a sheep roster with at
     * least one realized adult slot, and has shears available. False
     * when FarmerBehavior should take over (no shears, no sheep, role
     * not SHEPHERD, etc.).
     */
    public static boolean hasActionableWork(ServerLevel level, TownspersonMob entity) {
        if (entity.getProfession() != Profession.FARMER
                && entity.getProfession() != Profession.FARMHAND) return false;
        FarmRole role = ProfessionRoleManager.getRole(entity, FarmRole.class);
        if (role != FarmRole.SHEPHERD) return false;
        Building farmhouse = entity.getAssignedBuildingId()
                .flatMap(VillageSavedData.get(level)::getBuildingById).orElse(null);
        if (farmhouse == null) return false;
        BuildingRoster roster = RosterSavedData.get(level)
                .getRoster(farmhouse.getId(), AnimalRosterDefinitions.SHEEP).orElse(null);
        if (roster == null) return false;
        // At least one Simulated-or-Realized adult — the SHEPHERD can
        // do something useful regardless of realization state (rotate
        // a depleted pen, walk the pen for visibility, deposit wool).
        if (roster.countAdults() == 0) return false;
        if (!hasShears(entity)) return false;
        return true;
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
                .getRoster(fh.getId(), AnimalRosterDefinitions.SHEEP).orElse(null);
        if (roster == null) return false;

        // Rotate if current bound pen has depleted grass (or no
        // binding at all). chooseAndBindActivePen updates the roster's
        // boundPlotId in-place; we then resolve the spawn anchor.
        FarmPlot pen = vdata.getFarmPlotById(roster.boundPlotId().orElse(null))
                .orElse(null);
        boolean needsRotation = pen == null
                || pen.getSoilQuality() <= ROTATION_QUALITY_THRESHOLD;
        if (needsRotation) {
            pen = PastureRotation.chooseAndBindActivePen(level, fh.getId(), roster)
                    .orElse(null);
        }
        if (pen == null) return false;

        this.farmhouse   = fh;
        this.sheepRoster = roster;
        this.activePen   = pen;
        this.penAnchor   = pen.getOrigin();
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
        phase = Phase.WALKING_TO_PEN;
        entity.setCurrentActivity("Tending sheep");
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(penAnchor, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case WALKING_TO_PEN -> tickWalking(entity);
            case SHEARING       -> tickShearing(level, entity, gameTime);
            case DEPOSITING     -> tickDepositing(level, entity, gameTime);
            case DONE           -> { /* canStillUse short-circuits */ }
        }
    }

    private void tickWalking(TownspersonMob entity) {
        double distSq = entity.distanceToSqr(
                penAnchor.getX() + 0.5, penAnchor.getY(), penAnchor.getZ() + 0.5);
        if (distSq <= ARRIVAL_DIST_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.SHEARING;
            subTimer = 0;
            return;
        }
        if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickShearing(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getLookControl().setLookAt(
                penAnchor.getX() + 0.5, penAnchor.getY() + 1.0, penAnchor.getZ() + 0.5);
        if (subTimer >= SHEAR_TICKS) {
            // Mark the pen as grazed; reduces grass quality and updates
            // lastGrazedTick so the next rotation lookup picks a fresher pen.
            if (activePen != null) activePen.onGrazed(gameTime);
            phase = Phase.DEPOSITING;
            subTimer = 0;
        }
    }

    private void tickDepositing(ServerLevel level, TownspersonMob entity, long gameTime) {
        ItemStack wool = new ItemStack(Items.WHITE_WOOL, WOOL_PER_SHEAR);
        BuildingStorageAccess.storeWithFallback(level, farmhouse, wool,
                entity.getPersonalInventory());
        // FARMER_SHEPHERD spec +50% bonus applied here (FarmerSpecialtyMultiplier
        // recognizes SHEPHERDING + ANIMAL_HUSBANDRY targets for FARMER_SHEPHERD
        // per 6.7.1.6 wiring). SkillXp.award stacks AMBITION + mentor multipliers.
        float xp = SHEARING_XP_PER_ACTION
                * FarmerSpecialtyMultiplier.of(entity, Skill.SHEPHERDING);
        SkillXp.award(entity, Skill.SHEPHERDING, xp, gameTime);
        LOGGER.info("[ShepherdBehavior] {} (SHEPHERDING {}) sheared at pen {}; "
                + "deposited {} wool; pen grass {}",
                entity.getNpcName(),
                entity.getSkills().getLevel(Skill.SHEPHERDING),
                activePen.getId(),
                WOOL_PER_SHEAR,
                String.format("%.2f", activePen.getSoilQuality()));
        phase = Phase.DONE;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private static boolean hasShears(TownspersonMob entity) {
        var inv = entity.getPersonalInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).getItem() == Items.SHEARS) return true;
        }
        return false;
    }
}
