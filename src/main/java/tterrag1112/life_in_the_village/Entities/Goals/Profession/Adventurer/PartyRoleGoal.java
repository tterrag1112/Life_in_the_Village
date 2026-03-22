// src/main/java/tterrag1112/life_in_the_village/Entities/Goals/PartyRoleGoal.java
package tterrag1112.life_in_the_village.Entities.Goals.Profession.Adventurer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Adventurer.CombatRole;
import tterrag1112.life_in_the_village.Guilds.Adventurer.PlayerParty;
import tterrag1112.life_in_the_village.Guilds.PlayerPartySavedData;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Executes role-specific behaviour for each {@link }.
 *
 * <h3>Per-role behaviour</h3>
 * <ul>
 *   <li><b>SWORDSMAN</b> — performs a taunt effect (draws enemy
 *       aggro by running toward the nearest hostile and giving
 *       itself Resistance I briefly)</li>
 *   <li><b>ARCHER</b> — repositions to maintain optimal range,
 *       uses bow interaction</li>
 *   <li><b>MAGE</b> — throws splash potion of harming at nearby
 *       enemies on a cooldown</li>
 *   <li><b>HEALER</b> — scans for party members below 50% health
 *       and throws splash potion of healing toward them</li>
 *   <li><b>SCOUT</b> — runs slightly ahead, detects mobs within
 *       16 blocks, and alerts the player with a chat message</li>
 * </ul>
 */
public class PartyRoleGoal extends Goal {

    private static final int    HEAL_COOLDOWN_TICKS   = 100; // 5 seconds
    private static final int    POTION_COOLDOWN_TICKS = 80;
    private static final int    SCOUT_SCAN_TICKS      = 60;
    private static final int    TAUNT_COOLDOWN_TICKS  = 200;
    private static final int    SCOUT_ALERT_RADIUS    = 20;
    private static final float  HEAL_THRESHOLD        = 0.5f; // 50% health

    private final TownspersonMob entity;
    private int cooldown = 0;

    public PartyRoleGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    public boolean canUse() {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (entity.getCombatRole() == null) return false;
        // Only fire on cooldown
        return cooldown <= 0
                && PlayerPartySavedData.get(level)
                .getPartyContaining(entity.getUUID()).isPresent();
    }

    @Override
    public boolean canContinueToUse() { return false; } // one-shot

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (cooldown > 0) { cooldown--; return; }
        if (!(entity.level() instanceof ServerLevel level)) return;

        Optional<PlayerParty> partyOpt =
                PlayerPartySavedData.get(level)
                        .getPartyContaining(entity.getUUID());
        if (partyOpt.isEmpty()) return;

        PlayerParty party = partyOpt.get();
        ServerPlayer player = level.getServer()
                .getPlayerList()
                .getPlayer(party.getLeaderPlayerId());

        CombatRole role = entity.getCombatRole();
        if (role == null) return;

