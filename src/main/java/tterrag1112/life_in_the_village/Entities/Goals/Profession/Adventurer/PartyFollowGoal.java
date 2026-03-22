// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/PartyFollowGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Makes a party member follow the player leader in a loose formation.
 *
 * <h3>Formation offsets</h3>
 * Each role has a preferred formation position relative to the player:
 * <ul>
 *   <li>SWORDSMAN — directly behind (flanking position)</li>
 *   <li>ARCHER    — behind and to the side</li>
 *   <li>MAGE      — behind the swordsman</li>
 *   <li>HEALER    — rear centre</li>
 *   <li>SCOUT     — slightly ahead and to the side</li>
 * </ul>
 *
 * Formation positions are in the player's facing direction so they
 * rotate with the player rather than being world-axis aligned.
 */
public class PartyFollowGoal extends Goal {

    private static final double CLOSE_ENOUGH      = 3.0;
    private static final double MAX_FOLLOW_DIST   = 24.0;
    private static final double TELEPORT_DIST     = 48.0;
    private static final double FOLLOW_SPEED      = 0.9;

    private final TownspersonMob entity;
    private int      recalcTimer = 0;

    public PartyFollowGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        // Only active when not in combat
        if (entity.getTarget() != null) return false;
        return getParty(level).isPresent();
    }

    @Override
    public boolean canContinueToUse() {
        if (entity.getTarget() != null) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        return getParty(level).isPresent()
                && getPlayer(level).isPresent();
    }

    @Override
    public void stop() {
        entity.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        Optional<ServerPlayer> playerOpt = getPlayer(level);
        if (playerOpt.isEmpty()) return;

        ServerPlayer player = playerOpt.get();
        BlockPos target     = formationTarget(player);

        double dist = entity.distanceToSqr(
                target.getX(), target.getY(), target.getZ());

        // Teleport if too far away (prevents getting stuck)
        if (dist > TELEPORT_DIST * TELEPORT_DIST) {
            entity.teleportTo(
                    target.getX() + 0.5,
                    target.getY(),
                    target.getZ() + 0.5);
            return;
        }

        if (dist < CLOSE_ENOUGH * CLOSE_ENOUGH) {
            entity.getNavigation().stop();
            entity.getLookControl().setLookAt(
                    player, 30f, 30f);
            return;
        }

        // Recalculate path every 10 ticks
        recalcTimer++;
        if (recalcTimer >= 10) {
            recalcTimer = 0;
            entity.getNavigation().moveTo(
                    target.getX(), target.getY(), target.getZ(),
                    FOLLOW_SPEED * getRole().speedModifier);
        }
    }

    // =========================================================================
    // Formation calculation
    // =========================================================================

    /**
     * Returns the world-space formation position for this member,
     * offset in the player's facing direction.
     *
     * Formation slots (forward = player facing direction, right = 90° CW):
     * <pre>
     *      [SCOUT]   [Player]
     *  [ARCHER]  [SWORDSMAN]
     *       [MAGE] [HEALER]
     * </pre>
     */
    private BlockPos formationTarget(ServerPlayer player) {
        // Convert player yaw to radians — forward direction
        double yaw     = Math.toRadians(player.getYRot());
        double fwdX    = -Math.sin(yaw);
        double fwdZ    =  Math.cos(yaw);
        double rightX  = -fwdZ;
        double rightZ  =  fwdX;

        // Formation offsets [forward, right] in blocks
        double[] offset = switch (getRole()) {
            case SWORDSMAN -> new double[]{-1.5,  0.8};
            case ARCHER    -> new double[]{-3.0, -1.5};
            case MAGE      -> new double[]{-4.0,  0.5};
            case HEALER    -> new double[]{-4.5, -0.5};
            case SCOUT     -> new double[]{ 1.5,  1.0};
        };

        double tx = player.getX() + fwdX * offset[0] + rightX * offset[1];
        double tz = player.getZ() + fwdZ * offset[0] + rightZ * offset[1];

        int surfY = ((ServerLevel) entity.level()).getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types
                        .MOTION_BLOCKING_NO_LEAVES,
                (int) tx, (int) tz);

        return new BlockPos((int) tx, surfY, (int) tz);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private CombatRole getRole() {
        return entity.getCombatRole() != null
                ? entity.getCombatRole()
                : CombatRole.SWORDSMAN;
    }

    private Optional<PlayerParty> getParty(ServerLevel level) {
        return PlayerPartySavedData.get(level)
                .getPartyContaining(entity.getUUID());
    }

    private Optional<ServerPlayer> getPlayer(ServerLevel level) {
        return getParty(level)
                .map(PlayerParty::getLeaderPlayerId)
                .map(id -> level.getServer()
                        .getPlayerList().getPlayer(id));
    }
}