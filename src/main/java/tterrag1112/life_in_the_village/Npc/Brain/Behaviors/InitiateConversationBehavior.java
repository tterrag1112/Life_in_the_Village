package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.ConversationRole;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.RecentInteractionPartners;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pairing logic — picks a CONVERSATION_CANDIDATES entry and writes
 * CONVERSATION_PARTNER on both NPCs. Bilateral write with race
 * handling: write self, re-check candidate is still empty, then write
 * candidate; if the candidate was claimed in the meantime, roll back
 * self.
 *
 * <p>Because Brain ticks run serially per server tick, the only race
 * is two NPCs ticking in different orders within the same tick; the
 * re-check still catches the edge case. Pair memory uses
 * {@code setMemoryWithExpiry(... 600)} so an orphaned pair (one side
 * disappears or fails to advance) self-clears.
 *
 * <p>Pure pairing — no movement, no animation. The actual engagement
 * runs in {@link EngageInConversationBehavior} on both NPCs once
 * paired.
 */
public class InitiateConversationBehavior extends Behavior<TownspersonMob> {

    private static final double MAX_PAIR_DIST_SQ = 6.0 * 6.0;
    private static final long PAIR_TTL_TICKS = 600L;
    private static final long RECENT_COOLDOWN_TICKS = 1200L;

    public InitiateConversationBehavior() {
        super(ImmutableMap.of(
                NpcMemoryTypes.CONVERSATION_CANDIDATES.get(), MemoryStatus.VALUE_PRESENT,
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), MemoryStatus.VALUE_ABSENT
        ));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return pickCandidate(entity, level.getGameTime()) != null;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        TownspersonMob target = pickCandidate(entity, gameTime);
        if (target == null) return;

        Brain<TownspersonMob> selfBrain = entity.getBrain();
        Brain<TownspersonMob> targetBrain = target.getBrain();

        // 1. Write self.
        selfBrain.setMemoryWithExpiry(
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), target, PAIR_TTL_TICKS);
        selfBrain.setMemory(
                NpcMemoryTypes.CONVERSATION_ROLE.get(), ConversationRole.SPEAKER);

        // 2. Re-check target before committing target's side.
        boolean targetClaimed = targetBrain
                .getMemory(NpcMemoryTypes.CONVERSATION_PARTNER.get()).isPresent();
        if (targetClaimed) {
            // Race lost — roll back self.
            selfBrain.eraseMemory(NpcMemoryTypes.CONVERSATION_PARTNER.get());
            selfBrain.eraseMemory(NpcMemoryTypes.CONVERSATION_ROLE.get());
            return;
        }

        // 3. Commit target side.
        targetBrain.setMemoryWithExpiry(
                NpcMemoryTypes.CONVERSATION_PARTNER.get(), entity, PAIR_TTL_TICKS);
        targetBrain.setMemory(
                NpcMemoryTypes.CONVERSATION_ROLE.get(), ConversationRole.LISTENER);
    }

    private static TownspersonMob pickCandidate(TownspersonMob entity, long gameTime) {
        Brain<TownspersonMob> brain = entity.getBrain();
        Optional<List<LivingEntity>> candidates =
                brain.getMemory(NpcMemoryTypes.CONVERSATION_CANDIDATES.get());
        if (candidates.isEmpty()) return null;

        RecentInteractionPartners selfLog = brain
                .getMemory(NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get())
                .orElse(RecentInteractionPartners.EMPTY);

        TownspersonMob best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (LivingEntity e : candidates.get()) {
            if (!(e instanceof TownspersonMob other)) continue;
            if (other == entity || !other.isAlive()) continue;
            double dsq = other.distanceToSqr(entity);
            if (dsq > MAX_PAIR_DIST_SQ) continue;
            // Candidate must be unpaired too.
            if (other.getBrain()
                    .getMemory(NpcMemoryTypes.CONVERSATION_PARTNER.get()).isPresent()) continue;
            if (recentlyMet(selfLog, other.getUUID(), gameTime)) continue;
            RecentInteractionPartners otherLog = other.getBrain()
                    .getMemory(NpcMemoryTypes.RECENT_INTERACTION_PARTNERS.get())
                    .orElse(RecentInteractionPartners.EMPTY);
            if (recentlyMet(otherLog, entity.getUUID(), gameTime)) continue;
            if (dsq < bestDistSq) {
                bestDistSq = dsq;
                best = other;
            }
        }
        return best;
    }

    private static boolean recentlyMet(RecentInteractionPartners log, UUID id, long gameTime) {
        Long last = log.partners().get(id);
        return last != null && gameTime - last < RECENT_COOLDOWN_TICKS;
    }
}
