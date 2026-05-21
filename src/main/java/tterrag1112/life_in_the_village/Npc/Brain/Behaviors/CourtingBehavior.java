package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.HouseholdManager;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;

import java.util.Comparator;

/**
 * Phase 6.2.b — migrated from {@code CourtingGoal}. Universal SOCIAL
 * activity. HEAD-role adults without a spouse scan for an eligible
 * partner, walk over, court for a fixed duration, then either form a
 * couple (via {@code formCouple}) or dissolve.
 *
 * <p>Replaces the goal's {@code checkTimer} with a
 * {@code COURTING_COOLDOWN} TTL memory (6000 ticks) gating the
 * 32-block scan in {@code checkExtraStartConditions}.
 *
 * <p>External APIs (HouseholdManager, VillageSavedData,
 * NpcLifeEventBus, HistoryTextGenerator) preserved 1:1.
 */
public class CourtingBehavior extends Behavior<TownspersonMob> {

    private static final int COURT_DURATION = 200;
    private static final int COURT_RANGE_SQ = 25;
    private static final long COOLDOWN_TICKS = 6000L;
    private static final float WALK_SPEED = 1.0f;
    private static final int CLOSE_ENOUGH = 1;
    private static final int MAX_RUN = COURT_DURATION * 3;

    private enum Phase { WALKING_TO_TARGET, COURTING, DONE }

    private Phase phase = Phase.DONE;
    private TownspersonMob target;
    private int courtTimer;

    public CourtingBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.COURTING_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.getFamilyRole() != FamilyRole.HEAD) return false;
        if (entity.hasSpouse()) return false;
        if (!entity.isAdult()) return false;
        if (!entity.hasHome()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        target = findCourtingTarget(level, entity);
        return target != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (phase == Phase.DONE) return false;
        if (target == null || !target.isAlive()) return false;
        if (entity.hasSpouse()) return false;
        // Edge case from spec: target acquires a spouse mid-courting — dissolve.
        if (target.hasSpouse()) return false;
        return courtTimer < COURT_DURATION * 3;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.WALKING_TO_TARGET;
        courtTimer = 0;
        entity.setCurrentActivity("Flirting with " + target.getFirstName());
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (target == null) { phase = Phase.DONE; return; }
        courtTimer++;
        double distSq = entity.distanceToSqr(target);

        switch (phase) {
            case WALKING_TO_TARGET -> {
                if (distSq <= COURT_RANGE_SQ) {
                    entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                    phase = Phase.COURTING;
                } else {
                    // Target moves — refresh WALK_TARGET each tick.
                    entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(target.blockPosition(), WALK_SPEED, CLOSE_ENOUGH));
                }
            }
            case COURTING -> {
                entity.getLookControl().setLookAt(target, 30f, 30f);
                if (courtTimer >= COURT_DURATION) {
                    if (isEligible(target)) {
                        formCouple(level, entity);
                    }
                    target = null;
                    phase = Phase.DONE;
                }
            }
            case DONE -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        // Cool-down regardless of success — same cadence as the old CHECK_INTERVAL.
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.COURTING_COOLDOWN.get(), gameTime, COOLDOWN_TICKS);
        target = null;
        courtTimer = 0;
        phase = Phase.DONE;
    }

    // ── Eligibility / pairing (verbatim from CourtingGoal) ──────────────────

    private static TownspersonMob findCourtingTarget(ServerLevel level, TownspersonMob entity) {
        return level.getEntitiesOfClass(TownspersonMob.class,
                        entity.getBoundingBox().inflate(32),
                        mob -> mob != entity && isEligible(mob))
                .stream()
                .min(Comparator.comparingDouble(entity::distanceToSqr))
                .orElse(null);
    }

    private static boolean isEligible(TownspersonMob mob) {
        return mob.isAdult()
                && !mob.hasSpouse()
                && mob.getFamilyRole() != FamilyRole.HEAD
                && mob.isAlive();
    }

    private void formCouple(ServerLevel level, TownspersonMob entity) {
        entity.setSpouseId(target.getUUID());
        target.setSpouseId(entity.getUUID());

        tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.Married(
                        entity, target.getUUID()));
        tterrag1112.life_in_the_village.Npc.Events.NpcLifeEventBus.fire(
                new tterrag1112.life_in_the_village.Npc.Events.NpcLifeEvent.Married(
                        target, entity.getUUID()));

        target.setHouseId(entity.getHouseId().orElse(null));
        target.setFamilyRole(FamilyRole.SPOUSE);
        target.setHeadOfHouseholdId(entity.getUUID());
        target.setAssignedVillageName(entity.getAssignedVillageName().orElse(""));
        entity.adoptSurname(entity.getSurname(), level);

        VillageSavedData data = VillageSavedData.get(level);
        entity.getHouseId().ifPresent(houseId ->
                HouseholdManager.onNpcMovedHouse(
                        target.getUUID(), houseId, false, data, level.getGameTime()));

        entity.getAssignedVillageName()
                .flatMap(data::getVillageByName)
                .ifPresent(village -> data.getKingdomForVillage(village.getId())
                        .ifPresent(k -> {
                            k.getHistory().recordEvent(
                                    HistoryTextGenerator.notableMarriage(
                                            entity.getNpcName(), target.getNpcName(),
                                            village.getName(), level.getGameTime()),
                                    k.getName(), k.getRulerName(level));
                            data.setDirty();
                        }));
    }
}
