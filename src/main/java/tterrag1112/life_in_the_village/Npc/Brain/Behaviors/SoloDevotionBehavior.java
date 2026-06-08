package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyLocation;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyLocationResolver;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Religion.FaithReconciliation;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Optional;

/**
 * Religion Rework R3e-1 — solo private devotion: the practice rung for an
 * <em>unserved</em> minority believer. When an NPC's faith is not served
 * locally (their primary faith differs from the village's officiating faith, or
 * the village has no priest) and they are devout enough to bother, they
 * periodically perform a quiet private devotion at home — a small gain in their
 * OWN faith and a minor mood lift, with no officiant.
 *
 * <p>Modelled on {@link HobbyBehavior}: {@link BrainNavGuard}-gated, resolves
 * the spot once per pick (no per-tick scan), holds its cooldown in a field (no
 * new brain memory — avoids the freeze trap), and is registered low in
 * {@code Activity.IDLE} so it yields to real work and social behaviors. Bounded:
 * small piety, a minor mood, and a long cadence — upkeep for the unserved, not a
 * fast track.</p>
 */
public class SoloDevotionBehavior extends Behavior<TownspersonMob> {

    /** Minimum primary-faith strength to bother (PietyTier.FAITHFUL boundary).
     *  Minority NPCs spawn around 0.3, so they qualify; the truly unaffiliated
     *  (< 0.2) do not. */
    private static final float MIN_STRENGTH    = 0.2f;
    /** Throttle between devotions — a long cadence so this is upkeep, not farm. */
    private static final long  DEVOTION_INTERVAL = 6000L;  // ~5 in-game hours
    private static final int   PRAY_TICKS       = 100;     // ~5s pose at the spot
    private static final float DEVOTION_PIETY   = 0.004f;  // small own-faith trickle
    private static final int   DEVOTION_MOOD    = 3;       // minor lift (≤ ambient bless)
    private static final double ARRIVAL_DIST_SQ = 4.0;
    private static final float  WALK_SPEED      = 0.6f;
    private static final int    CLOSE_ENOUGH    = 1;
    private static final int    MAX_RUN         = 1200;    // hard upper bound

    private enum Phase { WALKING, PRAYING, DONE }

    private Phase phase = Phase.WALKING;
    private BlockPos spot;
    private int subTimer;
    private boolean prayed;
    private long lastDevotionTick = Long.MIN_VALUE;

    public SoloDevotionBehavior() {
        super(ImmutableMap.of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        if (entity.isChild()) return false;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;

        long now = level.getGameTime();
        if (now - lastDevotionTick < DEVOTION_INTERVAL) return false;     // cadence throttle

        // Devout enough to practice on their own?
        if (entity.getPiety().primaryStrength() < MIN_STRENGTH) return false;

        // Unserved minority? (the one canonical served/unserved test)
        Village village = entity.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName).orElse(null);
        if (village == null) return false;
        if (!FaithReconciliation.isUnservedLocally(level, village, entity)) return false;

        // Resolve a quiet spot (home) once per pick.
        Optional<BlockPos> resolved = HobbyLocationResolver.resolve(HobbyLocation.HOME, entity, level);
        if (resolved.isEmpty()) return false;
        this.spot = resolved.get();
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        return phase != Phase.DONE;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        phase = Phase.WALKING;
        subTimer = 0;
        prayed = false;
        entity.setCurrentActivity("Private devotion");
        entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(spot, WALK_SPEED, CLOSE_ENOUGH));
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        subTimer++;
        switch (phase) {
            case WALKING -> tickWalking(entity);
            case PRAYING -> tickPraying(level, entity, gameTime);
            case DONE    -> {}
        }
    }

    private void tickWalking(TownspersonMob entity) {
        double d2 = entity.distanceToSqr(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        if (d2 <= ARRIVAL_DIST_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.PRAYING;
            subTimer = 0;
            return;
        }
        // Unreachable spot — bail without reward.
        if (subTimer > 600 && !entity.getNavigation().isInProgress()) {
            phase = Phase.DONE;
        }
    }

    private void tickPraying(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getLookControl().setLookAt(
                spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5);
        if (subTimer < PRAY_TICKS) return;
        applyDevotion(entity, gameTime);
        phase = Phase.DONE;
    }

    /** The bounded reward: a small gain in the NPC's OWN faith + a minor mood
     *  lift. No officiant; counts toward the believer's rite-attendance upkeep
     *  so an unserved-but-devout NPC does not fall out of practice. */
    private void applyDevotion(TownspersonMob entity, long gameTime) {
        if (prayed) return;
        prayed = true;
        entity.getPiety().primaryReligion().ifPresent(mine ->
                entity.getPiety().adjustBelief(mine, DEVOTION_PIETY));
        entity.getMood().applyWithRawMagnitude(MoodTrigger.LETTER_FROM_FRIEND, DEVOTION_MOOD, gameTime);
        entity.getPiety().recordRiteAttendance(gameTime);
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        // Arm the cadence throttle whenever a session ran (completed or aborted),
        // so an unreachable/contended spot doesn't retry every tick.
        lastDevotionTick = gameTime;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        entity.clearCurrentActivity();
        phase = Phase.WALKING;
        spot = null;
        subTimer = 0;
        prayed = false;
    }
}
