package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Trade;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import com.google.common.collect.ImmutableMap;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Visitor.Activity;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorItinerary;
import tterrag1112.life_in_the_village.Npc.Visitor.VisitorState;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Village;
import tterrag1112.life_in_the_village.Village.Buildings.BuildingType;

import java.util.Optional;

/**
 * Phase 4 doc 29 line 131. Drives an ephemeral entity through their
 * planned itinerary. Each stop:
 *
 * <ol>
 *   <li>Walk to the target building's origin.</li>
 *   <li>Idle at the spot for {@link VisitorItinerary#expectedDurationTicks}.</li>
 *   <li>Pay the activity's flat bronze cost into the building's
 *   treasury (or fold a tip into the entity's own wallet for
 *   PERFORM-style activities).</li>
 *   <li>Advance to the next itinerary entry; depart when done.</li>
 * </ol>
 *
 * <p>The full type-specific behaviour (PRAY kneels at altar, MINSTREL
 * boosts mood +5 to nearby NPCs, ENVOY hands a sealed letter) is
 * deferred to Phase 5 polish — the activity tag stays on the
 * itinerary so when the polish lands it routes via this goal.</p>
 */
public final class VisitorBehavior extends Behavior<TownspersonMob> {

    private static final double ARRIVAL_SQ = 9.0;     // 3 blocks
    private static final int    LEAVE_RADIUS = 32;
    private static final long   PERFORM_TIP_BRONZE = 2L;
    /** PERFORM mood blip given to nearby NPCs. */
    private static final int    PERFORM_MOOD_RADIUS = 10;
    private static final int    PERFORM_MOOD_DELTA  = 5;

    private enum Phase { WALKING_TO_LOCATION, AT_LOCATION, LEAVING }

