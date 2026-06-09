package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyLocation;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyLocationResolver;

import java.util.Optional;

/**
 * Liveliness L3 (Part A) — social gathering. During the SOCIAL activity an
 * idle NPC with no higher-priority social task (eat / converse / court /
 * mentor run first) drifts to the village gathering spot (town square) and
 * lingers nearby, so the co-located conversation behaviors actually pair up
 * (they're pairing-based and SOCIAL-only — today nothing brings NPCs
 * together, so they rarely fire).
 *
 * <p>Cheap: gated on {@code WALK_TARGET} absent + {@link BrainNavGuard
 * #canSteerNavigation} + a per-pick {@code GATHER_COOLDOWN}; resolves the
 * square once per pick (no per-tick scan). Registered low in SOCIAL (above
 * only the ambient fallbacks), so it yields to the real social behaviors
 * and to nav. Each pick walks to a spot within {@link #LINGER_RADIUS} of the
 * square so NPCs spread + re-cluster rather than stacking on one block.
 */
public class GatherAtSquareBehavior extends Behavior<TownspersonMob> {

    private static final float WALK_SPEED   = 0.45f;
    private static final int   CLOSE_ENOUGH = 1;
    private static final int   LINGER_RADIUS = 4;
    private static final int   COOLDOWN_MIN = 120;
    private static final int   COOLDOWN_MAX = 400;
    private static final int   MAX_RUN      = 400;

    public GatherAtSquareBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.GATHER_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (yieldToCommitting(entity)) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return HobbyLocationResolver.resolve(
                HobbyLocation.TOWN_SQUARE, entity, level).isPresent();
    }

    /**
     * Committing-preempts-ambient — true when this ambient gather must yield the
     * nav channel: a customer to greet ({@code GREET_TARGET}), or the SOCIAL window
     * has ended (should-be-home / work begins), so the milling NPC doesn't leak
     * past the transition and pin itself at the square. Cheap predicate reads.
     */
    private static boolean yieldToCommitting(TownspersonMob entity) {
        // Greet / a festival-rite the NPC is pulled into / SOCIAL ending all
        // preempt the ambient square-gather (an event attendee converges on the
        // venue via AttendGatheringBehavior, registered above this).
        return GreetPlayerBehavior.isGreetPending(entity)
                || entity.isEventTime()
                || !entity.isSocialTime();
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        Optional<BlockPos> square = HobbyLocationResolver.resolve(
                HobbyLocation.TOWN_SQUARE, entity, level);
        if (square.isEmpty()) return;
        RandomSource rng = entity.getRandom();
        BlockPos spot = square.get().offset(
                rng.nextInt(2 * LINGER_RADIUS + 1) - LINGER_RADIUS, 0,
                rng.nextInt(2 * LINGER_RADIUS + 1) - LINGER_RADIUS);
        NpcBehaviorHelpers.walkTo(entity, spot, WALK_SPEED);
        entity.setCurrentActivity(ActivityFlavor.pick("square", entity.getRandom()));
        // Cosmetic prop while milling about (L3 part B). Cleared on stop.
        AmbientProps.applyDisplay(entity);
        armCooldown(entity);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        // Actively yield the channel when a committing need pends or SOCIAL ends —
        // vanilla won't preempt a RUNNING behavior, so the ambient stops itself.
        if (yieldToCommitting(entity)) return false;
        return entity.getNavigation().isInProgress();
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(NpcMemoryTypes.CARRYING_DISPLAY_ITEM.get());
        // Free the nav channel for the committer (erase WALK_TARGET so the gates
        // open, stop the in-flight path so canSteerNavigation clears immediately).
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.getNavigation().stop();
    }

    private void armCooldown(TownspersonMob entity) {
        int ttl = COOLDOWN_MIN + entity.getRandom().nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.GATHER_COOLDOWN.get(),
                entity.level().getGameTime(), ttl);
    }
}
