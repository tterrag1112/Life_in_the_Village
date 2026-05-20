package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Gesture;
import tterrag1112.life_in_the_village.Npc.Brain.IdleGesturePalette;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.ConversationRole;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.RecentInteractionPartners;
import tterrag1112.life_in_the_village.Npc.Gossip.GossipExchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs on each NPC of a conversation pair independently. Walks to a
 * meeting midpoint (TRAVEL), then locks gaze and exchanges knowledge
 * (ENGAGE), then dissolves the pair (DISSOLVE).
 *
 * <p>Knowledge transfer entry point:
 * {@link GossipExchange#run(ServerLevel, TownspersonMob, TownspersonMob, long)}
 * — only the SPEAKER role fires it (one-directional per turn keeps
 * volume under control; the LISTENER may speak back on a future
 * conversation if the pair re-forms with roles flipped).
 *
 * <p>Stop conditions: TTL on CONVERSATION_PARTNER expires (the
 * setMemoryWithExpiry from initiate handles orphans), partner
 * despawns / dies, partner's CONVERSATION_PARTNER no longer points
 * back at us (asymmetric break — we dissolve cleanly).
 */
public class EngageInConversationBehavior extends Behavior<TownspersonMob> {

    private enum Phase { TRAVEL, ENGAGE, DONE }

    private static final float WALK_SPEED = 0.45f;
    private static final int CLOSE_ENOUGH = 1;
    private static final double ENGAGE_DIST_SQ = 2.0 * 2.0;
    private static final double MAX_PARTNER_DIST_SQ = 10.0 * 10.0;
    private static final int ENGAGE_TICKS = 200;
    private static final int MAX_RUN = 600;

    private Phase phase = Phase.TRAVEL;
    private long engageStartTick = -1L;
    private boolean exchangeFired = false;

    public EngageInConversationBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (!BrainNavGuard.canRotateHead(entity)) return false;
        TownspersonMob partner = partnerOrNull(entity);
        return partner != null && partner.distanceToSqr(entity) <= MAX_PARTNER_DIST_SQ;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.TRAVEL;
        engageStartTick = -1L;
        exchangeFired = false;
        TownspersonMob partner = partnerOrNull(entity);
        if (partner == null) { phase = Phase.DONE; return; }
        walkToMeetingPoint(entity, partner);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (phase == Phase.DONE) return false;
        TownspersonMob partner = partnerOrNull(entity);
        if (partner == null) return false;
        if (partner.distanceToSqr(entity) > MAX_PARTNER_DIST_SQ) return false;
        // Asymmetric break detection.
        LivingEntity partnerSees = partner.getBrain()
                .getMemory(NpcMemoryTypes.CONVERSATION_PARTNER.get()).orElse(null);
        if (partnerSees != null && partnerSees != entity) return false;
        return true;
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        TownspersonMob partner = partnerOrNull(entity);
        if (partner == null) { phase = Phase.DONE; return; }

        switch (phase) {
            case TRAVEL -> {
                if (partner.distanceToSqr(entity) <= ENGAGE_DIST_SQ) {
                    enterEngage(entity, partner, gameTime);
                }
            }
            case ENGAGE -> {
                // Refresh gaze each tick so head stays locked.
                Brain<TownspersonMob> brain = entity.getBrain();
                brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(partner, true));
                if (!exchangeFired && isSpeaker(entity)) {
                    GossipExchange.run(level, entity, partner, gameTime);
                    exchangeFired = true;
                }
                if (gameTime - engageStartTick >= ENGAGE_TICKS) {
                    phase = Phase.DONE;
                }
            }
            case DONE -> {
                // canStillUse will return false next tick.
            }
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        Brain<TownspersonMob> brain = entity.getBrain();
        TownspersonMob partner = partnerOrNull(entity);

        brain.eraseMemory(NpcMemoryTypes.CONVERSATION_PARTNER.get());
        brain.eraseMemory(NpcMemoryTypes.CONVERSATION_ROLE.get());
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.eraseMemory(MemoryModuleType.LOOK_TARGET);

        // Bilateral recent-interaction write so re-pairing cools down.
        if (partner != null) {
            recordInteraction(entity, partner.getUUID(), gameTime);
            recordInteraction(partner, entity.getUUID(), gameTime);
        }

        phase = Phase.DONE;
        engageStartTick = -1L;
        exchangeFired = false;
    }

    private void enterEngage(TownspersonMob entity, TownspersonMob partner, long gameTime) {
        phase = Phase.ENGAGE;
        engageStartTick = gameTime;
        Brain<TownspersonMob> brain = entity.getBrain();
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(partner, true));
        Gesture g = IdleGesturePalette.pickAcknowledgment(entity.getMoodBand(), entity.getRandom());
        entity.triggerGesture(g);
    }

    private static void walkToMeetingPoint(TownspersonMob entity, TownspersonMob partner) {
        Vec3 midpoint = entity.position().add(partner.position()).scale(0.5);
        BlockPos target = BlockPos.containing(midpoint);
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(target, WALK_SPEED, CLOSE_ENOUGH));
    }

    private static TownspersonMob partnerOrNull(TownspersonMob entity) {
        LivingEntity partner = entity.getBrain()
                .getMemory(NpcMemoryTypes.CONVERSATION_PARTNER.get()).orElse(null);
        if (partner instanceof TownspersonMob t && t.isAlive()) return t;
        return null;
    }

    private static boolean isSpeaker(TownspersonMob entity) {
        return entity.getBrain()
                .getMemory(NpcMemoryTypes.CONVERSATION_ROLE.get())
                .orElse(ConversationRole.LISTENER) == ConversationRole.SPEAKER;
    }

    private static void recordInteraction(TownspersonMob self, UUID otherId, long now) {
        Brain<TownspersonMob> brain = self.getBrain();
        RecentInteractionPartners current = brain
                .getMemory(NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get())
                .orElse(RecentInteractionPartners.EMPTY);
        Map<UUID, Long> next = new LinkedHashMap<>(current.partners());
        next.put(otherId, now);
        // Bounded — same prune rule as greeting.
        long cutoff = now - 2400L;
        next.entrySet().removeIf(e -> e.getValue() < cutoff);
        brain.setMemory(NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get(),
                new RecentInteractionPartners(next));
    }
}
