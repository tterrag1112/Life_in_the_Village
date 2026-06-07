package tterrag1112.life_in_the_village.Npc.Brain.Behaviors;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyLocation;
import tterrag1112.life_in_the_village.Npc.Hobby.HobbyLocationResolver;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecution;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings;
import tterrag1112.life_in_the_village.Village.Event.CommunityGatherings;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Gathering.CommunityGathering;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Religion Rework R2b — the general attend-gathering behavior. Walks an
 * attendee to the gathering's venue and keeps them milling there for the
 * gathering's duration. Works for EVERY {@link CommunityGathering} kind
 * (festivals, trials, weddings, holy-day rites) — religion is just the first
 * beneficiary.
 *
 * <h3>How attendance flows here</h3>
 * When a gathering goes ACTIVE its attendees get an {@code eventOverride}
 * (an EventType): life events via {@code EventAttendance} (required + invited,
 * also recorded in {@code actualAttendees}), the Phase-3 festivals village-wide
 * via their {@code EventEffects} start handlers. R2b wires the long-documented
 * collapse — {@code ScheduleResolver.phaseAt} now forces an event-time NPC to
 * {@code LEISURE} → {@code Activity.IDLE} for the gathering's duration. Before
 * R2b they were merely pulled off work and stood still; this behavior
 * (registered high in IDLE) now physically gathers them. The gate is an
 * {@code eventOverride}-type match against the active gathering — general
 * across festivals, life events, and any future override source.
 *
 * <h3>Convergence with the officiant</h3>
 * A religious / life-stage gathering's venue resolves to its <em>linked
 * rite's</em> {@code location()} (the temple) — the SAME point
 * {@code PriestBehavior} walks the presider to — so the congregation and the
 * priest meet at one place. The first attendee to resolve writes that venue
 * back onto the (previously location-less) gathering so everyone agrees.
 *
 * <h3>Cheap, modelled on {@link GatherAtSquareBehavior}</h3>
 * Gated on {@code WALK_TARGET} absent + a {@code GATHER_COOLDOWN} re-pick
 * throttle (reused — an event-time attendee is in IDLE, never running the
 * SOCIAL square-gather, so the two never overlap) + {@link BrainNavGuard}.
 * Resolves the gathering + venue once per pick (no per-tick village scans);
 * walks to a spot within {@link #LINGER_RADIUS} so the crowd spreads and
 * re-clusters rather than stacking. Tightening the crowd into a staged
 * spectacle is R3.
 */
public class AttendGatheringBehavior extends Behavior<TownspersonMob> {

    private static final float WALK_SPEED    = 0.5f;
    private static final int   LINGER_RADIUS = 4;
    private static final int   COOLDOWN_MIN  = 80;
    private static final int   COOLDOWN_MAX  = 200;
    private static final int   MAX_RUN       = 600;

    /** Resolved once per pick in {@link #checkExtraStartConditions}. */
    private CommunityGathering target;
    private BlockPos venue;

    public AttendGatheringBehavior() {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                NpcMemoryTypes.GATHER_COOLDOWN.get(), MemoryStatus.VALUE_ABSENT
        ), MAX_RUN);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        target = null;
        venue = null;
        if (!entity.isEventTime()) return false;                 // not pulled to an event
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;

        Village village = entity.getAssignedVillageName()
                .flatMap(VillageSavedData.get(level)::getVillageByName).orElse(null);
        if (village == null) return false;

        // The eventOverride (an EventType, stamped by EventAttendance for life
        // events and by the Phase-3 festival start handlers village-wide) is the
        // signal "this NPC was pulled into an event of this type". Find the
        // matching active gathering to get its venue. Type-match (not
        // actualAttendees membership) is what makes this GENERAL — festivals
        // set the override without populating actualAttendees.
        VillageEvent.EventType ov = entity.getEventOverride().orElse(null);
        if (ov == null) return false;
        long now = level.getGameTime();
        UUID me = entity.getUUID();
        for (CommunityGathering g : CommunityGatherings.activeInVillage(level, village.getId(), now)) {
            if (!(g instanceof VillageEvent ve) || ve.getType() != ov) continue;
            if (isPresider(level, g, me)) continue;              // officiant officiates, not attends
            BlockPos v = resolveVenue(level, entity, village, g);
            if (v == null) continue;
            target = g;
            venue = v;
            return true;
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        if (target == null || venue == null) return;

        // Convergence write-back: pin the resolved venue onto a location-less
        // gathering so every attendee (and future consumers) agree on it.
        if (target instanceof VillageEvent ve && ve.getLocation().isEmpty()) {
            ve.setLocation(venue);
            VillageSavedData.get(level).setDirty();
        }

        RandomSource rng = entity.getRandom();
        BlockPos spot = venue.offset(
                rng.nextInt(2 * LINGER_RADIUS + 1) - LINGER_RADIUS, 0,
                rng.nextInt(2 * LINGER_RADIUS + 1) - LINGER_RADIUS);

        entity.getBrain().setMemory(NpcMemoryTypes.UPCOMING_EVENT_TARGET.get(), target.gatheringId());
        entity.getBrain().setMemory(NpcMemoryTypes.EVENT_ATTENDANCE_POS.get(),
                GlobalPos.of(level.dimension(), venue));
        NpcBehaviorHelpers.walkTo(entity, spot, WALK_SPEED);
        entity.setCurrentActivity("Attending " + label(target));
        armCooldown(entity);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        // Walk to the venue, then yield (the GATHER_COOLDOWN throttles the next
        // re-approach, so the attendee mills around the venue for the duration;
        // EventAttendance.clearOverrides ends attendance when the gathering does).
        return entity.getNavigation().isInProgress();
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        entity.getBrain().eraseMemory(NpcMemoryTypes.UPCOMING_EVENT_TARGET.get());
        entity.getBrain().eraseMemory(NpcMemoryTypes.EVENT_ATTENDANCE_POS.get());
        target = null;
        venue = null;
    }

    // ── Venue resolution ─────────────────────────────────────────────────

    /**
     * Resolution order: (1) the gathering's pinned location; (2) for a
     * religious / life-stage gathering, its linked rite's location (the temple
     * — converges with the officiant); (3) the town square / village centre.
     */
    private static BlockPos resolveVenue(ServerLevel level, TownspersonMob entity,
                                         Village village, CommunityGathering g) {
        Optional<BlockPos> pinned = g.gatheringLocation();
        if (pinned.isPresent()) return pinned.get();

        BlockPos rite = linkedRiteLocation(level, g);
        if (rite != null) return rite;

        BlockPos square = HobbyLocationResolver
                .resolve(HobbyLocation.TOWN_SQUARE, entity, level).orElse(null);
        if (square != null) return square;
        return village.getVillageCentre();
    }

    /** The location of a VillageEvent's linked blessing rite, or null. */
    private static BlockPos linkedRiteLocation(ServerLevel level, CommunityGathering g) {
        RiteExecution rite = linkedRite(level, g);
        if (rite == null) return null;
        BlockPos loc = rite.location();
        return BlockPos.ZERO.equals(loc) ? null : loc;
    }

    private static RiteExecution linkedRite(ServerLevel level, CommunityGathering g) {
        if (!(g instanceof VillageEvent ve)) return null;
        String riteIdStr = ve.getEventData().get(CeremonyBlessings.RITE_ID_KEY);
        if (riteIdStr == null) return null;
        try {
            return RiteSavedData.get(level).getRite(UUID.fromString(riteIdStr)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when {@code me} is the gathering's officiating priest (its own
     *  presider, or its linked rite's presider) — they officiate, not attend. */
    private static boolean isPresider(ServerLevel level, CommunityGathering g, UUID me) {
        if (g instanceof RiteExecution r) {
            return r.presidingPriestId().map(me::equals).orElse(false);
        }
        RiteExecution rite = linkedRite(level, g);
        return rite != null && rite.presidingPriestId().map(me::equals).orElse(false);
    }

    private static String label(CommunityGathering g) {
        if (g instanceof VillageEvent ve) {
            return ve.getType().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        }
        if (g instanceof RiteExecution r) {
            return r.type().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        }
        return "a gathering";
    }

    private void armCooldown(TownspersonMob entity) {
        int ttl = COOLDOWN_MIN + entity.getRandom().nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
        entity.getBrain().setMemoryWithExpiry(
                NpcMemoryTypes.GATHER_COOLDOWN.get(),
                entity.level().getGameTime(), ttl);
    }
}
