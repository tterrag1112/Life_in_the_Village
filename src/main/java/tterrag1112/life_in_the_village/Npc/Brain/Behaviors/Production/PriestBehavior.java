package tterrag1112.life_in_the_village.Npc.Brain.Behaviors.Production;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import tterrag1112.life_in_the_village.Entities.ActivityState;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Brain.BrainNavGuard;
import tterrag1112.life_in_the_village.Npc.Brain.Memories.NpcMemoryTypes;
import tterrag1112.life_in_the_village.Npc.Brain.NpcBehaviorHelpers;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Religion.FaithReconciliation;
import tterrag1112.life_in_the_village.Npc.Religion.ReligionContent;
import tterrag1112.life_in_the_village.Npc.Religion.Rite;
import tterrag1112.life_in_the_village.Npc.Religion.RiteCapability;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecution;
import tterrag1112.life_in_the_village.Npc.Religion.RiteExecutor;
import tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeRank;
import tterrag1112.life_in_the_village.Npc.Religion.RiteOutcome;
import tterrag1112.life_in_the_village.Npc.Religion.RiteProfile;
import tterrag1112.life_in_the_village.Npc.Religion.RiteSavedData;
import tterrag1112.life_in_the_village.Npc.Religion.RiteTier;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Skills.SkillXp;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Profession.ProfessionSupplyChain;
import tterrag1112.life_in_the_village.Village.Building;
import tterrag1112.life_in_the_village.Village.BuildingStorageAccess;
import tterrag1112.life_in_the_village.Village.Event.CeremonyBlessings;
import tterrag1112.life_in_the_village.Village.Event.CommunityGatherings;
import tterrag1112.life_in_the_village.Village.Event.EventCategory;
import tterrag1112.life_in_the_village.Village.Event.VillageEvent;
import tterrag1112.life_in_the_village.Village.Gathering.CommunityGathering;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Foundational PRIEST work behavior — embodies the abstract Religion
 * subsystem ({@code Npc/Religion/}). Phase machine (mirrors {@code
 * HealerBehavior}):
 * <ol>
 *   <li><b>Officiate</b> — claim a due rite in this priest's village
 *       (presider this priest or vacant), walk to {@code rite.location()},
 *       perform it, then {@link RiteExecutor#runImmediate} (reuses the
 *       per-rite effect logic; marks it done + awards XP).</li>
 *   <li><b>Produce</b> — when no rite, make temple goods from the supply
 *       chain at the building.</li>
 *   <li><b>Bless</b> — a cooldowned, bounded mood bump to nearby NPCs (the
 *       doc-promised "minor wellbeing bonus to nearby NPCs").</li>
 * </ol>
 *
 * <h3>No double-application</h3>
 * {@code RiteExecutor.runDue} now defers any due rite whose presiding priest
 * is a <i>realized</i> (loaded) PRIEST entity (within a grace window) — this
 * behavior performs it physically and applies it via {@code runImmediate}.
 * {@code runDue} keeps applying abstractly only when no realized priest
 * exists (off-screen) or the grace lapses. The behavior <b>claims</b> a rite
 * by persisting {@code withPresider(me)} so {@code runDue} defers and other
 * priests skip it.
 *
 * <h3>Specialization seam (stub)</h3>
 * Branches on building type (TEMPLE / CHAPEL / SHRINE) — identical for now;
 * the future religion rework differentiates them here.
 */
public class PriestBehavior extends Behavior<TownspersonMob> {

    private static final int    OFFICIATE_TICKS = 200;
    private static final int    PRODUCE_TICKS   = 200;
    private static final double ARRIVAL_SQ      = 6.0;
    private static final int    IDLE_COOLDOWN   = 200;
    private static final int    BLESS_INTERVAL  = 600;   // ~30s; NOT per-tick
    private static final double BLESS_RADIUS    = 12.0;
    private static final int    BLESS_MAGNITUDE = 4;
    private static final int    XP_PER_PRODUCE  = 1;
    private static final float  WALK_SPEED      = 1.0f;

