package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;
import tterrag1112.life_in_the_village.Village.Travel.Roster;

import java.util.List;
import java.util.UUID;

public class CaravanGuardBehavior extends Behavior<TownspersonMob> {

    private static final double FOLLOW_DISTANCE  = 5.0;
    private static final double ATTACK_RANGE     = 2.5;
    private static final double FOLLOW_SPEED     = 0.65;
    private static final double ATTACK_SPEED     = 1.1;
    private static final double THREAT_RANGE     = 20.0;
    private static final int    THREAT_RECHECK   = 20;

    private TownspersonMob entity;

    public CaravanGuardBehavior() {
        super(com.google.common.collect.ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED
        ), 24000);
    }
    private LivingEntity threat  = null;
    private int threatTimer      = 0;
    // Offset so guards spread around merchant naturally
    private final double offsetAngle;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return entity.isCaravanMember();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return entity.isCaravanMember();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        entity.setCurrentActivity("Guarding caravan...");
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        entity.setTarget(null);
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        hasRecordedAttack = false;
        threat    = null;
        threatTimer = 0;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        // Check for threats periodically
        threatTimer++;
        if (threatTimer >= THREAT_RECHECK) {
            threatTimer = 0;
            threat = findNearestThreat();
        }

        if (threat != null && threat.isAlive()) {
            tickCombat();
        } else {
            threat = null;
            entity.setTarget(null);
            tickFollow();
        }
    }
    private boolean hasRecordedAttack = false;



    private void tickCombat() {
        // In CaravanGuardGoal.tickCombat, first time combat starts
        if (!hasRecordedAttack) {
            hasRecordedAttack = true;
            if (entity.level() instanceof ServerLevel sl) {
                CaravanSavedData caravanData =
                        CaravanSavedData.get(sl);
                VillageSavedData villageData =
                        VillageSavedData.get(sl);

                entity.getCaravanId()
                        .flatMap(caravanData::getCaravan)
                        .ifPresent(caravan -> {
                            String origin = villageData
                                    .getVillageById(
                                            caravan.getOriginVillageId())
                                    .map(v -> v.getName())
                                    .orElse("unknown");
                            String dest = villageData
                                    .getVillageById(
                                            caravan.getDestVillageId())
                                    .map(v -> v.getName())
                                    .orElse("unknown");
                            villageData.getKingdomForVillage(
                                            caravan.getOriginVillageId())
                                    .ifPresent(k -> {
                                        k.getHistory().recordEvent(
                                                HistoryTextGenerator
                                                        .caravanAttacked(
                                                                origin,
                                                                dest,
                                                                sl.getGameTime()),
                                                k.getName(),
                                                k.getRulerName(sl));
                                        villageData.setDirty();
                                    });
                        });
            }
        }


        entity.getLookControl().setLookAt(threat,
                30f, entity.getMaxHeadXRot());
        entity.setTarget(threat);

        double dist = entity.distanceTo(threat);
        if (dist <= ATTACK_RANGE) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            entity.doHurtTarget(
                    (ServerLevel) entity.level(), threat);
        } else {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    threat.getX(), threat.getY(),
                    threat.getZ(), ATTACK_SPEED));
        }
        entity.setCurrentActivity("Defending caravan!");
    }

    private void tickFollow() {
        net.minecraft.world.entity.Entity merchant =
                getMerchant();
        if (merchant == null) return;

        // Calculate offset position around merchant
        double targetX = merchant.getX()
                + Math.cos(offsetAngle) * FOLLOW_DISTANCE;
        double targetZ = merchant.getZ()
                + Math.sin(offsetAngle) * FOLLOW_DISTANCE;
        double targetY = merchant.getY();

        double distToTarget = entity.distanceTo(merchant);

        // Only move if too far from target position
        if (distToTarget > FOLLOW_DISTANCE + 2) {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET, navWalkTarget(
                    targetX, targetY, targetZ,
                    FOLLOW_SPEED));
        } else if (entity.getNavigation().isDone()) {
            // Arrived — face the merchant
            entity.getLookControl().setLookAt(
                    merchant, 30f,
                    entity.getMaxHeadXRot());
        }

        entity.setCurrentActivity("Guarding caravan...");
    }

    private net.minecraft.world.entity.Entity getMerchant() {

        UUID caravanId = entity.getCaravanId().orElse(null);
        if (caravanId == null) return null;
        ServerLevel level = (ServerLevel) entity.level();

        return CaravanSavedData.get(level)
                .getCaravan(caravanId)
                .map(Caravan::getRoster)
                .map(Roster::getPrincipalId)
                .map(level::getEntity)
                .orElse(null);
    }

    private LivingEntity findNearestThreat() {
        ServerLevel level = (ServerLevel) entity.level();

        List<LivingEntity> nearby = level.getNearbyEntities(
                LivingEntity.class,
                TargetingConditions.forCombat()
                        .ignoreLineOfSight(),
                entity,
                entity.getBoundingBox()
                        .inflate(THREAT_RANGE));

        return nearby.stream()
                .filter(e -> e != entity)
                .filter(LivingEntity::isAlive)
                .filter(e -> !(e instanceof TownspersonMob))
                .filter(e -> e instanceof Mob mob
                        && mob.getTarget() != null
                        && mob.getTarget()
                        instanceof TownspersonMob t
                        && t.isCaravanMember())
                .min(java.util.Comparator
                        .comparingDouble(entity::distanceTo))
                .orElse(null);
    }

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}