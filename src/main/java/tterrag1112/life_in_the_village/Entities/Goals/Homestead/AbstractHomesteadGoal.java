package tterrag1112.life_in_the_village.Entities.Goals.Homestead;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tterrag1112.life_in_the_village.Entities.FamilyRole;
import tterrag1112.life_in_the_village.Entities.HouseholdData;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Schedule.DayPhase;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.Decoration.Adjunct.AdjunctPlot;

import java.util.EnumSet;

/**
 * B2.6 — base class for the homestead goal stack. Subclasses are
 * thin wrappers that pin the {@link FamilyRole} filter (SPOUSE for
 * primary; CHILD for SOCIAL-phase chores) and the
 * {@link DayPhase} predicate; the dispatch + per-tick work loop
 * lives here.
 *
 * <p>Per the user's "SPOUSE / CHILD only — HEAD untouched"
 * direction, this goal never matches HEAD-role NPCs even if the
 * household has a rolled plot.</p>
 *
 * <p>Schedule integration:</p>
 * <ul>
 *   <li>{@link Spouse} runs during any {@code WORK_*} phase
 *       (matches {@code DayPhase.isWork()}).</li>
 *   <li>{@link Child} runs during {@code SOCIAL} phase.</li>
 *   <li>{@link Elderly} (Phase 6.3.3.j.2.F) runs during {@code SOCIAL}
 *       phase as a light fallback when the elder is not actively
 *       mentoring — yields to {@code MentorBehavior} via the
 *       {@code mentorTargetId} check.</li>
 * </ul>
 *
 * <p>Dispatch: looks up the household's
 * {@link HouseholdData#getHomesteadPlotType()} (set during V2
 * household formation) and routes per-tick work to the
 * {@link HomesteadHandler} registered for that plot type. When
 * the plot type is null (HOUSE didn't win the probability gate),
 * dispatch falls through to
 * {@link HomesteadHandlerRegistry#GENERIC_CHORES} so SPOUSE / CHILD
 * still have something to do.</p>
 */
