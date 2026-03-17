package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerGroup;
import tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers.AdventurerSavedData;

import java.util.EnumSet;
import java.util.List;

public class AdventurerCombatAssistGoal extends Goal {

    private static final double ASSIST_RANGE      = 24.0;
    private static final double ATTACK_RANGE      = 2.5;
    private static final double MOVE_SPEED        = 1.2;
    private static final int    RECHECK_INTERVAL  = 20;

    private final TownspersonMob entity;
    private LivingEntity target;
    private int recheckTimer = 0;

    public AdventurerCombatAssistGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    // -------------------------------------------------------------------------
    // Goal lifecycle
    // -------------------------------------------------------------------------

    @Override
    public boolean canUse() {
        if (!entity.isAdventurerGroupMember()) return false;

        Player alliedPlayer = getAlliedPlayer();
        if (alliedPlayer == null) return false;

        // Only assist if tier allows it
        if (!groupAllowsAssist()) return false;

        target = findNearestThreat(alliedPlayer);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive()) return false;

        Player alliedPlayer = getAlliedPlayer();
        if (alliedPlayer == null) return false;

        // Stop if threat moved too far from player
        if (target.distanceTo(alliedPlayer) > ASSIST_RANGE * 1.5) {
            return false;
        }

        return true;
    }

    @Override
    public void start() {
        entity.setTarget(target);
        updateActivityLabel();
    }

    @Override
    public void stop() {
        entity.setTarget(null);
        target = null;
        recheckTimer = 0;

        // Immediately return to patrol activity label
        getGroup().ifPresent(group -> {
            String activity = group.getActiveQuest() != null
                    ? group.getActiveQuest().getTitle()
                    : "On patrol";
            entity.setCurrentActivity(activity);
        });
    }

    @Override
    public void tick() {
        if (target == null || !target.isAlive()) return;

        recheckTimer++;

        // Look at target
        entity.getLookControl().setLookAt(target,
                30f, entity.getMaxHeadXRot());

        double distToTarget = entity.distanceTo(target);

        if (distToTarget <= ATTACK_RANGE) {
            // In melee range — attack
            entity.getNavigation().stop();
            entity.doHurtTarget(
                    (net.minecraft.server.level.ServerLevel) entity.level(),
                    target);
        } else {
            // Move toward target
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(),
                    MOVE_SPEED);
        }

        // Periodically recheck for a closer threat
        if (recheckTimer >= RECHECK_INTERVAL) {
            recheckTimer = 0;
            Player alliedPlayer = getAlliedPlayer();
            if (alliedPlayer != null) {
                LivingEntity closer = findNearestThreat(alliedPlayer);
                if (closer != null && closer != target) {
                    target = closer;
                    entity.setTarget(target);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Threat detection
    // -------------------------------------------------------------------------

    private LivingEntity findNearestThreat(Player alliedPlayer) {
        if (!(entity.level() instanceof ServerLevel level)) return null;

        // Find all living entities near the player that are
        // attacking them or are hostile
        List<LivingEntity> threats = level.getNearbyEntities(
                LivingEntity.class,
                TargetingConditions.forCombat()
                        .ignoreLineOfSight()
                        .ignoreInvisibilityTesting(),
                entity,
                entity.getBoundingBox().inflate(ASSIST_RANGE)
        );

        return threats.stream()
                // Must be targeting the allied player
                .filter(e -> e != entity)
                .filter(e -> !(e instanceof TownspersonMob))
                .filter(LivingEntity::isAlive)
                .filter(e -> isThreateningPlayer(e, alliedPlayer))
                // Pick nearest to the player, not to the NPC
                .min(java.util.Comparator.comparingDouble(
                        e -> e.distanceTo(alliedPlayer)))
                .orElse(null);
    }

    private boolean isThreateningPlayer(LivingEntity entity,
                                        Player player) {
        // Direct attacker
        if (entity.getLastHurtMob() != null
                && entity.getLastHurtMob().getUUID()
                .equals(player.getUUID())) {
            return true;
        }
        // Currently targeting the player
        if (entity instanceof net.minecraft.world.entity.Mob mob
                && mob.getTarget() != null
                && mob.getTarget().getUUID()
                .equals(player.getUUID())) {
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Player getAlliedPlayer() {
        if (!(entity.level() instanceof ServerLevel level)) return null;

        return getGroup()
                .flatMap(AdventurerGroup::getAlliedPlayerId)
                .map(id -> level.getServer()
                        .getPlayerList().getPlayer(id))
                .orElse(null);
    }

    private java.util.Optional<AdventurerGroup> getGroup() {
        if (!(entity.level() instanceof ServerLevel level)) {
            return java.util.Optional.empty();
        }
        return entity.getGroupId()
                .flatMap(id -> AdventurerSavedData.get(level)
                        .getGroup(id));
    }

    private boolean groupAllowsAssist() {
        if (!(entity.level() instanceof ServerLevel level)) return false;

        Player alliedPlayer = getGroup()
                .flatMap(AdventurerGroup::getAlliedPlayerId)
                .map(id -> level.getServer()
                        .getPlayerList().getPlayer(id))
                .orElse(null);

        if (alliedPlayer == null) return false;

        return getGroup()
                .map(group -> {
                    tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers
                            .AdventurerReputationTier tier =
                            tterrag1112.life_in_the_village.Guilds.Adventurer.Adventurers
                                    .AdventurerReputationManager
                                    .getEffectiveTier(
                                            (net.minecraft.server.level.ServerPlayer)
                                                    alliedPlayer,
                                            group, level);
                    return tier.willAssistInCombat();
                })
                .orElse(false);
    }

    private void updateActivityLabel() {
        String mobName = target.getType()
                .builtInRegistryHolder()
                .key().registry().toString();
        String simpleName = mobName.contains(":")
                ? mobName.split(":")[1].replace("_", " ")
                : mobName;
        entity.setCurrentActivity("Fighting " + simpleName + "!");
    }
}