    // R3d-1 — festival fronting: the priest leads village-wide RELIGIOUS_RITE
    // gatherings for their active window with a sustained crowd-blessing.
    private static final int    FESTIVAL_PULSE_INTERVAL = 600;   // ~30s; NOT per-tick
    private static final int    FESTIVAL_MAX_PULSES     = 6;     // anti-farm cap
    private static final int    FESTIVAL_BLESS_MOOD     = 6;     // > ambient aura (4)
    private static final float  FESTIVAL_BLESS_PIETY    = 0.01f; // ≤ 6 × 0.01 over a festival

    private enum Phase { IDLE, WALKING_TO_RITE, OFFICIATING, PRODUCING, FRONTING }
    /** Specialization seam — the religion rework branches per building type. */
    private enum TempleKind { TEMPLE, CHAPEL, SHRINE, OTHER }

    private TownspersonMob entity;
    private Phase phase = Phase.IDLE;
    private Building building;
    private RiteExecution claimedRite;
    private int timer;
    private int idleCooldown;
    private long lastBlessTick = Long.MIN_VALUE;
    /** R1b order seam — last-seen specialization id, for change-only debug. */
    private net.minecraft.resources.Identifier lastOrderId;
    // R3d-1 — fronted-festival state, held in fields (no new brain memory).
    private UUID frontedGatheringId;
    private long frontEndTick;
    private String frontReligionId;
    private Rite frontRiteType;
    private int frontPulseCount;
    private long lastFrontPulseTick = Long.MIN_VALUE;

    public PriestBehavior() {
        super(ImmutableMap.of(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED), 24000);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, TownspersonMob entity) {
        this.entity = entity;
        if (!BrainNavGuard.canSteerNavigation(entity)) return false;
        if (entity.getProfession() != Profession.PRIEST) return false;
        if (!entity.isWorkTime()) return false;
        // Structural-idle cooldown (mirrors Farmer/Miner) — bounds the
        // re-analyze rate when idle so the rite-ledger / storage reads aren't
        // per-tick. The NO_ACTIONABLE_WORK signal set in goIdle persists
        // meanwhile so the idle director fills.
        if (idleCooldown > 0) { idleCooldown--; return false; }
        building = entity.getAssignedBuildingId()
                .flatMap(id -> VillageSavedData.get(level).getBuildingById(id)).orElse(null);
        return building != null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        return phase != Phase.IDLE;
    }

    @Override
    protected void start(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        phase = Phase.IDLE;
        analyze(level);
    }

    @Override
    protected void tick(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        // R3d-1 — the festival crowd-blessing pulse supersedes the ambient aura
        // while fronting (no odd stacking).
        if (phase != Phase.FRONTING) {
            blessNearby(level, gameTime); // cooldowned aura while the priest is active
        }
        switch (phase) {
            case WALKING_TO_RITE -> tickWalkToRite(level);
            case OFFICIATING     -> tickOfficiating(level);
            case PRODUCING       -> tickProducing(level);
            case FRONTING        -> tickFronting(level, gameTime);
            case IDLE            -> {}
        }
    }