public abstract class AbstractHomesteadGoal extends Goal {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractHomesteadGoal.class);

    /** Goal interval — re-evaluate canUse / dispatch every quarter
     *  second so we don't burn ticks on a stop-and-go family chore. */
    public static final int TICK_INTERVAL = 5;

    protected final TownspersonMob npc;
    protected final FamilyRole requiredRole;

    private int tickInGoal;
    private HomesteadHandler activeHandler;
    private HouseholdData household;
    private AdjunctPlot plot;
    private Building house;

    protected AbstractHomesteadGoal(TownspersonMob npc, FamilyRole requiredRole) {
        this.npc = npc;
        this.requiredRole = requiredRole;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Subclass returns the schedule phase predicate. */
    protected abstract boolean phaseAllows(DayPhase phase);

    @Override
    public boolean canUse() {
        if (npc == null || npc.getFamilyRole() != requiredRole) return false;
        if (!phaseAllows(npc.getCurrentPhase())) return false;
        if (!roleGateAllows()) return false;
        // Only re-resolve household / plot every TICK_INTERVAL ticks
        // so canUse stays cheap on the goal-poll loop.
        if (npc.tickCount % TICK_INTERVAL != 0) return false;
        return resolveContext();
    }

    @Override
    public boolean canContinueToUse() {
        if (npc == null || npc.getFamilyRole() != requiredRole) return false;
        if (!phaseAllows(npc.getCurrentPhase())) return false;
        // Consulted here as well as in canUse so a SPOUSE who becomes
        // profession-eligible mid-phase releases the MOVE flag at once
        // (rather than holding the Goal — and the nav guard — until the
        // phase ends).
        if (!roleGateAllows()) return false;
        return household != null;
    }

    /**
     * Extra per-tick gate beyond role + phase + context. Base returns
     * {@code true} (no extra gate). {@link Spouse} overrides to yield
     * the homestead Goal — and its {@code Goal.Flag.MOVE} claim — to an
     * active profession during WORK, since profession work runs
     * Brain-side and is denied navigation by {@code BrainNavGuard}
     * whenever this Goal holds MOVE.
     */
    protected boolean roleGateAllows() { return true; }

    @Override
    public void start() {
        tickInGoal = 0;
        if (resolveContext()) {
            activeHandler = HomesteadHandlerRegistry.getOrFallback(
                    household.getHomesteadPlotType());
            HomesteadHandler.Context ctx = ctx();
            if (ctx != null) activeHandler.onArrive(ctx);
        }
    }

    @Override
    public void tick() {
        tickInGoal++;
        if (activeHandler == null) return;
        HomesteadHandler.Context ctx = ctx();
        if (ctx == null) return;
        try {
            if (activeHandler.tick(ctx)) {
                // Cycle finished — let canContinueToUse re-evaluate.
                stop();
            }
        } catch (Exception e) {
            LOGGER.warn("AbstractHomesteadGoal: handler {} threw {}",
                    activeHandler.getClass().getSimpleName(), e.getMessage());
        }
    }

    @Override
    public void stop() {
        if (activeHandler != null) {
            HomesteadHandler.Context ctx = ctx();
            if (ctx != null) activeHandler.onStop(ctx);
        }
        activeHandler = null;
        household = null;
        plot = null;
        house = null;
        tickInGoal = 0;
    }

    /** Resolves household + plot + parent house from saved data.
     *  Returns false (canUse fails) if the NPC has no household or
     *  the household's house is missing. */
    private boolean resolveContext() {
        if (!(npc.level() instanceof ServerLevel sl)) return false;
        VillageSavedData data = VillageSavedData.get(sl);
        HouseholdData hh = data.getHouseholdForNpc(npc.getUUID()).orElse(null);
        if (hh == null) return false;
        Building b = data.getBuildingById(hh.getBuildingId()).orElse(null);
        if (b == null) return false;
        AdjunctPlot rolled = null;
        if (hh.hasHomestead()) {
            for (AdjunctPlot p : data.getAdjunctPlotsForBuilding(b.getId())) {
                if (p.type() == hh.getHomesteadPlotType()) { rolled = p; break; }
            }
        }
        this.household = hh;
        this.plot      = rolled;
        this.house     = b;
        return true;
    }

    private HomesteadHandler.Context ctx() {
        if (!(npc.level() instanceof ServerLevel sl) || household == null) return null;
        return new HomesteadHandler.Context(npc, sl, VillageSavedData.get(sl),
                house, household, plot, tickInGoal);
    }

    /** Convenience helper used by handlers — convert the plot's
     *  origin into a navigation target. Falls back to the parent
     *  house's origin when no plot is set (GENERIC_CHORES path). */
    public static BlockPos navTarget(HomesteadHandler.Context ctx) {
        if (ctx.plot() != null) return ctx.plot().origin();
        if (ctx.parentHouse() != null) return ctx.parentHouse().getShape().getOrigin();
        return ctx.npc().blockPosition();
    }

    // ── Concrete subclasses ────────────────────────────────────────────

    /** SPOUSE-role homestead worker — runs during any WORK_* phase
     *  EXCEPT when the spouse has an active profession with an
     *  assigned workplace (Phase 6.8.3.2). A SPOUSE who is also a
     *  FARMER / BAKER / etc. does their profession work during WORK;
     *  the Spouse Goal would otherwise claim {@code Goal.Flag.MOVE}
     *  and block the profession Brain behavior via BrainNavGuard.
     *  A SPOUSE without a profession (housekeeper / homemaker) keeps
     *  doing homestead chores during WORK as before. */
    public static final class Spouse extends AbstractHomesteadGoal {
        public Spouse(TownspersonMob npc) { super(npc, FamilyRole.SPOUSE); }
        @Override protected boolean phaseAllows(DayPhase phase) {
            return phase != null && phase.isWork();
        }
        @Override protected boolean roleGateAllows() {
            // Yield to an active profession during WORK: profession is
            // the commercial-scale primary, homestead is the
            // family-scale backup. A SPOUSE with no profession
            // (Profession.NONE) or a profession but no assigned
            // building keeps running homestead chores as before.
            boolean hasActiveProfession =
                    npc.getProfession() != Profession.NONE
                    && npc.getAssignedBuildingId().isPresent();
            return !hasActiveProfession;
        }
    }

    /** CHILD-role homestead helper — runs during SOCIAL phase. */
    public static final class Child extends AbstractHomesteadGoal {
        public Child(TownspersonMob npc) { super(npc, FamilyRole.CHILD); }
        @Override protected boolean phaseAllows(DayPhase phase) {
            return phase == DayPhase.SOCIAL;
        }
    }

    /** ELDERLY-role homestead helper — Phase 6.3.3.j.2.F. Runs during
     *  SOCIAL phase, but yields to {@code MentorBehavior} by skipping
     *  whenever the elder has an active mentor target. Mentorship is
     *  the higher-priority elderly activity; light homestead presence
     *  fills the SOCIAL slot when no apprentice is being mentored. */
    public static final class Elderly extends AbstractHomesteadGoal {
        public Elderly(TownspersonMob npc) { super(npc, FamilyRole.ELDERLY); }
        @Override protected boolean phaseAllows(DayPhase phase) {
            if (phase != DayPhase.SOCIAL) return false;
            return npc.getRetirementState().mentorTargetId().isEmpty();
        }
    }
}
