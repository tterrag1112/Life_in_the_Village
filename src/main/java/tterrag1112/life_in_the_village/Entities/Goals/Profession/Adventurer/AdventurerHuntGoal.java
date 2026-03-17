package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Quest;

import java.util.EnumSet;
import java.util.List;

public class AdventurerHuntGoal extends Goal {

    private static final double SEARCH_RANGE    = 32.0;
    private static final double ATTACK_RANGE    = 2.5;
    private static final double MOVE_SPEED      = 1.1;
    private static final int    RECHECK_INTERVAL = 40;

    private final TownspersonMob entity;
    private LivingEntity huntTarget;
    private int recheckTimer = 0;

    public AdventurerHuntGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!entity.isAdventurerGroupMember()) return false;

        Quest quest = getActiveQuest();
        if (quest == null) return false;
        if (quest.getType() != Quest.QuestType.HUNT) return false;
        if (quest.getCurrentCount() >= quest.getTargetCount()) return false;

        huntTarget = findNearestTarget(quest.getTargetMob());
        return huntTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (huntTarget == null || !huntTarget.isAlive()) return false;

        Quest quest = getActiveQuest();
        if (quest == null) return false;
        if (quest.getCurrentCount() >= quest.getTargetCount()) return false;

        return entity.distanceTo(huntTarget) <= SEARCH_RANGE * 1.5;
    }

    @Override
    public void start() {
        entity.setTarget(huntTarget);
        updateActivityLabel();
    }

    @Override
    public void stop() {
        entity.setTarget(null);
        huntTarget = null;
        recheckTimer = 0;
    }

    @Override
    public void tick() {
        if (huntTarget == null || !huntTarget.isAlive()) {
            huntTarget = null;
            return;
        }

        recheckTimer++;

        // Look at target
        entity.getLookControl().setLookAt(huntTarget,
                30f, entity.getMaxHeadXRot());

        double dist = entity.distanceTo(huntTarget);

        if (dist <= ATTACK_RANGE) {
            // In range — stop and attack
            entity.getNavigation().stop();
            entity.doHurtTarget(
                    (ServerLevel) entity.level(), huntTarget);
        } else {
            // Move toward target
            entity.getNavigation().moveTo(
                    huntTarget.getX(),
                    huntTarget.getY(),
                    huntTarget.getZ(),
                    MOVE_SPEED);
        }

        // Periodically recheck for a closer target
        if (recheckTimer >= RECHECK_INTERVAL) {
            recheckTimer = 0;
            Quest quest = getActiveQuest();
            if (quest != null) {
                LivingEntity closer = findNearestTarget(
                        quest.getTargetMob());
                if (closer != null && closer != huntTarget
                        && entity.distanceTo(closer)
                        < entity.distanceTo(huntTarget)) {
                    huntTarget = closer;
                    entity.setTarget(huntTarget);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Target finding
    // -------------------------------------------------------------------------

    private LivingEntity findNearestTarget(String targetMobId) {
        if (!(entity.level() instanceof ServerLevel level)) return null;
        if (targetMobId == null) return null;

        Identifier targetId = Identifier.parse(targetMobId);

        List<LivingEntity> nearby = level.getNearbyEntities(
                LivingEntity.class,
                net.minecraft.world.entity.ai.targeting.TargetingConditions
                        .forCombat().ignoreLineOfSight(),
                entity,
                entity.getBoundingBox().inflate(SEARCH_RANGE)
        );

        return nearby.stream()
                .filter(e -> e != entity)
                .filter(LivingEntity::isAlive)
                .filter(e -> e.getType().builtInRegistryHolder()
                        .key().registry().equals(targetId))
                .min(java.util.Comparator.comparingDouble(
                        entity::distanceTo))
                .orElse(null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Quest getActiveQuest() {
        if (!(entity.level() instanceof ServerLevel level)) return null;

        return entity.getGroupId()
                .flatMap(id -> AdventurerSavedData.get(level).getGroup(id))
                .map(AdventurerGroup::getActiveQuest)
                .orElse(null);
    }

    private void updateActivityLabel() {
        Quest quest = getActiveQuest();
        if (quest == null) return;

        String mobName = quest.getTargetMob() != null
                && quest.getTargetMob().contains(":")
                ? quest.getTargetMob().split(":")[1]
                .replace("_", " ")
                : "target";

        entity.setCurrentActivity("Hunting " + mobName
                + " (" + quest.getCurrentCount()
                + "/" + quest.getTargetCount() + ")");
    }
}