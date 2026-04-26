package tterrag1112.life_in_the_village.Npc.BusinessFront;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.EnumSet;
import java.util.UUID;

/**
 * NPC walks to the player and offers services. Spec lines 130-145.
 *
 * <p>Activated externally by {@link GreeterAssignment#assignFor}, not
 * by {@link #canUse} polling — only the assignment code knows which
 * worker to pick and which player to target. The goal returns false
 * from {@link #canUse} until {@link #assign} is called, then yields
 * true while the assignment is live.</p>
 *
 * <p>Phases (spec line 138):</p>
 * <ol>
 *   <li>{@code APPROACH} — walk to within {@link #INTERACT_RANGE_SQ}
 *   blocks of the player; bark on entering this phase.</li>
 *   <li>{@code ATTENDING} — hold position; flips
 *   {@link BusinessFrontStatus#GREETER_ATTENDING}.</li>
 *   <li>{@code FOLLOW_UP} — wait {@link #ATTEND_TIMEOUT_TICKS} for the
 *   player to right-click. If they never do, demote to DISMISS.</li>
 *   <li>{@code DISMISS} — clear assignment, return to production.</li>
 * </ol>
 *
 * <p>Priority (spec line 147): between combat (P_COMBAT = 2) and work
 * (P_WORK_PRIMARY = 8). v1 puts this at P_SOCIAL_HIGH (4) so it
 * pre-empts production but yields to combat / survival. Wired in
 * {@link tterrag1112.life_in_the_village.Entities.Goals.Profession.ProfessionGoalFactory}.</p>
 */
public class GreetPlayerGoal extends Goal {

    /** Bark range — close enough to ATTEND. */
    private static final int INTERACT_RANGE_SQ = 16;
    /** How long ATTENDING/FOLLOW_UP holds before auto-DISMISS. */
    private static final int ATTEND_TIMEOUT_TICKS = 600; // 30 in-game seconds
    /** Anti-spam: same player can't re-trigger a greet within this gap. */
    public static final int RE_GREET_COOLDOWN_TICKS = 1200; // 60s, per spec "Open decisions" #2

    enum Phase { APPROACH, ATTENDING, FOLLOW_UP, DISMISS }

    private final TownspersonMob entity;
    private ServerPlayer target;
    private Phase phase = Phase.DISMISS;
    private long phaseStartTick = 0L;
    private boolean hasBarked = false;

    public GreetPlayerGoal(TownspersonMob entity) {
        this.entity = entity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * Called by {@link GreeterAssignment} to seat a target. Idempotent
     * — re-assigning the same player while still attending just updates
     * the timer.
     */
    public void assign(ServerPlayer player) {
        this.target = player;
        this.phase = Phase.APPROACH;
        this.phaseStartTick = entity.level().getGameTime();
        this.hasBarked = false;
    }

    /** Used by debug commands to clear the seat. */
    public void clear() {
        this.target = null;
        this.phase = Phase.DISMISS;
    }

    @Override
    public boolean canUse() {
        return target != null && target.isAlive() && !target.isRemoved()
                && entity.isWorkTime();
    }

    @Override
    public boolean canContinueToUse() {
        if (!canUse()) return false;
        return phase != Phase.DISMISS;
    }

    @Override public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        phaseStartTick = entity.level().getGameTime();
        hasBarked = false;
        entity.setCurrentActivity("Greeting customer");
    }

    @Override
    public void tick() {
        if (target == null || !(entity.level() instanceof ServerLevel level)) {
            phase = Phase.DISMISS;
            return;
        }
        long now = level.getGameTime();
        switch (phase) {
            case APPROACH -> tickApproach(level, now);
            case ATTENDING, FOLLOW_UP -> tickAttending(now);
            case DISMISS -> { /* will exit via canContinueToUse */ }
        }
    }

    @Override
    public void stop() {
        // Leave BusinessFront state alone; the assignment selector
        // handles state transitions when the player leaves the building.
        target = null;
        phase = Phase.DISMISS;
        entity.getNavigation().stop();
    }

    // ── Phase implementations ─────────────────────────────────────────────

    private void tickApproach(ServerLevel level, long now) {
        BlockPos pos = target.blockPosition();
        double distSq = entity.distanceToSqr(pos.getX(), pos.getY(), pos.getZ());
        if (distSq <= INTERACT_RANGE_SQ) {
            entity.getNavigation().stop();
            phase = Phase.ATTENDING;
            phaseStartTick = now;
            // Flip the building's status so right-click handler picks
            // up the BusinessFrontScreen path.
            UUID buildingId = entity.getAssignedBuildingId().orElse(null);
            if (buildingId != null) {
                BusinessFrontTracker.put(BusinessFrontTracker.getOrCreate(buildingId)
                        .withGreeter(entity.getUUID(), target.getUUID(), now,
                                BusinessFrontStatus.GREETER_ATTENDING));
            }
            return;
        }
        // Bark exactly once on entering the approach window.
        if (!hasBarked) {
            bark(level);
            hasBarked = true;
            UUID buildingId = entity.getAssignedBuildingId().orElse(null);
            if (buildingId != null) {
                BusinessFrontTracker.put(BusinessFrontTracker.getOrCreate(buildingId)
                        .withGreeter(entity.getUUID(), target.getUUID(), now,
                                BusinessFrontStatus.GREETER_APPROACHING));
            }
        }
        entity.getNavigation().moveTo(pos.getX(), pos.getY(), pos.getZ(), 1.0);
    }

    private void tickAttending(long now) {
        if (target == null) { phase = Phase.DISMISS; return; }
        // If the player has wandered out of the building, abort.
        UUID buildingId = entity.getAssignedBuildingId().orElse(null);
        if (buildingId != null) {
            UUID playerHere = BuildingPresenceTracker.getBuildingFor(target.getUUID()).orElse(null);
            if (playerHere == null || !playerHere.equals(buildingId)) {
                phase = Phase.DISMISS;
                return;
            }
        }
        if (now - phaseStartTick > ATTEND_TIMEOUT_TICKS) {
            phase = Phase.DISMISS;
        }
    }

    /** Bark text varies by greeter profession, matching spec line 196's tree. */
    private void bark(ServerLevel level) {
        Component bark = barkFor(entity.getProfession(), entity);
        target.displayClientMessage(bark, /* actionBar */ true);
    }

    private static Component barkFor(Profession p, TownspersonMob npc) {
        String name = npc.getNpcName();
        return switch (p) {
            case MERCHANT, STOCKPILE_KEEPER -> Component.literal("[" + name + "] Welcome — looking for anything in particular?");
            case BLACKSMITH, CARPENTER, STONEMASON, WEAVER, CANDLEMAKER, BAKER, MILLER ->
                    Component.literal("[" + name + "] Welcome to my workshop. Need something made?");
            case INNKEEPER -> Component.literal("[" + name + "] Take a seat, traveller.");
            case SCRIBE    -> Component.literal("[" + name + "] A letter, a contract, or a copy?");
            case LIBRARIAN -> Component.literal("[" + name + "] Quietly, please. What are you looking for?");
            case SCHOLAR   -> Component.literal("[" + name + "] Hmm? Oh, do come in.");
            case PRIEST    -> Component.literal("[" + name + "] Peace be with you. How may I help?");
            default        -> Component.literal("[" + name + "] Welcome.");
        };
    }
}
