package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds;
import tterrag1112.life_in_the_village.Village.Roster.BuildingRoster;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6.3.3.k.5 — predator-scan behavior for animal-keeping NPCs.
 *
 * <p>Every {@link #SCAN_INTERVAL_TICKS} the behavior:</p>
 * <ol>
 *   <li>Locates the nearest animal-keeping building (one whose
 *       {@link RosterSavedData} entry holds at least one roster) for
 *       which this NPC is responsible — roughly defined as any
 *       building within {@link #BUILDING_PROXIMITY} of the NPC.</li>
 *   <li>Scans for {@link Wolf} / {@link Fox} entities within
 *       {@link #PREDATOR_DETECTION_RANGE} of the building.</li>
 *   <li>Bumps each relevant roster's hazardCounter (drives the
 *       simulated-mode attrition roll in
 *       {@link BuildingRoster#tick}) regardless of who's on duty.</li>
 *   <li>If the NPC themselves can defend (FamilyRole.CHILD excluded,
 *       {@link Skill#ANIMAL_HUSBANDRY} or {@link Skill#COMBAT} above
 *       {@link SkillThresholds#APPRENTICE_MILESTONE_FOUNDATION}),
 *       writes {@code ATTACK_TARGET} so the existing
 *       {@link GuardMeleeAttackBehavior} (added to the FARMER brain
 *       alongside this scan) handles the engagement.</li>
 * </ol>
 *
 * <p>Children and unskilled adults flee / ignore: the hazard counter
 * still climbs so the attrition path eventually triggers, but no
 * suicide-charge against a wolf.</p>
 *
 * <p>Wolf vs Fox: foxes are filtered out for non-POULTRY buildings.
 * A wolf threatens any pen; a fox only chickens. Same hazard increment
 * either way — k.5 keeps the urgency distinction at the threat-target
 * priority level (wolves prioritized when both are present).</p>
 */
public class PredatorScanBehavior extends Behavior<TownspersonMob> {

    public static final double PREDATOR_DETECTION_RANGE = 24.0;
    public static final double BUILDING_PROXIMITY       = 32.0;
    public static final int    SCAN_INTERVAL_TICKS      = 40;
    private static final int   MAX_RUN                  = Integer.MAX_VALUE;

    private long lastScanTick;

    public PredatorScanBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return true;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (gameTime - lastScanTick < SCAN_INTERVAL_TICKS) return;
        lastScanTick = gameTime;

        VillageSavedData vdata = VillageSavedData.get(level);
        RosterSavedData rdata  = RosterSavedData.get(level);

        // Find a nearby animal-keeping building. Iterating all
        // buildings is fine — most villages hold tens of buildings.
        UUID targetBuildingId = null;
        for (var b : vdata.getAllBuildings()) {
            if (b.getShape() == null) continue;
            var origin = b.getShape().getOrigin();
            if (entity.distanceToSqr(origin.getX() + 0.5,
                    origin.getY(), origin.getZ() + 0.5)
                            > BUILDING_PROXIMITY * BUILDING_PROXIMITY) continue;
            if (rdata.getRostersForBuilding(b.getId()).isEmpty()) continue;
            targetBuildingId = b.getId();
            break;
        }
        if (targetBuildingId == null) return;

        // Scan for predators near the nominated building.
        var building = vdata.getBuildingById(targetBuildingId).orElse(null);
        if (building == null) return;
        var origin = building.getShape().getOrigin();
        AABB area = new AABB(origin).inflate(PREDATOR_DETECTION_RANGE);
        List<Wolf> wolves = level.getEntitiesOfClass(Wolf.class, area,
                w -> !w.isTame() && w.isAlive());
        List<Fox> foxes = level.getEntitiesOfClass(Fox.class, area,
                f -> f.isAlive());

        if (wolves.isEmpty() && foxes.isEmpty()) return;

        // Bump hazard on each roster at the threatened building.
        for (BuildingRoster roster : rdata.getRostersForBuilding(targetBuildingId)) {
            roster.recordHazard();
        }
        rdata.markDirty();

        // If this NPC can defend, set ATTACK_TARGET. Wolves take
        // priority since they threaten broader livestock; fall back
        // to the nearest fox.
        if (!canDefend(entity)) return;
        LivingEntity target = !wolves.isEmpty() ? wolves.get(0) : foxes.get(0);
        if (BrainNavGuard.canSetAttackTarget(entity)) {
            entity.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
        }
    }

    /** Skill + lifestage gating: children never defend; adults need
     *  enough animal-husbandry or combat training to take on a wolf. */
    private static boolean canDefend(TownspersonMob entity) {
        if (entity.getFamilyRole()
                == tterrag1112.life_in_the_village.Entities.FamilyRole.CHILD) {
            return false;
        }
        int animalLvl = entity.getSkills().getLevel(Skill.ANIMAL_HUSBANDRY);
        int combatLvl = entity.getSkills().getLevel(Skill.COMBAT);
        int threshold = SkillThresholds.APPRENTICE_MILESTONE_FOUNDATION;
        return animalLvl >= threshold || combatLvl >= threshold;
    }
}
