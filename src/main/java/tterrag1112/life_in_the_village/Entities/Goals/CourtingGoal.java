package tterrag1112.life_in_the_village.Entities.Goals;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;

import java.util.Comparator;
import java.util.EnumSet;

public class CourtingGoal extends Goal {

    private static final int CHECK_INTERVAL = 6000;
    private static final int COURT_DURATION = 200; // time spent near target before proposing
    private static final int COURT_RANGE_SQ = 25;

    private final TownspersonMob entity;
    private int checkTimer = 0;
    private int courtTimer = 0;
    private TownspersonMob target = null;

    public CourtingGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Only HEAD adults without a spouse can court
        if (entity.getFamilyRole() != FamilyRole.HEAD) return false;
        if (entity.hasSpouse()) return false;
        if (!entity.isAdult()) return false;
        if (!entity.hasHome()) return false;

        checkTimer++;
        if (checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;

        if (!(entity.level() instanceof ServerLevel level)) return false;

        // Find an eligible adult without a home or spouse
        target = findCourtingTarget(level);
        return target != null;
    }

    @Override
    public void start() {
        courtTimer = 0;
        entity.setCurrentActivity("Flirting with " + target.getFirstName()
        );
    }

    @Override
    public boolean canContinueToUse() {
        return target != null
                && target.isAlive()
                && !entity.hasSpouse()
                && courtTimer < COURT_DURATION * 3;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (target == null) return;
        courtTimer++;

        double distSq = entity.distanceToSqr(target);

        if (distSq > COURT_RANGE_SQ) {
            entity.getNavigation().moveTo(target, 1.0);
            return;
        }

        // Near target — look at them
        entity.getLookControl().setLookAt(target, 30f, 30f);

        if (courtTimer >= COURT_DURATION) {
            // Propose — check target is still eligible
            if (isEligible(target)) {
                formCouple();
            }
            target = null;
        }
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
        target = null;
        courtTimer = 0;
        entity.clearCurrentActivity();
    }

    private void formCouple() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // Link the two NPCs
        entity.setSpouseId(target.getUUID());
        target.setSpouseId(entity.getUUID());

        // Move spouse into head's house
        target.setHouseId(entity.getHouseId().orElse(null));
        target.setFamilyRole(FamilyRole.SPOUSE);
        target.setHeadOfHouseholdId(entity.getUUID());
        target.setAssignedVillageName(entity.getAssignedVillageName().orElse(""));

        // Propagate head's surname to spouse
        entity.adoptSurname(entity.getSurname(), level);

        System.out.println("NPC " + entity.getNpcName()
                + " and " + target.getNpcName() + " formed a couple");
    }

    private TownspersonMob findCourtingTarget(ServerLevel level) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                        entity.getBoundingBox().inflate(32),
                        mob -> mob != entity
                                && isEligible(mob)
                ).stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr))
                .orElse(null);
    }

    private boolean isEligible(TownspersonMob mob) {
        return mob.isAdult()
                && !mob.hasSpouse()
                && mob.getFamilyRole() != FamilyRole.HEAD
                && mob.isAlive();
    }


}
