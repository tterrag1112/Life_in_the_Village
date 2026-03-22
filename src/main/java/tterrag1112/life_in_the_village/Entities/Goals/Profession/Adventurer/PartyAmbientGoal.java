// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/PartyAmbientGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.decoration.ArmorStand;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Small ambient behaviours that make party members feel alive when
 * not in combat or camping.
 *
 * <h3>Behaviours by role</h3>
 * <ul>
 *   <li><b>SWORDSMAN</b> — periodically faces the direction of travel,
 *       checks behind the party</li>
 *   <li><b>ARCHER</b> — crouches briefly when stopping, faces the
 *       direction the player is looking</li>
 *   <li><b>MAGE</b> — occasional particle burst (happy villager
 *       as a stand-in for magic sparks)</li>
 *   <li><b>HEALER</b> — periodically looks at each party member
 *       in turn (visual health check)</li>
 *   <li><b>SCOUT</b> — briefly sprints ahead and returns</li>
 * </ul>
 */
public class PartyAmbientGoal extends Goal {

    private static final int BASE_INTERVAL = 200; // ~10 seconds
    private static final int SPRINT_DIST   = 8;

    private final TownspersonMob entity;
    private int tickTimer  = 0;
    private int actionTime = 0;
    private boolean sprintingAhead = false;

    public PartyAmbientGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.noneOf(Flag.class)); // non-exclusive
    }

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (entity.getCombatRole() == null) return false;
        if (entity.getTarget() != null) return false;
        return getParty(level).isPresent();
    }

    @Override
    public boolean canContinueToUse() { return canUse(); }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;
        tickTimer++;

        // Stagger each member's tick using their role ordinal
        int stagger = entity.getCombatRole() != null
                ? entity.getCombatRole().ordinal() * 40 : 0;
        if ((tickTimer + stagger) % BASE_INTERVAL != 0) return;

        Optional<PlayerParty> partyOpt = getParty(level);
        if (partyOpt.isEmpty()) return;

        PlayerParty party = partyOpt.get();
        ServerPlayer player = getPlayer(level, party);
        if (player == null) return;

        CombatRole role = entity.getCombatRole() != null
                ? entity.getCombatRole() : CombatRole.SWORDSMAN;

        switch (role) {
            case SWORDSMAN -> doSwordsmanAmbient(level, player, party);
            case ARCHER    -> doArcherAmbient(level, player);
            case MAGE      -> doMageAmbient(level);
            case HEALER    -> doHealerAmbient(level, party);
            case SCOUT     -> doScoutAmbient(level, player);
        }
    }

    // =========================================================================
    // Role ambients
    // =========================================================================

    private void doSwordsmanAmbient(ServerLevel level,
                                    ServerPlayer player,
                                    PlayerParty party) {
        // Face behind the party (180° from player's facing)
        double yaw = Math.toRadians(player.getYRot() + 180);
        double lookX = entity.getX() + Math.sin(yaw) * 3;
        double lookZ = entity.getZ() + Math.cos(yaw) * 3;
        entity.getLookControl().setLookAt(
                lookX, entity.getEyeY(), lookZ);
    }

    private void doArcherAmbient(ServerLevel level,
                                 ServerPlayer player) {
        // Mirror where the player is looking — archer covers the same arc
        double yaw   = Math.toRadians(player.getYRot());
        double pitch = Math.toRadians(player.getXRot());
        double lookX = entity.getX() - Math.sin(yaw) * Math.cos(pitch) * 10;
        double lookY = entity.getEyeY() - Math.sin(pitch) * 10;
        double lookZ = entity.getZ() + Math.cos(yaw) * Math.cos(pitch) * 10;
        entity.getLookControl().setLookAt(lookX, lookY, lookZ);
    }

    private void doMageAmbient(ServerLevel level) {
        // Small particle burst — uses HAPPY_VILLAGER as magic stand-in
        for (int i = 0; i < 5; i++) {
            double ox = (level.getRandom().nextDouble() - 0.5) * 1.0;
            double oy = level.getRandom().nextDouble() * 1.5;
            double oz = (level.getRandom().nextDouble() - 0.5) * 1.0;
            level.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.ENCHANT,
                    entity.getX() + ox,
                    entity.getY() + oy,
                    entity.getZ() + oz,
                    1, 0, 0, 0, 0.05);
        }
    }

    private void doHealerAmbient(ServerLevel level,
                                 PlayerParty party) {
        // Look at each party member in turn — visual health check
        java.util.List<PlayerParty.PartyMember> alive =
                party.getMembers().stream()
                        .filter(PlayerParty.PartyMember::isAlive)
                        .filter(m -> !m.npcId().equals(entity.getUUID()))
                        .toList();

        if (alive.isEmpty()) return;

        int idx = (tickTimer / BASE_INTERVAL)
                % alive.size();
        var target = level.getEntity(alive.get(idx).npcId());
        if (target != null) {
            entity.getLookControl().setLookAt(
                    target, 30f, 30f);
        }
    }

    private void doScoutAmbient(ServerLevel level,
                                ServerPlayer player) {
        // Sprint a few blocks ahead of the player and return
        if (sprintingAhead) {
            // Already sprinted — navigate back toward player
            entity.getNavigation().moveTo(player, 1.4);
            if (entity.distanceToSqr(player) < 4.0) {
                sprintingAhead = false;
                entity.setSprinting(false);
            }
            return;
        }

        // Sprint ahead in the player's facing direction
        double yaw   = Math.toRadians(player.getYRot());
        double aheadX = player.getX() - Math.sin(yaw) * SPRINT_DIST;
        double aheadZ = player.getZ() + Math.cos(yaw) * SPRINT_DIST;

        int surfY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES,
                (int) aheadX, (int) aheadZ);

        entity.setSprinting(true);
        entity.getNavigation().moveTo(
                aheadX, surfY, aheadZ, 1.4);
        sprintingAhead = true;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Optional<PlayerParty> getParty(ServerLevel level) {
        return PlayerPartySavedData.get(level)
                .getPartyContaining(entity.getUUID());
    }

    private ServerPlayer getPlayer(ServerLevel level,
                                   PlayerParty party) {
        return level.getServer().getPlayerList()
                .getPlayer(party.getLeaderPlayerId());
    }
}