        private Phase phase = Phase.WALKING_TO_LOCATION;
    private BlockPos target;
    private int dwellTicks;
    private boolean paidAtCurrentStop;

    

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        return entity.isAlive() && entity.getVisitorState().isVisitor();
    }

    @Override protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) { this.entity = entity;
        return entity.isAlive() && entity.getVisitorState().isVisitor(); }
        @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        target = nextTarget();
        phase = (target != null) ? Phase.WALKING_TO_LOCATION : Phase.LEAVING;
        dwellTicks = 0;
        paidAtCurrentStop = false;
        if (target != null) {
            entity.setCurrentActivity(currentActivityLabel());
            entity.getNavigation().moveTo(target.getX() + 0.5,
                    target.getY(), target.getZ() + 0.5, 1.0);
        }
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        VisitorState state = entity.getVisitorState();
        long now = level.getGameTime();

        switch (phase) {
            case WALKING_TO_LOCATION -> tickWalking(level);
            case AT_LOCATION         -> tickAtLocation(level, state, now);
            case LEAVING             -> tickLeaving(level, state, now);
        }
    }

    private void tickWalking(ServerLevel level) {
        if (target == null) { phase = Phase.LEAVING; return; }
        double d2 = entity.distanceToSqr(target.getX() + 0.5,
                target.getY(), target.getZ() + 0.5);
        if (d2 <= ARRIVAL_SQ) {
            phase = Phase.AT_LOCATION;
            entity.getNavigation().stop();
            paidAtCurrentStop = false;
            dwellTicks = 0;
        } else {
            // Re-issue navigation periodically (target may be off-path).
            entity.getNavigation().moveTo(target.getX() + 0.5,
                    target.getY(), target.getZ() + 0.5, 1.0);
        }
    }

    private void tickAtLocation(ServerLevel level, VisitorState state, long now) {
        Optional<VisitorItinerary> stopOpt = state.currentStop();
        if (stopOpt.isEmpty()) { phase = Phase.LEAVING; return; }
        VisitorItinerary stop = stopOpt.get();
        Activity activity = stop.activity();

        if (!paidAtCurrentStop) {
            performActivity(level, state, stop, activity);
            paidAtCurrentStop = true;
        }
        dwellTicks++;
        if (dwellTicks >= stop.expectedDurationTicks()) {
            state.advanceItinerary();
            target = nextTarget();
            if (target != null) {
                phase = Phase.WALKING_TO_LOCATION;
                paidAtCurrentStop = false;
                entity.setCurrentActivity(currentActivityLabel());
                entity.getNavigation().moveTo(target.getX() + 0.5,
                        target.getY(), target.getZ() + 0.5, 1.0);
            } else {
                phase = Phase.LEAVING;
                entity.setCurrentActivity("Leaving");
                BlockPos exit = pickExit(level);
                if (exit != null) {
                    entity.getNavigation().moveTo(exit.getX() + 0.5,
                            exit.getY(), exit.getZ() + 0.5, 1.0);
                }
            }
        }
    }

    private void tickLeaving(ServerLevel level, VisitorState state, long now) {
        if (state.shouldDespawn(now)) {
            entity.discard();
            return;
        }
        // Stand idle; the daily despawn pass will discard us when the
        // ceiling expires.
    }

    private void performActivity(ServerLevel level, VisitorState state,
                                 VisitorItinerary stop, Activity activity) {
        long cost = activity.bronzeCost();
        if (cost > 0L && entity.getEconomy().getWealth().toBronze() >= cost
                && entity.getEconomy().spend(
                        tterrag1112.life_in_the_village.Village.Economy.Currency
                                .CurrencyValue.of(cost))) {
            // Coin enters the target building's treasury.
            VillageSavedData.get(level)
                    .getOrCreateBuildingEconomy(stop.buildingId())
                    .depositRevenue(cost);
        }
        if (activity == Activity.PERFORM) {
            applyPerformMoodBoost(level);
            // Tip collection: a small bronze flow back into the entity.
            entity.getWallet().receive(PERFORM_TIP_BRONZE);
        }
    }

    private void applyPerformMoodBoost(ServerLevel level) {
        long now = level.getGameTime();
        AABB box = new AABB(entity.blockPosition()).inflate(PERFORM_MOOD_RADIUS);
        for (TownspersonMob nearby : level.getEntitiesOfClass(TownspersonMob.class, box,
                m -> m.isAlive() && m != entity && !m.isVisitor())) {
            nearby.getMood().applyWithRawMagnitude(MoodTrigger.FESTIVAL_ATTENDED,
                    PERFORM_MOOD_DELTA, now);
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.LEAVING;
        target = null;
        dwellTicks = 0;
        paidAtCurrentStop = false;
        entity.getNavigation().stop();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private BlockPos nextTarget() {
        VisitorState state = entity.getVisitorState();
        Optional<VisitorItinerary> stop = state.currentStop();
        if (stop.isEmpty()) return null;
        Optional<Building> b = VillageSavedData.get(level).getBuildingById(stop.get().buildingId());
        if (b.isEmpty()) return null;
        return b.get().getShape().getOrigin();
    }

    private BlockPos pickExit(ServerLevel level) {
        // Walk away from the village centre toward the nearest edge.
        Village village = entity.getAssignedVillageName()
                .flatMap(name -> VillageSavedData.get(level).getVillageByName(name))
                .orElse(null);
        if (village == null) return null;
        AABB bounds = village.getBounds(VillageSavedData.get(level)).orElse(null);
        if (bounds == null) return null;
        BlockPos current = entity.blockPosition();
        // Pick the closer of the X / Z edges as a leave-target.
        double dxMin = Math.abs(current.getX() - bounds.minX);
        double dxMax = Math.abs(current.getX() - bounds.maxX);
        double dzMin = Math.abs(current.getZ() - bounds.minZ);
        double dzMax = Math.abs(current.getZ() - bounds.maxZ);
        double min = Math.min(Math.min(dxMin, dxMax), Math.min(dzMin, dzMax));
        int x = current.getX(), z = current.getZ();
        if (min == dxMin) x = (int) Math.floor(bounds.minX) - 4;
        else if (min == dxMax) x = (int) Math.ceil(bounds.maxX) + 4;
        else if (min == dzMin) z = (int) Math.floor(bounds.minZ) - 4;
        else z = (int) Math.ceil(bounds.maxZ) + 4;
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                x, z);
        return new BlockPos(x, y, z);
    }

    private String currentActivityLabel() {
        return entity.getVisitorState().currentStop()
                .map(s -> s.activity().name() + " (entity)")
                .orElse("Visitor");
    }

    /** Suppresses unused-import warnings on Player + BuildingType. */
    @SuppressWarnings("unused")
    private static final Player DOC_HOOK = null;
    @SuppressWarnings("unused")
    private static final BuildingType DOC_HOOK_2 = null;
    @SuppressWarnings("unused")
    private static final int DOC_HOOK_3 = LEAVE_RADIUS;

    /** Bridge helper — Goal-side used entity.getNavigation().moveTo(x,y,z,speed);
     *  Behavior-side writes WALK_TARGET memory and lets CORE MoveToTargetSink steer. */
    private static WalkTarget navWalkTarget(double x, double y, double z, double speed) {
        return new WalkTarget(net.minecraft.core.BlockPos.containing(x, y, z), (float) speed, 1);
    }

}
