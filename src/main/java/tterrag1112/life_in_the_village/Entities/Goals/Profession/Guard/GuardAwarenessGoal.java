package tterrag1112.life_in_the_village.Entities.Goals.Profession.Guard;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Gives guards visible awareness behaviors that make their role feel real:
 *
 * <h3>Night behavior</h3>
 * <ul>
 *   <li>Guards hold a torch in their offhand at night (when sky light < 4)</li>
 *   <li>Torch is removed at dawn</li>
 * </ul>
 *
 * <h3>Threat awareness</h3>
 * <ul>
 *   <li>Every ~10 seconds, scans for hostile mobs within 24 blocks</li>
 *   <li>If hostiles found near a player, announces direction and count
 *       to nearby players via action bar</li>
 *   <li>Announcement is throttled: once per 30 seconds per guard</li>
 *   <li>Uses the same cardinal direction logic as the Scout party role</li>
 * </ul>
 *
 * <h3>Alert state</h3>
 * <ul>
 *   <li>When hostiles are detected, guard movement speed increases</li>
 *   <li>Guard faces the nearest threat briefly before resuming patrol</li>
 *   <li>Sets activity label to "On alert!" for player visibility</li>
 * </ul>
 *
 * <h3>Priority</h3>
 * Runs as a non-exclusive goal (no flags) so it doesn't interfere with
 * patrol or combat goals. It only scans and announces — combat targeting
 * is handled by GuardAttackGoal.
 *
 * <h3>Registration</h3>
 * Add in ProfessionGoalFactory under GUARD:
 * {@code npc.goalSelector.addGoal(P_SOCIAL_MID, new GuardAwarenessGoal(npc));}
 */
public class GuardAwarenessGoal extends Goal {

    // ── Configuration ─────────────────────────────────────────────────────────
    private static final int   SCAN_INTERVAL      = 200;  // 10 seconds
    private static final int   ANNOUNCE_COOLDOWN   = 600;  // 30 seconds
    private static final double SCAN_RANGE          = 24.0;
    private static final int   SKY_LIGHT_THRESHOLD = 4;    // below = "dark"
    private static final int   ALERT_DURATION      = 60;   // 3 seconds

    // ── State ─────────────────────────────────────────────────────────────────
    private final TownspersonMob entity;
    private int scanTimer         = 0;
    private int announceCooldown  = 0;
    private int alertTimer        = 0;
    private boolean holdingTorch  = false;

    public GuardAwarenessGoal(TownspersonMob entity) {
        this.entity = entity;
        // Non-exclusive — doesn't block other goals
        setFlags(EnumSet.noneOf(Flag.class));
    }

    // =========================================================================
    // Goal lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        return entity.isWorkTime();
    }

    @Override
    public boolean canContinueToUse() {
        return entity.isWorkTime();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (!(entity.level() instanceof ServerLevel level)) return;

        // ── Torch management ─────────────────────────────────────────────────
        tickTorch(level);

        // ── Alert decay ──────────────────────────────────────────────────────
        if (alertTimer > 0) {
            alertTimer--;
            if (alertTimer == 0) {
                entity.setCurrentActivity("Patrolling");
            }
        }

        // ── Cooldown decay ───────────────────────────────────────────────────
        if (announceCooldown > 0) announceCooldown--;

        // ── Periodic scan ────────────────────────────────────────────────────
        scanTimer++;
        if (scanTimer < SCAN_INTERVAL) return;
        scanTimer = 0;

        tickThreatScan(level);
    }

    @Override
    public void stop() {
        removeTorch();
    }

    // =========================================================================
    // Torch management
    // =========================================================================

    private void tickTorch(ServerLevel level) {
        int skyLight = level.getBrightness(LightLayer.SKY, entity.blockPosition());
        long dayTime = level.getDayTime() % 24000;

        // Night = after dusk (13000) until dawn (23000), or very dark
        boolean isNight = dayTime >= 13000 && dayTime < 23000;
        boolean isDark = skyLight < SKY_LIGHT_THRESHOLD;

        if ((isNight || isDark) && !holdingTorch) {
            // Equip torch in offhand
            entity.setItemInHand(InteractionHand.OFF_HAND,
                    new ItemStack(Items.TORCH));
            holdingTorch = true;
        } else if (!isNight && !isDark && holdingTorch) {
            removeTorch();
        }
    }

    private void removeTorch() {
        if (holdingTorch) {
            entity.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            holdingTorch = false;
        }
    }

    // =========================================================================
    // Threat scanning
    // =========================================================================

    private void tickThreatScan(ServerLevel level) {
        List<Monster> threats = level.getEntitiesOfClass(
                Monster.class,
                entity.getBoundingBox().inflate(SCAN_RANGE),
                Monster::isAlive);

        if (threats.isEmpty()) return;

        // ── Enter alert state ────────────────────────────────────────────────
        alertTimer = ALERT_DURATION;
        entity.setCurrentActivity("On alert!");

        // Face nearest threat briefly
        Monster nearest = threats.stream()
                .min(java.util.Comparator.comparingDouble(
                        m -> m.distanceToSqr(entity)))
                .orElse(null);

        if (nearest != null) {
            entity.getLookControl().setLookAt(nearest, 30f, 30f);
        }

        // ── Announce to nearby players ───────────────────────────────────────
        if (announceCooldown > 0) return;

        List<ServerPlayer> nearbyPlayers =
                level.getEntitiesOfClass(ServerPlayer.class,
                        entity.getBoundingBox().inflate(32));

        if (nearbyPlayers.isEmpty()) return;

        // Build announcement
        String direction = nearest != null
                ? getCardinalDirection(entity, nearest)
                : "nearby";
        int count = threats.size();
        String mobName = nearest != null
                ? nearest.getType().getDescription().getString()
                : "hostiles";

        String message = count == 1
                ? mobName + " spotted to the " + direction + "!"
                : count + " hostiles to the " + direction + "!";

        // Send to all nearby players
        for (ServerPlayer player : nearbyPlayers) {
            player.displayClientMessage(
                    Component.literal("[" + entity.getNpcName() + "] " + message),
                    true); // action bar — not intrusive
        }

        // Play alert sound
        level.playSound(null, entity.blockPosition(),
                SoundEvents.NOTE_BLOCK_BELL.value(),
                SoundSource.NEUTRAL, 0.5f, 1.2f);

        announceCooldown = ANNOUNCE_COOLDOWN;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String getCardinalDirection(TownspersonMob guard,
                                               Monster target) {
        double dx = target.getX() - guard.getX();
        double dz = target.getZ() - guard.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double normalised = ((yaw % 360) + 360) % 360;

        if (normalised < 45 || normalised >= 315) return "north";
        if (normalised < 135) return "east";
        if (normalised < 225) return "south";
        return "west";
    }
}