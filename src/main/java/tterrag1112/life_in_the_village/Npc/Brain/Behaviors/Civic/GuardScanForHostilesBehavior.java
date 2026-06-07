package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Civic;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Optional;

/**
 * Phase 6.2.e — port of {@code GuardAttackGoal} + {@code HurtByTargetGoal}'s
 * GUARD-side trigger. Universal CORE behavior on GUARD's brain. Sets
 * {@code ATTACK_TARGET} memory when a hostile is in range OR when the
 * guard has just been hurt by an attacker. Vanilla
 * {@code StopAttackingIfTargetInvalid}-style cleanup is folded in:
 * targets that die / despawn / leave range have ATTACK_TARGET erased.
 *
 * <p>This behavior writes ATTACK_TARGET only — it does NOT navigate or
 * swing. {@code GuardMeleeAttackBehavior} consumes the memory in FIGHT.
 *
 * <p>Coexistence: ATTACK_TARGET write is gated by
 * {@link BrainNavGuard#canSetAttackTarget}; no concurrent attack-target
 * set conflicts with the migrated combat path.
 */
public class GuardScanForHostilesBehavior extends Behavior<TownspersonMob> {

    private static final double DETECTION_RANGE = 16.0;
    private static final double FORGET_RANGE = DETECTION_RANGE * 1.5;
    private static final int MAX_RUN = Integer.MAX_VALUE;

    /** Liveliness L0 — the proactive monster/player scan (wide
     *  {@code getEntitiesOfClass} + {@code VillageSavedData.get}) ran every
     *  tick per guard. Gate it to a 1s cadence (mirrors
     *  {@code PredatorScanBehavior}); retaliation + stale-target clearing
     *  below stay per-tick so a guard still reacts instantly to being hit. */
    private static final int SCAN_INTERVAL_TICKS = 20;
    private long lastScanTick;

    public GuardScanForHostilesBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return true;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        Brain<TownspersonMob> brain = entity.getBrain();
        LivingEntity current = brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);

        // 1. Clear stale target if invalid / out of range / dead.
        if (current != null) {
            if (!current.isAlive() || current.distanceTo(entity) > FORGET_RANGE) {
                brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                current = null;
            }
        }

        if (current != null) return; // already engaged

        // 2. Retaliate against last attacker (HurtByTargetGoal port).
        LivingEntity hurtBy = brain.getMemory(MemoryModuleType.HURT_BY_ENTITY).orElse(null);
        if (hurtBy != null && hurtBy.isAlive()
                && hurtBy.distanceTo(entity) <= FORGET_RANGE) {
            setTarget(entity, hurtBy);
            return;
        }

        // 3. Proactive scan for hostiles in range (GuardAttackGoal port).
        //    Throttled — the wide entity scans + VillageSavedData.get are the
        //    expensive part; a 1s cadence is plenty to spot a monster.
        if (gameTime - lastScanTick < SCAN_INTERVAL_TICKS) return;
        lastScanTick = gameTime;

        AABB searchArea = entity.getBoundingBox().inflate(DETECTION_RANGE);

        List<Monster> monsters = level.getEntitiesOfClass(Monster.class, searchArea);
        if (!monsters.isEmpty()) {
            setTarget(entity, monsters.get(0));
            return;
        }

        Optional<Village> village = entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name));
        if (village.isPresent()) {
            List<Player> players = level.getEntitiesOfClass(Player.class, searchArea);
            for (Player player : players) {
                if (village.get().isHostile(player.getUUID())) {
                    setTarget(entity, player);
                    return;
                }
            }
        }
    }

    private static void setTarget(TownspersonMob entity, LivingEntity target) {
        if (!BrainNavGuard.canSetAttackTarget(entity)) return;
        entity.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target);
    }
}
