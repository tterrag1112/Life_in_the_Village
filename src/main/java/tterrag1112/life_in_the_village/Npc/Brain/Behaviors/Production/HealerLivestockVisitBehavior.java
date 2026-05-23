package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Roster.BuildingRoster;
import tterrag1112.life_in_the_village.Village.Roster.RosterSavedData;

import java.util.UUID;

/**
 * Phase 6.3.3.k.6 — HEALER visits a livestock-keeping building with
 * elevated diseaseLevel and reduces it by {@link #TREATMENT_REDUCTION}
 * per visit (the big lever vs. the FARMER-side passive recovery's
 * -1/cycle).
 *
 * <p>Skill-gated on {@code VILLAGE_MEDICINE ≥ INTERMEDIATE (40)} per
 * the spec design call. Composes alongside the existing
 * {@link HealerBehavior} at WORK @ priority 1 so it doesn't preempt
 * NPC-patient treatment (which is more urgent).</p>
 *
 * <p>Phase machine is intentionally simple: SEARCH (find diseased
 * building) → WALKING (approach) → TREATING (apply reduction +
 * MEDICINE XP). The visit completes after one application; the
 * next session starts after the cooldown so multiple buildings get
 * served in rotation.</p>
 */
public class HealerLivestockVisitBehavior extends Behavior<TownspersonMob> {

    public static final int    TREATMENT_REDUCTION   = 3;
    public static final int    TREATMENT_TICKS       = 200;
    public static final int    MEDICINE_XP_PER_VISIT = 3;
    public static final double SCAN_RADIUS           = 48.0;
    public static final double ARRIVAL_SQ            = 9.0;
    public static final float  WALK_SPEED            = 0.6f;
    private static final int   CLOSE_ENOUGH          = 2;
    private static final int   MAX_RUN               = Integer.MAX_VALUE;

    private enum Phase { SEARCH, WALKING, TREATING }

    private Phase phase = Phase.SEARCH;
    private UUID  target;
    private int   timer;

    public HealerLivestockVisitBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (entity.getSkills().getLevel(Skill.VILLAGE_MEDICINE)
                < SkillThresholds.APPRENTICE_MILESTONE_INTERMEDIATE) return false;
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return target != null || phase == Phase.SEARCH;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.SEARCH;
        timer = 0;
        target = null;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        switch (phase) {
            case SEARCH   -> tickSearch(level, entity);
            case WALKING  -> tickWalking(level, entity);
            case TREATING -> tickTreating(level, entity, gameTime);
        }
    }

    private void tickSearch(ServerLevel level, TownspersonMob entity) {
        VillageSavedData vdata = VillageSavedData.get(level);
        RosterSavedData rdata  = RosterSavedData.get(level);
        Building best = null;
        int bestDisease = 0;
        for (var b : vdata.getAllBuildings()) {
            if (b.getShape() == null) continue;
            BlockPos origin = b.getShape().getOrigin();
            if (entity.distanceToSqr(origin.getX() + 0.5,
                    origin.getY(), origin.getZ() + 0.5)
                            > SCAN_RADIUS * SCAN_RADIUS) continue;
            int maxDisease = 0;
            for (BuildingRoster roster : rdata.getRostersForBuilding(b.getId())) {
                if (roster.diseaseLevel() > maxDisease) maxDisease = roster.diseaseLevel();
            }
            if (maxDisease > bestDisease) { best = b; bestDisease = maxDisease; }
        }
        if (best == null || bestDisease == 0) {
            // Nothing to do this session; stop the behavior.
            target = null;
            doStop(entity);
            return;
        }
        target = best.getId();
        phase = Phase.WALKING;
        BlockPos origin = best.getShape().getOrigin();
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(origin, WALK_SPEED, CLOSE_ENOUGH));
    }

    private void tickWalking(ServerLevel level, TownspersonMob entity) {
        if (target == null) { doStop(entity); return; }
        var b = VillageSavedData.get(level).getBuildingById(target).orElse(null);
        if (b == null || b.getShape() == null) { doStop(entity); return; }
        BlockPos origin = b.getShape().getOrigin();
        if (entity.distanceToSqr(origin.getX() + 0.5,
                origin.getY(), origin.getZ() + 0.5) <= ARRIVAL_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.TREATING;
            timer = 0;
        }
    }

    private void tickTreating(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (target == null) { doStop(entity); return; }
        timer++;
        if (timer < TREATMENT_TICKS) return;
        RosterSavedData rdata = RosterSavedData.get(level);
        boolean any = false;
        for (BuildingRoster roster : rdata.getRostersForBuilding(target)) {
            if (roster.diseaseLevel() > 0) {
                roster.adjustDiseaseLevel(-TREATMENT_REDUCTION);
                any = true;
            }
        }
        if (any) {
            rdata.markDirty();
            SkillXp.award(entity, Skill.VILLAGE_MEDICINE,
                    MEDICINE_XP_PER_VISIT, gameTime);
        }
        doStop(entity);
    }

    private void doStop(TownspersonMob entity) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        target = null;
        timer = 0;
        phase = Phase.SEARCH;
    }
}
