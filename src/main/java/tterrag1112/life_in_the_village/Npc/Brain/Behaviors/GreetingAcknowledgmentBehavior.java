package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Gesture;
import tterrag1112.life_in_the_village.Npc.Brain.IdleGesturePalette;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.RecentInteractionPartners;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Brief greeting between two nearby NPCs — head turn (via LOOK_TARGET
 * write, gated by {@link BrainNavGuard#canRotateHead}) plus a short
 * acknowledgment gesture (NOD / WAVE / FRIENDLY_WAVE). Mutual
 * cool-down written into both NPCs' {@code RECENT_INTERACTION_PARTNERS}
 * so they don't immediately re-fire each other.
 *
 * <p>Registered in IDLE and SOCIAL. Does not move the entity, does not
 * set an attack target. The LookAtTargetSink behavior on CORE consumes
 * LOOK_TARGET to drive the actual head rotation.
 */
public class GreetingAcknowledgmentBehavior extends Behavior<TownspersonMob> {

    private static final double SCAN_RADIUS_SQ = 3.0 * 3.0;
    private static final long COOLDOWN_TICKS = 600L;
    private static final int DURATION_TICKS = 20;

    public GreetingAcknowledgmentBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.NEAREST_LIVING_ENTITIES, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get(), MemoryStatus.REGISTERED
        ), DURATION_TICKS);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canRotateHead(entity)) return false;
        return findPartner(entity, level.getGameTime()) != null;
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        // Release LOOK_TARGET so the head doesn't stay pinned on the
        // partner after the greeting expires — LookAtTargetSink would
        // otherwise restart on the still-present memory.
        entity.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        TownspersonMob partner = findPartner(entity, gameTime);
        if (partner == null) return;

        Brain<TownspersonMob> brain = entity.getBrain();
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(partner, true));

        Gesture pick = IdleGesturePalette.pickAcknowledgment(entity.getMoodBand(), entity.getRandom());
        entity.triggerGesture(pick);

        long now = gameTime;
        recordMutualInteraction(entity, partner, now);
    }

    /** Picks the first eligible nearby TownspersonMob, or null. */
    private static TownspersonMob findPartner(TownspersonMob entity, long gameTime) {
        Brain<TownspersonMob> brain = entity.getBrain();
        Optional<List<LivingEntity>> nearby = brain.getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES);
        if (nearby.isEmpty()) return null;

        RecentInteractionPartners log = brain.getMemory(NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get())
                .orElse(RecentInteractionPartners.EMPTY);

        for (LivingEntity e : nearby.get()) {
            if (!(e instanceof TownspersonMob other)) continue;
            if (other == entity || !other.isAlive()) continue;
            if (e.distanceToSqr(entity) > SCAN_RADIUS_SQ) continue;
            Long last = log.partners().get(other.getUUID());
            if (last != null && gameTime - last < COOLDOWN_TICKS) continue;
            return other;
        }
        return null;
    }

    private static void recordMutualInteraction(TownspersonMob a, TownspersonMob b, long now) {
        recordInteraction(a, b.getUUID(), now);
        recordInteraction(b, a.getUUID(), now);
    }

    private static void recordInteraction(TownspersonMob self, UUID otherId, long now) {
        Brain<TownspersonMob> brain = self.getBrain();
        RecentInteractionPartners current = brain
                .getMemory(NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get())
                .orElse(RecentInteractionPartners.EMPTY);
        Map<UUID, Long> next = new LinkedHashMap<>(current.partners());
        next.put(otherId, now);
        prune(next, now);
        brain.setMemory(NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get(),
                new RecentInteractionPartners(next));
    }

    /** Drop entries older than 2× cool-down — keeps the map bounded. */
    private static void prune(Map<UUID, Long> map, long now) {
        long cutoff = now - 2 * COOLDOWN_TICKS;
        map.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}
