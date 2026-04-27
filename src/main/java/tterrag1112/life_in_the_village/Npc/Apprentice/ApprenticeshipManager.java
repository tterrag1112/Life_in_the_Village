package tterrag1112.life_in_the_village.Npc.Apprentice;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin;
import tterrag1112.life_in_the_village.Npc.Skills.ProfessionSkills;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the active-phase mechanics: starts contracts, runs the
 * weekly milestone + masterpiece evaluation, fires
 * {@code TAUGHT_BY} memories, and routes termination paths.
 *
 * <p>Pure static — state lives in {@link ApprenticeshipSavedData}.
 * Spec lines 121-206.</p>
 */
public final class ApprenticeshipManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Spec line 131: TAUGHT_BY memory cadence. */
    public static final long TEACHING_MEMORY_INTERVAL_TICKS = 30L * 24000L;
    /** Skill thresholds for milestones 1..4. Spec line 141. */
    public static final int[] MILESTONE_SKILL_THRESHOLDS = {20, 40, 55, 70};
    /** Spec line 119: relationship seeded when a contract starts. */
    public static final int CONTRACT_RELATIONSHIP_SEED = 30;
    /** Spec line 191: relationship bump on graduation. */
    public static final int GRADUATION_RELATIONSHIP_BUMP = 20;
    /** Spec line 191: relationship hit on dismissal. */
    public static final int DISMISSAL_RELATIONSHIP_HIT = -25;
    /** Spec line 134: master's per-session SOCIAL XP for teaching. */
    public static final int MASTER_TEACHING_XP_PER_SESSION = 2;

    private ApprenticeshipManager() {}

    // =========================================================================
    // Contract creation
    // =========================================================================

    /**
     * Creates and registers a new contract. Seeds the master ↔
     * apprentice relationship at +30 (spec line 119).
     */
    public static ApprenticeshipContract startContract(ServerLevel level,
                                                       TownspersonMob master,
                                                       TownspersonMob apprentice,
                                                       String terms) {
        long now = level.getGameTime();
        UUID buildingId = master.getAssignedBuildingId().orElse(UUID.randomUUID());
        ApprenticeshipContract contract = ApprenticeshipContract.create(
                master.getUUID(), false,
                apprentice.getUUID(), false,
                buildingId, master.getProfession(),
                now, terms);
        ApprenticeshipSavedData.get(level).add(contract);

        // Seed mutual relationship at +30.
        master.getNpcRelationships().adjust(apprentice.getUUID(),
                CONTRACT_RELATIONSHIP_SEED, now, RelationshipOrigin.WORKPLACE_COLLEAGUE);
        apprentice.getNpcRelationships().adjust(master.getUUID(),
                CONTRACT_RELATIONSHIP_SEED, now, RelationshipOrigin.WORKPLACE_COLLEAGUE);

        // Apprentice inherits master's profession + workplace assignment.
        apprentice.setProfession(master.getProfession());
        apprentice.assignToBuilding(buildingId,
                master.getAssignedVillageName().orElse(""));

        LOGGER.info("[Apprenticeship] {} → apprentice of {} ({}). contract={}",
                apprentice.getNpcName(), master.getNpcName(),
                master.getProfession().name(), contract.contractId());
        return contract;
    }

    // =========================================================================
    // Weekly tick
    // =========================================================================

    /**
     * Runs once per in-game week per spec line 148. Walks every
     * active contract and updates milestone/masterpiece state.
     */
    public static void weeklyTick(ServerLevel level) {
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        long now = level.getGameTime();
        for (ApprenticeshipContract c : reg.active()) {
            if (c.masterIsPlayer() || c.apprenticeIsPlayer()) {
                tickPlayerInvolvedContract(level, reg, c, now);
            } else {
                tickNpcContract(level, reg, c, now);
            }
        }
    }

    private static void tickNpcContract(ServerLevel level,
                                        ApprenticeshipSavedData reg,
                                        ApprenticeshipContract c,
                                        long now) {
        Optional<TownspersonMob> apprenticeOpt =
                TownspersonMob.findByUUID(level, c.apprenticeId());
        Optional<TownspersonMob> masterOpt =
                TownspersonMob.findByUUID(level, c.masterId());
        if (apprenticeOpt.isEmpty()) return;
        if (masterOpt.isEmpty()) {
            // Master died/migrated — mark BROKEN per spec line 203.
            reg.update(c.withStatus(ContractStatus.BROKEN));
            return;
        }
        TownspersonMob apprentice = apprenticeOpt.get();
        TownspersonMob master = masterOpt.get();
        Skill primary = ProfessionSkills.of(c.profession())
                .map(ProfessionSkills::primary).orElse(Skill.CRAFTING);

        // Milestone check.
        ApprenticeshipContract updated = checkMilestones(c, apprentice, primary, now);
        if (updated != c) {
            reg.update(updated);
            c = updated;
        }

        // TAUGHT_BY memory every 30 days.
        if (shouldRecordTeachingMemory(apprentice, c.masterId(), now)) {
            apprentice.getMemory().add(NpcMemory.create(
                    MemoryType.TAUGHT_BY,
                    List.of(c.masterId()),
                    now, 50,
                    "Lessons from " + master.getNpcName()));
            master.getSkills().addXp(Skill.SOCIAL,
                    MASTER_TEACHING_XP_PER_SESSION, now);
        }

        // Masterpiece phase auto-progress for NPCs.
        if (c.masterpieceUnlocked()) {
            tickMasterpiece(reg, c, apprentice, master, primary, now);
        }
    }

    private static void tickPlayerInvolvedContract(ServerLevel level,
                                                   ApprenticeshipSavedData reg,
                                                   ApprenticeshipContract c,
                                                   long now) {
        // Player-involved contracts don't auto-tick milestones via
        // skill (player has no SkillComponent). Player progression is
        // surfaced via dialogue + quest events; the manager just keeps
        // the contract alive and lets debug commands force milestones.
        if (now - c.lastProgressTick() > c.expectedDurationTicks() * 2L) {
            // Hard timeout safety — a player-led contract that's
            // wandered far past its expected duration is unlikely to
            // ever finish. Don't auto-terminate — flag it for the
            // debug surface.
            LOGGER.debug("[Apprenticeship] contract {} dormant past 2x duration",
                    c.contractId());
        }
    }

    /**
     * Walks the milestone ladder. Returns the contract unchanged
     * when no milestone advanced.
     */
    public static ApprenticeshipContract checkMilestones(ApprenticeshipContract c,
                                                         TownspersonMob apprentice,
                                                         Skill primary,
                                                         long currentTick) {
        int level = apprentice.getSkills().getLevel(primary);
        int target = c.progressMilestones();
        for (int i = c.progressMilestones(); i < MILESTONE_SKILL_THRESHOLDS.length; i++) {
            if (level >= MILESTONE_SKILL_THRESHOLDS[i]) target = i + 1;
            else break;
        }
        if (target == c.progressMilestones()) return c;
        return c.withMilestone(target, currentTick);
    }

    private static boolean shouldRecordTeachingMemory(TownspersonMob apprentice,
                                                      UUID masterId, long now) {
        // Find most recent TAUGHT_BY memory of this master.
        long lastTaught = apprentice.getMemory().all().stream()
                .filter(m -> m.type() == MemoryType.TAUGHT_BY
                        && m.participantIds().contains(masterId))
                .mapToLong(NpcMemory::tick).max().orElse(Long.MIN_VALUE);
        return (now - lastTaught) >= TEACHING_MEMORY_INTERVAL_TICKS;
    }

    // =========================================================================
    // Masterpiece phase
    // =========================================================================

    /**
     * Spec line 158. Auto-assigns a masterpiece target on first tick
     * after milestone 4 if the contract doesn't have one. Then
     * evaluates progress: when the apprentice's primary skill
     * reaches the pass threshold the contract completes; if the
     * deadline passes without progress an attempt is recorded.
     */
    private static void tickMasterpiece(ApprenticeshipSavedData reg,
                                        ApprenticeshipContract c,
                                        TownspersonMob apprentice,
                                        TownspersonMob master,
                                        Skill primary,
                                        long now) {
        if (!c.masterpieceAssigned()) {
            String target = masterpieceTargetFor(c.profession());
            long deadline = now + ApprenticeshipContract.MASTERPIECE_DEADLINE_TICKS;
            ApprenticeshipContract assigned = c.withMasterpieceAssigned(target, deadline);
            reg.update(assigned);
            LOGGER.info("[Apprenticeship] masterpiece assigned to {}: {}",
                    apprentice.getNpcName(), target);
            return;
        }

        // Pass condition: skill at MASTERPIECE_PASS_SKILL or above.
        int level = apprentice.getSkills().getLevel(primary);
        if (level >= ApprenticeshipContract.MASTERPIECE_PASS_SKILL) {
            graduate(reg, c, apprentice, master, ApprenticeRank.MASTER, now);
            return;
        }
        // Fail condition: deadline reached without pass.
        if (now >= c.masterpieceDeadlineTick()) {
            attemptOrFail(reg, c, apprentice, master, now);
        }
    }

    private static void attemptOrFail(ApprenticeshipSavedData reg,
                                      ApprenticeshipContract c,
                                      TownspersonMob apprentice,
                                      TownspersonMob master,
                                      long now) {
        ApprenticeshipContract bumped = c.withMasterpieceAttempt();
        if (bumped.masterpieceAttempts() > ApprenticeshipContract.MAX_MASTERPIECE_ATTEMPTS) {
            // Hard fail: graduate as JOURNEYMAN.
            graduate(reg, c, apprentice, master, ApprenticeRank.JOURNEYMAN, now);
            return;
        }
        // Reset deadline for the next attempt.
        ApprenticeshipContract retry = bumped.withMasterpieceAssigned(
                bumped.masterpieceTarget(),
                now + ApprenticeshipContract.MASTERPIECE_DEADLINE_TICKS);
        reg.update(retry);
    }

    /** Completes the contract; relationship + memory bookkeeping. */
    public static void graduate(ApprenticeshipSavedData reg,
                                ApprenticeshipContract c,
                                TownspersonMob apprentice,
                                TownspersonMob master,
                                ApprenticeRank rank,
                                long now) {
        reg.update(c.withStatus(ContractStatus.COMPLETED));
        if (apprentice != null && master != null) {
            apprentice.getNpcRelationships().adjust(master.getUUID(),
                    GRADUATION_RELATIONSHIP_BUMP, now, RelationshipOrigin.WORKPLACE_COLLEAGUE);
            master.getNpcRelationships().adjust(apprentice.getUUID(),
                    GRADUATION_RELATIONSHIP_BUMP, now, RelationshipOrigin.WORKPLACE_COLLEAGUE);
            // Pinned high-value TAUGHT_BY memory (spec line 192).
            apprentice.getMemory().add(NpcMemory.create(
                    MemoryType.TAUGHT_BY,
                    List.of(master.getUUID()),
                    now, 95,
                    "Graduated under " + master.getNpcName() + " (" + rank.name() + ")"));
        }
        LOGGER.info("[Apprenticeship] graduated: {} as {} (contract {})",
                apprentice == null ? "?" : apprentice.getNpcName(),
                rank.name(), c.contractId());
    }

    // =========================================================================
    // Termination
    // =========================================================================

    /** Apprentice walks away — spec line 198. */
    public static void apprenticeAbandons(ServerLevel level, ApprenticeshipContract c) {
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        reg.update(c.withStatus(ContractStatus.ABANDONED));
    }

    /** Master dismisses apprentice — spec line 200. */
    public static void masterDismisses(ServerLevel level, ApprenticeshipContract c) {
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        long now = level.getGameTime();
        reg.update(c.withStatus(ContractStatus.TERMINATED));
        TownspersonMob apprentice =
                TownspersonMob.findByUUID(level, c.apprenticeId()).orElse(null);
        if (apprentice != null && !c.masterIsPlayer()) {
            apprentice.getNpcRelationships().adjust(c.masterId(),
                    DISMISSAL_RELATIONSHIP_HIT, now, RelationshipOrigin.MET_IN_CONFLICT);
        }
    }

    /** Master died — spec line 203. */
    public static void onMasterDeath(ServerLevel level, UUID masterId) {
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(level);
        for (ApprenticeshipContract c : reg.getActiveByMaster(masterId)) {
            reg.update(c.withStatus(ContractStatus.BROKEN));
        }
    }

    // =========================================================================
    // Masterpiece-target table (spec line 386 — profession defaults)
    // =========================================================================

    public static String masterpieceTargetFor(tterrag1112.life_in_the_village.Profession.Profession p) {
        return switch (p) {
            case BLACKSMITH    -> "minecraft:diamond_sword";
            case CARPENTER     -> "minecraft:chiseled_bookshelf";
            case WEAVER        -> "minecraft:white_banner";
            case CANDLEMAKER   -> "minecraft:white_candle";
            case STONEMASON    -> "minecraft:chiseled_stone_bricks";
            case BAKER         -> "minecraft:cake";
            case MILLER        -> "life_in_the_village:wheat_flour";
            case SCHOLAR, SCRIBE, LIBRARIAN -> "minecraft:written_book";
            default            -> "minecraft:emerald";
        };
    }

    // =========================================================================
    // Mentorship-bonus presence helper
    // =========================================================================

    /**
     * Returns the active mentorship XP multiplier for the given
     * apprentice — used by the work goals when the apprentice is at
     * the workshop with the master present. Spec line 130.
     */
    public static float mentorshipMultiplierFor(TownspersonMob apprentice,
                                                 ServerLevel level) {
        if (!(level instanceof ServerLevel sl)) return 1f;
        ApprenticeshipSavedData reg = ApprenticeshipSavedData.get(sl);
        Optional<ApprenticeshipContract> opt = reg.getByApprentice(apprentice.getUUID());
        if (opt.isEmpty()) return 1f;
        ApprenticeshipContract c = opt.get();
        Optional<TownspersonMob> master = TownspersonMob.findByUUID(sl, c.masterId());
        if (master.isEmpty()) return 1f;
        // Master must be co-located at the workshop. Spec line 130
        // says "master being present at same building"; use a 16-block
        // range as the building-bounds approximation.
        if (apprentice.distanceToSqr(master.get()) > 16.0 * 16.0) return 1f;
        return MentorshipBonus.NPC_MENTORSHIP_MULTIPLIER;
    }
}
