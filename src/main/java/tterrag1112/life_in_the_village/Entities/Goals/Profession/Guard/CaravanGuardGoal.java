package tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Lore.HistoryTextGenerator;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.Trade.Caravan;
import tterrag1112.life_in_the_village.Village.Economy.Trade.CaravanSavedData;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class CaravanGuardGoal extends Goal {

    private static final double FOLLOW_DISTANCE  = 5.0;
    private static final double ATTACK_RANGE     = 2.5;
    private static final double FOLLOW_SPEED     = 0.65;
    private static final double ATTACK_SPEED     = 1.1;
    private static final double THREAT_RANGE     = 20.0;
    private static final int    THREAT_RECHECK   = 20;

    private final TownspersonMob entity;
    private LivingEntity threat  = null;
    private int threatTimer      = 0;
    // Offset so guards spread around merchant naturally
    private final double offsetAngle;

    public CaravanGuardGoal(TownspersonMob entity) {
        this.entity = entity;
        // Stable offset based on entity UUID
        this.offsetAngle = (Math.abs(entity.getUUID()
                .getMostSignificantBits()) % 8)
                * (Math.PI / 4);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK,
                Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        return entity.isCaravanMember();
    }

    @Override
    public boolean canContinueToUse() {
        return entity.isCaravanMember();
    }

    @Override
    public void start() {
        entity.setCurrentActivity("Guarding caravan...");
    }

    @Override
    public void stop() {
        entity.setTarget(null);
        entity.getNavigation().stop();
        entity.clearCurrentActivity();
        hasRecordedAttack = false;
        threat    = null;
        threatTimer = 0;
    }

    @Override
    public void tick() {
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
            entity.getNavigation().stop();
            entity.doHurtTarget(
                    (ServerLevel) entity.level(), threat);
        } else {
            entity.getNavigation().moveTo(
                    threat.getX(), threat.getY(),
                    threat.getZ(), ATTACK_SPEED);
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
            entity.getNavigation().moveTo(
                    targetX, targetY, targetZ,
                    FOLLOW_SPEED);
        } else if (entity.getNavigation().isDone()) {
            // Arrived — face the merchant
            entity.getLookControl().setLookAt(
                    merchant, 30f,
                    entity.getMaxHeadXRot());
        }

        entity.setCurrentActivity("Guarding caravan...");
    }

    private net.minecraft.world.entity.Entity getMerchant() {
        if (!(entity.level() instanceof ServerLevel level))
            return null;

        UUID caravanId = entity.getCaravanId().orElse(null);
        if (caravanId == null) return null;

        return CaravanSavedData.get(level)
                .getCaravan(caravanId)
                .map(Caravan::getMerchantEntityId)
                .map(level::getEntity)
                .orElse(null);
    }

    private LivingEntity findNearestThreat() {
        if (!(entity.level() instanceof ServerLevel level))
            return null;

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
}