package tterrag1112.life_in_the_village.Entities.Goals.Social;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.LifeStage;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Aging.RetirementState;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Phase 2 task 15. Elderly NPC with primary skill ≥ 60 picks a
 * younger mentee at the same workplace and hangs around their
 * WORK_PRIMARY phase.
 *
 * <p>The mentor's presence near the mentee triggers the mentorship
 * XP multiplier in
 * {@link tterrag1112.life_in_the_village.Npc.Apprentice.MentorshipBonus};
 * {@code MentorGoal} is the spatial primer that puts them within
 * range of each other.</p>
 */
public class MentorGoal extends Goal {

    /** Skill threshold for mentor eligibility. Spec line 172. */
    public static final int MENTOR_SKILL_THRESHOLD = 60;
    /** SOCIAL XP per mentor session. Spec line 180. */
    public static final int SOCIAL_XP_PER_SESSION = 1;
    /** How often (ticks) the mentor's session XP fires. */
    public static final int SESSION_XP_INTERVAL = 12000;
    /** How often (ticks) the goal scans for a mentee. */
    public static final int RETARGET_INTERVAL = 600;
    /** Search radius around the mentor's workplace. */
    public static final double WORKPLACE_RADIUS = 24.0;

    private final TownspersonMob entity;
    private TownspersonMob target;
    private int retargetTimer;
    private int sessionTimer;

    public MentorGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (entity.getLifeStage() != LifeStage.ELDERLY) return false;
        if (entity.getProfession() == Profession.NONE) return false;
        Skill primary = ProfessionSkills.of(entity.getProfession())
                .map(ProfessionSkills::primary).orElse(null);
        if (primary == null
                || entity.getSkills().getLevel(primary) < MENTOR_SKILL_THRESHOLD) {
            return false;
        }
        if (!entity.isWorkTime()) return false;
        retargetTimer++;
        if (retargetTimer < RETARGET_INTERVAL) return false;
        retargetTimer = 0;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        target = pickMentee(level, primary);
        if (target == null) return false;
        // Persist the mentor target on the retirement state.
        RetirementState rs = entity.getRetirementState();
        rs.setMentorTargetId(target.getUUID());
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && entity.isWorkTime();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void start() {
        sessionTimer = 0;
        entity.setCurrentActivity("Mentoring " + target.getNpcName());
        entity.getNavigation().moveTo(target, 0.7);
    }

    @Override
    public void tick() {
        if (target == null) return;
        sessionTimer++;
        // Hold near the mentee, looking at them.
        entity.getLookControl().setLookAt(target, 30f, 30f);
        if (entity.distanceToSqr(target) > 5.0 * 5.0
                && !entity.getNavigation().isInProgress()) {
            entity.getNavigation().moveTo(target, 0.7);
        }
        if (sessionTimer >= SESSION_XP_INTERVAL) {
            sessionTimer = 0;
            if (entity.level() instanceof ServerLevel level) {
                entity.getSkills().addXp(Skill.SOCIAL, SOCIAL_XP_PER_SESSION,
                        level.getGameTime());
            }
        }
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        entity.clearCurrentActivity();
        target = null;
    }

    private TownspersonMob pickMentee(ServerLevel level, Skill primary) {
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
        // Lowest-skill nearby = most useful to mentor.
        TownspersonMob best = nearby.get(0);
        int bestSkill = best.getSkills().getLevel(primary);
        for (TownspersonMob m : nearby) {
            int s = m.getSkills().getLevel(primary);
            if (s < bestSkill) {
                best = m;
                bestSkill = s;
            }
        }
        return best;
    }
}
