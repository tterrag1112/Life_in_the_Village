// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/PartyDefendGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Makes a party member engage enemies that attack the player leader
 * or other party members.
 *
 * <h3>Trigger conditions</h3>
 * <ul>
 *   <li>Player takes damage from a mob → all members engage that mob</li>
 *   <li>A party member takes damage → nearby members engage the attacker</li>
 *   <li>A hostile mob comes within {@value #ALERT_RADIUS} blocks of the
 *       player → SCOUT role alerts the party</li>
 * </ul>
 */
public class PartyDefendGoal extends Goal {

    private static final int    ALERT_RADIUS  = 12;
    private static final int    SCAN_INTERVAL = 20; // ticks
    private static final double ENGAGE_SPEED  = 1.2;

    private final TownspersonMob entity;
    private LivingEntity target      = null;
    private int          scanTimer   = 0;

    public PartyDefendGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        Optional<PlayerParty> partyOpt =
                PlayerPartySavedData.get(level)
                        .getPartyContaining(entity.getUUID());
        if (partyOpt.isEmpty()) return false;

        target = findTarget(level, partyOpt.get());
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive()
                && entity.distanceToSqr(target) < 32 * 32;
    }

    @Override
    public void start() {
        entity.setTarget(target);
    }

    @Override
    public void stop() {
        entity.setTarget(null);
        target = null;
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        scanTimer++;

        if (target == null || !target.isAlive()) {
            // Scan for a new target
            if (scanTimer >= SCAN_INTERVAL) {
                scanTimer = 0;
                Optional<PlayerParty> partyOpt =
                        PlayerPartySavedData.get(level)
                                .getPartyContaining(entity.getUUID());
                partyOpt.ifPresent(party ->
                        target = findTarget(level, party));
                if (target != null) entity.setTarget(target);
            }
            return;
        }

        entity.setTarget(target);
        entity.getLookControl().setLookAt(target,
                30f, 30f);

        // Move to engagement range based on role
        int range = entity.getCombatRole() != null
                ? entity.getCombatRole().engagementRange : 2;

        double distSq = entity.distanceToSqr(target);
        if (distSq > (range + 1.0) * (range + 1.0)) {
            entity.getNavigation().moveTo(
                    target, ENGAGE_SPEED
                            * (entity.getCombatRole() != null
                            ? entity.getCombatRole().speedModifier
                            : 1.0f));
        } else {
            entity.getNavigation().stop();
        }
    }

    // =========================================================================
    // Target finding
    // =========================================================================

    private LivingEntity findTarget(ServerLevel level,
                                    PlayerParty party) {
        // Priority 1: mob currently attacking the player
        ServerPlayer player = level.getServer()
                .getPlayerList()
                .getPlayer(party.getLeaderPlayerId());
        if (player != null && player.getLastHurtByMob() != null
                && player.getLastHurtByMob().isAlive()) {
            return player.getLastHurtByMob();
        }

        // Priority 2: mob currently attacking any party member
        for (PlayerParty.PartyMember member : party.getMembers()) {
            if (!member.isAlive()) continue;
            var entity = level.getEntity(member.npcId());
            if (entity instanceof TownspersonMob npc
                    && npc.getLastHurtByMob() != null
                    && npc.getLastHurtByMob().isAlive()) {
                return npc.getLastHurtByMob();
            }
        }

        // Priority 3: nearest hostile mob within alert radius of player
        if (player != null) {
            List<Monster> nearby = level.getEntitiesOfClass(
                    Monster.class,
                    player.getBoundingBox().inflate(ALERT_RADIUS));
            if (!nearby.isEmpty()) {
                return nearby.stream()
                        .min(java.util.Comparator.comparingDouble(
                                m -> m.distanceToSqr(player)))
                        .orElse(null);
            }
        }

        return null;
    }
}