    @Override
    protected void stop(ServerLevel level, TownspersonMob entity, long gameTime) {
        this.entity = entity;
        // L1-fix lifecycle: do NOT wipe NO_ACTIONABLE_WORK or the blocking
        // reason here — only the transient reset.
        phase = Phase.IDLE;
        claimedRite = null;
        clearFronting();
        timer = 0;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    private void analyze(ServerLevel level) {
        // Specialization seam (R1b): resolve the priest's order so the
        // content/multi-religion phase can branch behaviour per
        // religion-specific order without re-plumbing. Generalist
        // (priest/cleric) today — a no-op beyond the change-only debug
        // trace. Runs behind idleCooldown, never per-tick.
        readOrderSeam();

        // R3d-1 — front an active village-wide religious festival in this
        // priest's village (preempts routine one-shot officiation + produce for
        // the festival window). One fronter: claiming the gathering's blessing
        // rite (presider=me) makes other priests skip it. Non-festival rites
        // (weddings/funerals) are still claimed by OTHER priests; in a
        // single-priest village they defer until the (short) festival ends.
        if (tryStartFronting(level)) return;

        RiteExecution rite = findClaimableRite(level);
        if (rite != null) {
            RiteSavedData rdata = RiteSavedData.get(level);
            // Claim: persist presider=me so runDue defers (realized priest) and
            // other priests skip this rite.
            claimedRite = rite.withPresider(entity.getUUID());
            rdata.putRite(claimedRite);
            phase = Phase.WALKING_TO_RITE;
            entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get()); // real work
            entity.setCurrentActivity(clergyTitle() + ": Officiating " + riteLabel(rite.type()));
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(claimedRite.location(), WALK_SPEED, 1));
            return;
        }
        if (canProduce(level)) {
            phase = Phase.PRODUCING;
            entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());
            entity.setCurrentActivity(clergyTitle() + ": Preparing temple goods");
            BlockPos origin = building.getShape().getOrigin();
            if (origin != null) {
                entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                        new WalkTarget(origin, WALK_SPEED, 1));
            }
            timer = 0;
            return;
        }
        goIdle("no rites or temple work");
    }

    /** Idle with the work-satisfied signal so the director fills (L1-fix
     *  lifecycle: SET here, never erased in stop()). */
    private void goIdle(String reason) {
        entity.setActivityState(ActivityState.IDLE.withBlocking(reason));
        entity.getBrain().setMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get(), Boolean.TRUE);
        phase = Phase.IDLE;
        idleCooldown = IDLE_COOLDOWN;
        claimedRite = null;
        clearFronting();
        timer = 0;
        entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    // ── Rite officiation ─────────────────────────────────────────────────

    private RiteExecution findClaimableRite(ServerLevel level) {
        Village v = entity.getAssignedVillageName()
                .flatMap(n -> VillageSavedData.get(level).getVillageByName(n)).orElse(null);
        if (v == null) return null;
        UUID me = entity.getUUID();
        TempleKind kind = kindOf(building);
        // R3c — the priest's clergy ORDER (its spec id) nudges preference toward
        // its focus rites, composed UNDER the R1b building band: building rank is
        // primary, order focus is a secondary tiebreak WITHIN a band (orderRank
        // 0 = a focus rite, 1 = not). A Threadkeeper in a temple still does GRAND
        // first, but prefers CONFESSION among same-band rites. Generalist → all
        // orderRank 1, so no change.
        net.minecraft.resources.Identifier orderId =
                entity.getSpecializationComponent().currentId().orElse(null);
        // Building-kind rite preference (R1b): among all due, claimable,
        // capability-permitted rites in this priest's village, prefer the
        // tier this building specializes in. This is preference ordering,
        // NOT a second gate — R1a canOfficiate remains the only hard limit,
        // so a lone qualified priest still performs everything (the best by
        // preference is simply chosen first). Ties keep the earliest-due
        // rite (stable: a strictly-better rank must beat the incumbent),
        // so OTHER buildings reproduce the prior first-due behaviour.
        RiteExecution best = null;
        int bestRank = Integer.MAX_VALUE;
        int bestOrderRank = Integer.MAX_VALUE;
        for (RiteExecution r : RiteSavedData.get(level).dueRites(level.getGameTime())) {
            if (!v.getId().equals(r.villageId())) continue;
            UUID presider = r.presidingPriestId().orElse(null);
            if (presider != null && !presider.equals(me)) continue; // claimed by another
            // Capability gate (R1a): only claim a rite this priest is
            // qualified to officiate. An over-tier rite (e.g. a GRAND
            // ceremony for an unseated low-skill priest) is left unclaimed
            // for a qualified officiant. Same helper RiteExecutor consults.
            if (!RiteCapability.canOfficiate(entity, r.type())) continue;
            int rank = tierPreferenceRank(kind, RiteTier.tierOf(r.type()));
            int orderRank = tterrag1112.life_in_the_village.Npc.Religion.ClergyOrders
                    .isFocusRite(orderId, r.type()) ? 0 : 1;
            if (rank < bestRank || (rank == bestRank && orderRank < bestOrderRank)) {
                bestRank = rank;
                bestOrderRank = orderRank;
                best = r;
            }
        }
        return best;
    }

    /** Building-kind rite-claim preference: lower rank = claimed first.
     *  Layers on the R1a capability gate; never relaxes or tightens it. */
    private static int tierPreferenceRank(TempleKind kind, RiteTier tier) {
        return switch (kind) {
            // Seat of grand ceremony: GRAND first, then STANDARD, then MINOR.
            case TEMPLE -> switch (tier) {
                case GRAND -> 0; case STANDARD -> 1; case MINOR -> 2;
            };
            // Day-to-day village religious life: STANDARD life-events and
            // MINOR first; GRAND deprioritized (still allowed if pending).
            case CHAPEL -> switch (tier) {
                case STANDARD -> 0; case MINOR -> 1; case GRAND -> 2;
            };
            // Light devotional post: the fallback for routine MINOR rites.
            case SHRINE -> switch (tier) {
                case MINOR -> 0; case STANDARD -> 1; case GRAND -> 2;
            };
            // No preference — earliest-due wins (prior behaviour).
            case OTHER -> 0;
        };
    }

    /** Specialization-order seam (R1b). Resolves the priest's spec id and
     *  debug-logs only on change. Generalist (priest/cleric) is a no-op
     *  today; the content phase branches behaviour here by order. */
    private void readOrderSeam() {
        net.minecraft.resources.Identifier id =
                entity.getSpecializationComponent().currentId().orElse(null);
        if (id != null && !id.equals(lastOrderId)) {
            lastOrderId = id;
            org.slf4j.LoggerFactory.getLogger(PriestBehavior.class)
                    .debug("[PriestBehavior] {} officiates as order {} ({})",
                            entity.getNpcName(), id, clergyTitle());
        }
    }

    /** R1d — cosmetic clergy rank, derived from SOCIAL via the shared
     *  {@link ApprenticeRank} ladder (no persistent rank field). Surfaced in
     *  the priest's activity text so progression is player-visible. */
    private String clergyTitle() {
        int social = entity.getSkills().getLevel(Skill.SOCIAL);
        return switch (ApprenticeRank.fromSkillLevel(social)) {
            case APPRENTICE -> "Initiate";
            case JOURNEYMAN -> "Priest";
            case MASTER     -> "Senior Priest";
        };
    }

    private void tickWalkToRite(ServerLevel level) {
        if (claimedRite == null) { phase = Phase.IDLE; return; }
        BlockPos loc = claimedRite.location();
        double d2 = entity.distanceToSqr(loc.getX() + 0.5, loc.getY(), loc.getZ() + 0.5);
        if (d2 <= ARRIVAL_SQ) {
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            phase = Phase.OFFICIATING;
            timer = 0;
        } else {
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(loc, WALK_SPEED, 1));
        }
    }

    private void tickOfficiating(ServerLevel level) {
        if (claimedRite == null) { phase = Phase.IDLE; return; }
        timer++;
        BlockPos loc = claimedRite.location();
        entity.getLookControl().setLookAt(loc.getX() + 0.5, loc.getY() + 1.0, loc.getZ() + 0.5);
        if (timer % 20 == 0) entity.swing(InteractionHand.MAIN_HAND);
        if (timer < OFFICIATE_TICKS) return;

        // Complete. Re-fetch by id and confirm still PENDING (guard against a
        // race with another loaded priest or the abstract grace fallback), then
        // apply via runImmediate — reuses the per-rite effect logic, marks the
        // rite done, awards the priest +5 SOCIAL XP. No re-implementation.
        RiteExecution current = RiteSavedData.get(level)
                .getRite(claimedRite.riteId()).orElse(null);
        if (current != null && current.outcome() == RiteOutcome.PENDING) {
            RiteExecutor.runImmediate(current, level);
        }
        // R3d-1 — if this was a festival's opening blessing, stay and front the
        // gathering for its window; otherwise it's a one-shot rite → idle. The
        // re-fetch-PENDING guard above means an already-applied linked rite is
        // NOT double-applied — the priest still fronts.
        claimedRite = null;
        if (frontedGatheringId != null) {
            phase = Phase.FRONTING;
            timer = 0;
            lastFrontPulseTick = level.getGameTime(); // don't pulse instantly on arrival
        } else {
            phase = Phase.IDLE;
        }
    }

    // ── Production ───────────────────────────────────────────────────────

    private boolean canProduce(ServerLevel level) {
        for (Item in : ProfessionSupplyChain.getInputs(Profession.PRIEST)) {
            if (BuildingStorageAccess.countItem(level, building, in) > 0) return true;
        }
        return false;
    }

    private void tickProducing(ServerLevel level) {
        timer++;
        if (timer < PRODUCE_TICKS) return;
        timer = 0;
        // Consume one available input; produce one temple output (rotated by
        // day so the stock diversifies). Reuses building storage + deposit
        // helpers per the litv-npc-behavior contract.
        Item consumed = null;
        for (Item in : ProfessionSupplyChain.getInputs(Profession.PRIEST)) {
            if (BuildingStorageAccess.takeItem(level, building, in, 1)) { consumed = in; break; }
        }
        if (consumed == null) { phase = Phase.IDLE; return; } // inputs ran out
        List<Item> outputs = ProfessionSupplyChain.getOutputs(Profession.PRIEST);
        if (!outputs.isEmpty()) {
            Item out = outputs.get((int) ((level.getGameTime() / 24000L) % outputs.size()));
            NpcBehaviorHelpers.depositToBuilding(level, entity, building, new ItemStack(out, 1));
        }
        SkillXp.award(entity, Skill.SOCIAL, XP_PER_PRODUCE, level.getGameTime());
        entity.swing(InteractionHand.MAIN_HAND);
    }

    // ── Blessing aura ────────────────────────────────────────────────────

    /** Cooldowned, bounded mood bump to nearby NPCs. Runs while the priest is
     *  actively working (officiating/producing/approaching); a fully-idle
     *  priest does not bless (foundation limitation — flagged for a future
     *  move to a CORE/always-on hook). */
    private void blessNearby(ServerLevel level, long gameTime) {
        if (gameTime - lastBlessTick < BLESS_INTERVAL) return;
        lastBlessTick = gameTime;
        AABB box = new AABB(entity.blockPosition()).inflate(BLESS_RADIUS);
        for (TownspersonMob other : level.getEntitiesOfClass(TownspersonMob.class, box,
                npc -> npc != entity && npc.isAlive())) {
            other.getMood().applyWithRawMagnitude(MoodTrigger.GIFT_RECEIVED, BLESS_MAGNITUDE, gameTime);
        }
    }

    // ── Festival fronting (R3d-1) ────────────────────────────────────────

    /**
     * Looks for an active village-wide RELIGIOUS_RITE gathering this priest
     * should FRONT (holy-days + signature rites + vigils/purifications — the
     * frontable predicate is the RELIGIOUS_RITE category, which excludes
     * LIFE_STAGE life-events and SEASONAL secular festivals). Fronter election
     * reuses the gathering's blessing-rite presider: claim it (presider=me) and
     * other priests skip; respect the R1a capability gate. On success, walks to
     * the venue (the linked rite's location — the R2b convergence point) and
     * arms the fronting flow. Returns true when fronting started.
     */
    private boolean tryStartFronting(ServerLevel level) {
        Village v = entity.getAssignedVillageName()
                .flatMap(n -> VillageSavedData.get(level).getVillageByName(n)).orElse(null);
        if (v == null) return false;
        long now = level.getGameTime();
        UUID me = entity.getUUID();
        for (CommunityGathering g : CommunityGatherings.activeInVillage(level, v.getId(), now)) {
            if (!(g instanceof VillageEvent ve)) continue;          // festivals are events
            if (ve.getType().category() != EventCategory.RELIGIOUS_RITE) continue; // predicate
            RiteExecution linked = linkedRite(level, ve);
            if (linked == null) continue;                           // no blessing to lead
            UUID presider = linked.presidingPriestId().orElse(null);
            if (presider != null && !presider.equals(me)) continue; // another priest fronts
            if (!RiteCapability.canOfficiate(entity, linked.type())) continue; // R1a gate

            // Become the fronter: claim the blessing rite, walk to the venue.
            claimedRite = linked.withPresider(me);
            RiteSavedData.get(level).putRite(claimedRite);
            frontedGatheringId = ve.getId();
            frontEndTick = ve.endTick();
            frontReligionId = ReligionContent.villageReligionId(level, v);
            frontRiteType = linked.type();
            frontPulseCount = 0;
            lastFrontPulseTick = Long.MIN_VALUE;
            phase = Phase.WALKING_TO_RITE;
            entity.getBrain().eraseMemory(NpcMemoryTypes.NO_ACTIONABLE_WORK.get());
            entity.setCurrentActivity(clergyTitle() + ": Leading "
                    + ve.getType().name().replace('_', ' ').toLowerCase(Locale.ROOT));
            entity.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                    new WalkTarget(claimedRite.location(), WALK_SPEED, 1));
            return true;
        }
        return false;
    }

    /** Lead the festival for its window: periodic sermon gesture + a bounded,
     *  per-faith crowd-blessing pulse to attendees at the venue. */
    private void tickFronting(ServerLevel level, long gameTime) {
        timer++;
        if (timer % 20 == 0) entity.swing(InteractionHand.MAIN_HAND); // sermon gesture
        if (gameTime - lastFrontPulseTick >= FESTIVAL_PULSE_INTERVAL
                && frontPulseCount < FESTIVAL_MAX_PULSES) {
            lastFrontPulseTick = gameTime;
            frontPulseCount++;
            festivalCrowdBless(level, gameTime);
        }
        // End when the gathering's window closes or it is no longer active.
        if (gameTime >= frontEndTick || !gatheringStillActive(level, gameTime)) {
            clearFronting();
            phase = Phase.IDLE;
            entity.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    /** The crowd-blessing payoff: a stronger-than-ambient mood boost + a small,
     *  bounded piety bump to attendees gathered at the venue (the priest stands
     *  there while fronting), tuned per-faith by the festival rite's
     *  {@link ReligionContent} profile. The one-shot blessing rite already fired
     *  at gathering start — these are the SUSTAINED layer, not a re-application;
     *  the per-festival pulse cap prevents piety farming. */
    private void festivalCrowdBless(ServerLevel level, long gameTime) {
        RiteProfile profile = ReligionContent.profileFor(frontReligionId, frontRiteType);
        AABB box = new AABB(entity.blockPosition()).inflate(BLESS_RADIUS);
        for (TownspersonMob other : level.getEntitiesOfClass(TownspersonMob.class, box,
                npc -> npc != entity && npc.isAlive())) {
            // R3e-1 — route through the single cross-faith reconciliation site.
            // Fixes the prior bug where the crowd pulse deepened the ATTENDEE's
            // own faith regardless of the festival's faith: a minority now gets
            // a reduced mood + a syncretic drift toward the officiating faith.
            FaithReconciliation.applyCommunalBenefit(other, frontReligionId,
                    MoodTrigger.FESTIVAL_ATTENDED, profile.scaleMood(FESTIVAL_BLESS_MOOD),
                    profile.scalePiety(FESTIVAL_BLESS_PIETY), gameTime);
            other.getPiety().recordRiteAttendance(gameTime);
        }
    }

    private boolean gatheringStillActive(ServerLevel level, long gameTime) {
        if (frontedGatheringId == null) return false;
        return VillageSavedData.get(level).getEventById(frontedGatheringId)
                .map(e -> e.isActiveAt(gameTime)).orElse(false);
    }

    private static RiteExecution linkedRite(ServerLevel level, VillageEvent ve) {
        String riteIdStr = ve.getEventData().get(CeremonyBlessings.RITE_ID_KEY);
        if (riteIdStr == null) return null;
        try {
            return RiteSavedData.get(level).getRite(UUID.fromString(riteIdStr)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void clearFronting() {
        frontedGatheringId = null;
        frontEndTick = 0L;
        frontReligionId = null;
        frontRiteType = null;
        frontPulseCount = 0;
        timer = 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static TempleKind kindOf(Building b) {
        return switch (b.getType()) {
            case TEMPLE -> TempleKind.TEMPLE;
            case CHAPEL -> TempleKind.CHAPEL;
            case SHRINE -> TempleKind.SHRINE;
            default     -> TempleKind.OTHER;
        };
    }

    private static String riteLabel(Rite rite) {
        return switch (rite) {
            case COMING_OF_AGE        -> "a coming-of-age";
            case MARRIAGE             -> "a marriage";
            case NAMING               -> "a naming";
            case FUNERAL              -> "a funeral";
            case BLESSING             -> "a blessing";
            case CONFESSION           -> "a confession";
            case OFFERING             -> "an offering";
            case TITHE                -> "the tithe";
            case HARVEST_THANKSGIVING -> "harvest thanksgiving";
            case FEAST_DAY            -> "a feast day";
            case ORDINATION           -> "an ordination";
            case CONSECRATION         -> "a consecration";
            case VIGIL                -> "a vigil";
            case PURIFICATION         -> "a purification";
            case SIGNATURE_RITE       -> "a signature rite";
            case GRAND_FESTIVAL       -> "a grand festival";
        };
    }
}
