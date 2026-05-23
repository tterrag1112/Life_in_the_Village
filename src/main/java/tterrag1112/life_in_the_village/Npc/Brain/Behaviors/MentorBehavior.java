package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Aging.RetirementState;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6.2.c — migrated from {@code MentorGoal}. Universal SOCIAL @ 9.
 *
 * <p>Spec-directed change: the original goal gated on
 * {@code entity.isWorkTime()}, but the migration spec places this in
 * SOCIAL activity (the SOCIAL hours bracket the WORK shifts in our
 * schedule, so mentor + mentee may still be at the workplace during
 * social breaks). The workplace-proximity scan is preserved so the
 * behavior naturally no-ops if mentee isn't nearby.
 *
 * <p>Mentee lookup: spatial scan via {@code level.getEntitiesOfClass}
 * within {@link #WORKPLACE_RADIUS} of the mentor, filtered by same
 * profession + same assigned building + skill &lt; threshold. Mentor's
 * {@link RetirementState#setMentorTargetId} is set so MentorshipBonus
 * picks up the relationship.
 */
public class MentorBehavior extends Behavior<TownspersonMob> {

    public static final int MENTOR_SKILL_THRESHOLD =
            tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds.MENTOR_SKILL_THRESHOLD;
    public static final int SOCIAL_XP_PER_SESSION = 1;
    public static final int SESSION_XP_INTERVAL = 12000;
    public static final double WORKPLACE_RADIUS = 24.0;
    /** Phase 6.3.3.j.4.B — advise gesture cadence within a session.
     *  Fires every ~30s of in-game time while the mentor is in
     *  follow range, so the apprentice visibly sees the mentor
     *  teaching rather than just standing nearby. */
    public static final int ADVISE_INTERVAL = 600;
    public static final int APPRENTICE_XP_PER_ADVISE = 1;

    private static final long COOLDOWN_TICKS = 1200L;
    private static final double FOLLOW_DIST_SQ = 5.0 * 5.0;
    private static final float WALK_SPEED = 0.7f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = SESSION_XP_INTERVAL + 600;

    private TownspersonMob target;
    private int sessionTimer;

    public MentorBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.MENTOR_SESSION_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.getLifeStage() != LifeStage.ELDERLY) return false;
        if (entity.getProfession() == Profession.NONE) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        Skill primary = ProfessionSkills.of(entity.getProfession())
                .map(ProfessionSkills::primary).orElse(null);
        if (primary == null
                || entity.getSkills().getLevel(primary) < MENTOR_SKILL_THRESHOLD) {
            return false;
        }
        target = pickMentee(level, entity, primary);
        if (target == null) return false;
        RetirementState rs = entity.getRetirementState();
        rs.setMentorTargetId(target.getUUID());
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return target != null && target.isAlive();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        sessionTimer = 0;
        entity.setCurrentActivity("Mentoring " + target.getNpcName());
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (target == null) return;
        sessionTimer++;
        entity.getLookControl().setLookAt(target, 30f, 30f);
        // Refresh WALK_TARGET if we drift out of follow range — mentee moves.
        if (entity.distanceToSqr(target) > FOLLOW_DIST_SQ) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(target.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
        } else {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
        // Phase 6.3.3.j.4.B — advise gesture. When the mentor is in
        // close follow range of the mentee, periodically fire a
        // visible chirp + grant the mentee XP in their profession
        // primary skill. SkillXp.award composes the mentee multiplier
        // automatically — the mentee gets a real on-spec boost while
        // the elder is actively engaged, not just standing nearby.
        if (sessionTimer % ADVISE_INTERVAL == 0
                && entity.distanceToSqr(target) <= FOLLOW_DIST_SQ) {
            Skill menteePrimary = ProfessionSkills.of(target.getProfession())
                    .map(ProfessionSkills::primary).orElse(null);
            if (menteePrimary != null) {
                SkillXp.award(target, menteePrimary,
                        APPRENTICE_XP_PER_ADVISE, gameTime);
            }
            level.playSound(null, entity.blockPosition(),
                    SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.35f,
                    0.8f + entity.getRandom().nextFloat() * 0.2f);
        }
        if (sessionTimer >= SESSION_XP_INTERVAL) {
            sessionTimer = 0;
            SkillXp.award(entity, Skill.SOCIAL, SOCIAL_XP_PER_SESSION, gameTime);
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.MENTOR_SESSION_COOLDOWN.get(), gameTime, COOLDOWN_TICKS);
        target = null;
        sessionTimer = 0;
    }

    private static TownspersonMob pickMentee(ServerLevel level, TownspersonMob entity, Skill primary) {
        UUID building = entity.getAssignedBuildingId().orElse(null);
        if (building == null) return null;
        List<TownspersonMob> nearby = level.getEntitiesOfClass(
                TownspersonMob.class,
                entity.getBoundingBox().inflate(WORKPLACE_RADIUS),
                m -> m != entity
                        && (m.getLifeStage() == LifeStage.ADULT
                                || m.getLifeStage() == LifeStage.TEEN)
                        && m.getProfession() == entity.getProfession()
                        && m.getAssignedBuildingId().map(building::equals).orElse(false)
                        && m.getSkills().getLevel(primary) < MENTOR_SKILL_THRESHOLD);
        if (nearby.isEmpty()) return null;
        TownspersonMob best = nearby.get(0);
        int bestSkill = best.getSkills().getLevel(primary);
        for (TownspersonMob m : nearby) {
            int s = m.getSkills().getLevel(primary);
            if (s < bestSkill) { best = m; bestSkill = s; }
        }
        return best;
    }
}