        switch (role) {
            case SWORDSMAN -> tickSwordsman(level, player);
            case ARCHER    -> tickArcher(level, player);
            case MAGE      -> tickMage(level, player);
            case HEALER    -> tickHealer(level, party, player);
            case SCOUT     -> tickScout(level, party, player);
        }
    }

    // =========================================================================
    // Role implementations
    // =========================================================================

    private void tickSwordsman(ServerLevel level,
                               ServerPlayer player) {
        // Taunt: give self Resistance I and charge toward nearest mob
        List<Monster> nearby = level.getEntitiesOfClass(
                Monster.class,
                entity.getBoundingBox().inflate(8));
        if (nearby.isEmpty()) return;

        entity.addEffect(new MobEffectInstance(
                MobEffects.RESISTANCE, 60, 0, false, false));
        entity.setTarget(nearby.get(0));
        cooldown = TAUNT_COOLDOWN_TICKS;
    }

    private void tickArcher(ServerLevel level, ServerPlayer player) {
        // Ensure bow is equipped
        ItemStack mainhand = entity.getMainHandItem();
        if (!mainhand.is(Items.BOW)) {
            entity.setItemSlot(
                    net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    new ItemStack(Items.BOW));
        }

        // Reposition: maintain engagementRange blocks from target
        LivingEntity target = entity.getTarget();
        if (target == null) return;

        double dist = entity.distanceTo(target);
        int desired = CombatRole.ARCHER.engagementRange;

        if (dist < desired - 1) {
            // Too close — back away
            double dx = entity.getX() - target.getX();
            double dz = entity.getZ() - target.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0) {
                entity.getNavigation().moveTo(
                        entity.getX() + (dx / len) * 2,
                        entity.getY(),
                        entity.getZ() + (dz / len) * 2,
                        1.1);
            }
        }
        // Actual bow shooting is handled by Minecraft's RangedAttackGoal
        // which is registered separately
        cooldown = 20;
    }

    private void tickMage(ServerLevel level, ServerPlayer player) {
        // Throw splash potion of harming at nearest enemy cluster
        List<Monster> nearby = level.getEntitiesOfClass(
                Monster.class,
                entity.getBoundingBox().inflate(8));
        if (nearby.isEmpty()) return;

        Monster nearest = nearby.stream()
                .min(java.util.Comparator.comparingDouble(
                        m -> m.distanceToSqr(entity)))
                .orElse(null);
        if (nearest == null) return;

        // Apply Weakness to enemies nearby the target (AoE simulation)
        for (Monster mob : nearby) {
            if (mob.distanceToSqr(nearest) < 4 * 4) {
                mob.addEffect(new MobEffectInstance(
                        MobEffects.WEAKNESS, 100, 0, false, true));
                // Damage via direct effect application
                mob.hurt(level.damageSources().magic(),
                        4.0f * (float) entity.getCombatRole().damageModifier);
            }
        }

        // Swing animation
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        cooldown = POTION_COOLDOWN_TICKS;
    }

    private void tickHealer(ServerLevel level,
                            PlayerParty party,
                            ServerPlayer player) {
        // Scan party members and player for low health
        LivingEntity healTarget = null;
        float lowestHealthFraction = HEAL_THRESHOLD;

        // Check player
        if (player != null) {
            float fraction = player.getHealth() / player.getMaxHealth();
            if (fraction < lowestHealthFraction) {
                lowestHealthFraction = fraction;
                healTarget = player;
            }
        }

        // Check party members
        for (PlayerParty.PartyMember member : party.getMembers()) {
            if (!member.isAlive()) continue;
            var ent = level.getEntity(member.npcId());
            if (ent instanceof LivingEntity living) {
                float fraction = living.getHealth() / living.getMaxHealth();
                if (fraction < lowestHealthFraction) {
                    lowestHealthFraction = fraction;
                    healTarget = living;
                }
            }
        }

        if (healTarget == null) return;

        // Apply healing (splash potion simulation)
        float healAmount = 4.0f + (entity.getCombatRole().damageModifier * 2);
        healTarget.heal(healAmount);

        // Apply Regeneration briefly
        healTarget.addEffect(new MobEffectInstance(
                MobEffects.REGENERATION, 60, 0, false, true));

        // Face the target and animate
        entity.getLookControl().setLookAt(healTarget, 30f, 30f);
        entity.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        cooldown = HEAL_COOLDOWN_TICKS;
    }

    private void tickScout(ServerLevel level,
                           PlayerParty party,
                           ServerPlayer player) {
        if (player == null) return;

        // Detect nearby hostile mobs within scout radius
        List<Monster> threats = level.getEntitiesOfClass(
                Monster.class,
                player.getBoundingBox().inflate(SCOUT_ALERT_RADIUS));

        if (threats.isEmpty()) return;

        // Only alert once per scan cycle
        // Report threat direction and count
        int count = threats.size();
        Monster nearest = threats.stream()
                .min(java.util.Comparator.comparingDouble(
                        m -> m.distanceToSqr(player)))
                .orElse(null);

        if (nearest != null) {
            String direction = getCardinalDirection(
                    player, nearest);
            String name = nearest.getType()
                    .getDescription().getString();

            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[" + entity.getNpcName()
                                    + "] " + count
                                    + (count == 1 ? " " + name : " hostiles")
                                    + " spotted to the " + direction + "!"),
                    true); // actionbar — not intrusive
        }

        cooldown = SCOUT_SCAN_TICKS;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String getCardinalDirection(ServerPlayer player,
                                        LivingEntity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double normalised = ((yaw % 360) + 360) % 360;

        if (normalised < 45 || normalised >= 315) return "north";
        if (normalised < 135) return "east";
        if (normalised < 225) return "south";
        return "west";
    }
}