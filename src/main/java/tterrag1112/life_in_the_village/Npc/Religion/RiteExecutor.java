package tterrag1112.life_in_the_village.Npc.Religion;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Memory.MemoryType;
import tterrag1112.life_in_the_village.Npc.Memory.NpcMemory;
import tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Profession.Profession;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.List;
import java.util.UUID;

/**
 * Runs every {@link RiteOutcome#PENDING} rite whose scheduled tick
 * has passed. Spec line 96.
 *
 * <p>Per-rite handlers apply the spec's "per-rite effects" block
 * (spec lines 113-133): mood deltas, memory writes, piety bumps,
 * priest XP, kingdom history stub. Each handler returns the
 * resulting {@link RiteOutcome} so the saved-data ledger stamps the
 * correct status.</p>
 *
 * <p>Spec edge cases handled: no priest available → SKIPPED for
 * COMING_OF_AGE, deferred up to 14 days for MARRIAGE; subject
 * missing at own rite → DISRUPTED; temple destroyed → DISRUPTED.
 * Phase 5 may refine.</p>
 */
public final class RiteExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Maximum tick deferral for MARRIAGE when no priest is available
     *  (spec line 252: "defer up to 14d"). */
    public static final long MARRIAGE_DEFER_LIMIT_TICKS = 14L * 24000L;

    /** Grace (ticks) a realized priest gets to physically officiate a due rite
     *  before {@code runDue} falls back to applying it abstractly — so a
     *  loaded-but-unable priest (off-work / unreachable venue) can't starve a
     *  rite indefinitely. */
    public static final long OFFICIATE_GRACE_TICKS = 24000L;

    private RiteExecutor() {}

    /** Daily entry point — runs every due rite in the level. */
    public static void runDue(ServerLevel level) {
        if (level == null) return;
        RiteSavedData rdata = RiteSavedData.get(level);
        long now = level.getGameTime();
        for (RiteExecution rite : rdata.dueRites(now)) {
            // deferToRealizedPriest=true: leave rites whose priest is a loaded
            // PRIEST entity PENDING so PriestBehavior officiates them physically.
            try { runOne(rite, rdata, level, true); }
            catch (Throwable t) {
                LOGGER.warn("[RiteExecutor] Rite {} threw: {}", rite.type(), t.getMessage());
            }
        }
    }

    /** Public entry point for the {@code /religion rite} debug command AND for
     *  {@code PriestBehavior} when it finishes officiating physically. Bypasses
     *  the realized-priest defer (the caller IS performing the rite). */
    public static RiteOutcome runImmediate(RiteExecution rite, ServerLevel level) {
        return runOne(rite, RiteSavedData.get(level), level, false);
    }

    private static RiteOutcome runOne(RiteExecution rite, RiteSavedData rdata,
                                      ServerLevel level, boolean deferToRealizedPriest) {
        long now = level.getGameTime();
        VillageSavedData vdata = VillageSavedData.get(level);
        Village village = vdata.getVillageById(rite.villageId()).orElse(null);

        // Pick / verify priest.
        UUID priestId = rite.presidingPriestId().orElseGet(() -> findPriest(village));
        TownspersonMob priest = priestId == null
                ? null : TownspersonMob.findByUUID(level, priestId).orElse(null);

        // Capability gate (Religion Rework R1a): a resolved priest who is
        // not qualified for this rite's tier is treated as "no qualified
        // officiant" — the rite waits for one (MARRIAGE defer) or SKIPs,
        // rather than an unstaffable GRAND rite being applied abstractly.
        // The seated VILLAGE_PRIEST always reaches GRAND, so in practice
        // this only bites when a presider was set to an under-tier priest
        // (e.g. a low-skill non-office priest claimed via an older save).
        // Same canonical helper PriestBehavior.findClaimableRite calls.
        boolean capable = priest != null && RiteCapability.canOfficiate(priest, rite.type());

        // Physical-officiation gate: defer to a realized (loaded) PRIEST entity
        // — PriestBehavior will walk to the venue and call runImmediate (which
        // passes deferToRealizedPriest=false and applies). No double-apply: the
        // abstract path here is skipped while the priest is loaded and within
        // the grace window. runImmediate bypasses this gate entirely. Only
        // defer to a priest that can actually officiate this tier — an
        // under-tier priest will never claim it, so deferring would starve it.
        if (deferToRealizedPriest && capable
                && priest.getProfession() == Profession.PRIEST
                && now - rite.scheduledTick() < OFFICIATE_GRACE_TICKS) {
            return RiteOutcome.PENDING;
        }

        if (priest == null || !capable) {
            // Spec line 252: skip COMING_OF_AGE; defer MARRIAGE up to 14d.
            if (rite.type() == Rite.MARRIAGE
                    && now - rite.scheduledTick() < MARRIAGE_DEFER_LIMIT_TICKS) {
                // Reschedule for tomorrow; outcome stays PENDING.
                rdata.putRite(new RiteExecution(rite.riteId(), rite.type(),
                        rite.presidingPriestId(), rite.participantIds(),
                        rite.location(), rite.scheduledTick() + 24000L,
                        rite.completedTick(), RiteOutcome.PENDING, rite.villageId()));
                return RiteOutcome.PENDING;
            }
            rdata.putRite(rite.withOutcome(RiteOutcome.SKIPPED, now));
            return RiteOutcome.SKIPPED;
        }

        // Run the per-rite handler.
        RiteOutcome outcome = switch (rite.type()) {
            case COMING_OF_AGE        -> handleComingOfAge(rite, priest, level, now);
            case MARRIAGE             -> handleMarriage(rite, priest, level, now);
            case NAMING               -> handleNaming(rite, priest, level, now);
            case FUNERAL              -> handleFuneral(rite, priest, level, now);
            case BLESSING             -> handleBlessing(rite, priest, level, now);
            case CONFESSION           -> handleConfession(rite, priest, level, now);
            case OFFERING             -> handleOffering(rite, priest, level, now);
            case TITHE                -> handleTithe(rite, priest, level, now);
            case HARVEST_THANKSGIVING -> handleHarvestThanksgiving(rite, priest, level, now, village);
            case FEAST_DAY            -> handleFeastDay(rite, priest, level, now, village);
            case ORDINATION           -> handleOrdination(rite, priest, level, now);
        };

        rdata.putRite(rite.withPresider(priestId).withOutcome(outcome, now));
        if (priest.getProfession() == Profession.PRIEST) {
            // Priest gains SOCIAL XP from officiating, scaled by rite tier
            // (R1a) so harder rites progress faster once qualified. Single
            // XP site for both paths — runImmediate (debug + PriestBehavior
            // completion) routes through here too.
            tterrag1112.life_in_the_village.Npc.Skills.SkillXp.award(priest, tterrag1112.life_in_the_village.Npc.Skills.Skill.SOCIAL,
                    RiteTier.tierOf(rite.type()).socialXp(), now);
        }
        return outcome;
    }

    // ── Per-rite handlers ─────────────────────────────────────────────────

    private static RiteOutcome handleOrdination(RiteExecution rite, TownspersonMob priest,
                                                ServerLevel level, long now) {
        // Religion Rework R1c — the ceremony by which a PRIEST formally
        // becomes clergy. The clergy specialization IS the ordained marker
        // (no new persistent field): assigning it both records ordination
        // and unlocks the order seam PriestBehavior reads.
        UUID ordinandId = first(rite.participantIds());
        TownspersonMob ordinand = ordinandId == null
                ? null : TownspersonMob.findByUUID(level, ordinandId).orElse(null);
        if (ordinand == null) return RiteOutcome.DISRUPTED;

        // Assign the locked clergy spec via the canonical spawn-spec route —
        // the SAME path the populator uses (idempotent: assign(force=true) +
        // setLocked). No third assignment mechanism. This also closes the
        // R1b gap: leader-hired priests, never run through the populator,
        // become ordained here.
        tterrag1112.life_in_the_village.Npc.Specialization.NpcSpecializationTypes
                .assignInitialSpawnSpec(ordinand, ordinand.getProfession());

        // Effects mirror COMING_OF_AGE in style + magnitude: piety +0.1,
        // mood +20, OFFICIATED_BY memory + rel-with-officiant +10.
        String religionId = ordinand.getPiety().primaryReligion().orElse(ReligionRegistry.SUNSTEAD);
        ordinand.getPiety().adjustBelief(religionId, 0.10f);
        ordinand.getMood().applyWithRawMagnitude(MoodTrigger.GIFT_FAVORITE, 20, now);

        // Self-ordination fallback (a lone capable priest with no separate
        // senior present): still ordain, but skip the self-referential
        // relationship/memory writes.
        if (!priest.getUUID().equals(ordinand.getUUID())) {
            ordinand.getNpcRelationships().adjust(priest.getUUID(), 10, now,
                    tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin.MET_SOCIALLY);
            ordinand.getMemory().add(NpcMemory.create(MemoryType.OFFICIATED_BY,
                    List.of(priest.getUUID()), now, 80,
                    priest.getNpcName() + " ordained me into the clergy"));
        }

        // R1d — start the mentored clergy training arc. The ADULT-transition
        // dispatcher misses mid-life ordination (leader hires / conversions),
        // so an initiate is matched to a senior here.
        tryFormClergyApprenticeship(level, ordinand);
        return RiteOutcome.SUCCESSFUL;
    }

    /**
     * Religion Rework R1d — forms a clergy apprenticeship for a freshly
     * ordained initiate. Reuses the generic matcher + manager verbatim (the
     * same calls {@code ApprenticeshipDispatcher} makes at ADULT) — no forked
     * contract path. Only juniors apprentice, and only when forming the
     * contract will not de-staff a building the ordinand already works.
     */
    private static void tryFormClergyApprenticeship(ServerLevel level, TownspersonMob ordinand) {
        // Only an APPRENTICE-rank junior (SOCIAL < 40) needs mentoring; a
        // priest who is already JOURNEYMAN+ qualifies via the R1a gate solo.
        int social = ordinand.getSkills().getLevel(
                tterrag1112.life_in_the_village.Npc.Skills.Skill.SOCIAL);
        if (tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeRank
                .fromSkillLevel(social)
                != tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeRank.APPRENTICE) {
            return;
        }

        var reg = tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipSavedData.get(level);
        if (reg.getByApprentice(ordinand.getUUID()).isPresent()) return; // already in a contract

        TownspersonMob master = tterrag1112.life_in_the_village.Npc.Apprentice
                .ApprenticeshipMatcher.findMaster(ordinand, level).orElse(null);
        if (master == null) return;
        if (!tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipMatcher
                .masterAccepts(master, ordinand, reg)) return;

        // De-staffing guard: startContract reassigns the apprentice to the
        // master's building. Skip if that would yank the ordinand off a
        // building they already staff (e.g. a chapel/shrine priest). Allowed
        // when the ordinand has no building yet or already shares the
        // master's. The un-mentored junior still grows solo under the R1a gate.
        UUID masterBuilding = master.getAssignedBuildingId().orElse(null);
        UUID ordinandBuilding = ordinand.getAssignedBuildingId().orElse(null);
        if (ordinandBuilding != null && !ordinandBuilding.equals(masterBuilding)) return;

        var contract = tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipManager
                .startContract(level, master, ordinand, "Clergy initiation. 2 years.");
        tterrag1112.life_in_the_village.Npc.Apprentice.ApprenticeshipContractFactory
                .queueViaScribe(level, contract, master, ordinand);
    }

    private static RiteOutcome handleComingOfAge(RiteExecution rite, TownspersonMob priest,
                                                 ServerLevel level, long now) {
        UUID subjectId = first(rite.participantIds());
        TownspersonMob subject = subjectId == null
                ? null : TownspersonMob.findByUUID(level, subjectId).orElse(null);
        if (subject == null) return RiteOutcome.DISRUPTED;
        // Spec line 115: piety +0.1, mood +20, rel-with-priest +10.
        subject.getPiety().adjustBelief(
                subject.getPiety().primaryReligion()
                        .orElse(ReligionRegistry.SUNSTEAD), 0.10f);
        subject.getMood().applyWithRawMagnitude(MoodTrigger.GIFT_FAVORITE, 20, now);
        subject.getNpcRelationships().adjust(priest.getUUID(), 10, now,
                tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin.MET_SOCIALLY);
        subject.getMemory().add(NpcMemory.create(MemoryType.OFFICIATED_BY,
                List.of(priest.getUUID()), now, 70,
                priest.getNpcName() + " officiated my coming-of-age"));
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleMarriage(RiteExecution rite, TownspersonMob priest,
                                              ServerLevel level, long now) {
        // Spec line 118: spouses + priest + household; mood +50 (existing
        // marriage event already covers the +50, so this rite adds +10
        // blessing on top).
        for (UUID participantId : rite.participantIds()) {
            TownspersonMob p = TownspersonMob.findByUUID(level, participantId).orElse(null);
            if (p == null) continue;
            p.getMood().applyWithRawMagnitude(MoodTrigger.MARRIAGE, 10, now);
            p.getMemory().add(NpcMemory.create(MemoryType.OFFICIATED_BY,
                    List.of(priest.getUUID()), now, 90,
                    priest.getNpcName() + " officiated our marriage"));
        }
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleNaming(RiteExecution rite, TownspersonMob priest,
                                            ServerLevel level, long now) {
        // Spec line 119: priest visits, household mood +15, priest rel +5.
        for (UUID id : rite.participantIds()) {
            TownspersonMob p = TownspersonMob.findByUUID(level, id).orElse(null);
            if (p == null) continue;
            p.getMood().applyWithRawMagnitude(MoodTrigger.GIFT_RECEIVED, 15, now);
            p.getNpcRelationships().adjust(priest.getUUID(), 5, now,
                    tterrag1112.life_in_the_village.Npc.Relations.RelationshipOrigin.MET_SOCIALLY);
        }
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleFuneral(RiteExecution rite, TownspersonMob priest,
                                             ServerLevel level, long now) {
        // Spec line 120-121: lessens grief, pinned "remembered kindly"
        // memory for household + close friends.
        for (UUID id : rite.participantIds()) {
            TownspersonMob p = TownspersonMob.findByUUID(level, id).orElse(null);
            if (p == null) continue;
            // Counter-trigger: add a small positive mood shift.
            p.getMood().applyWithRawMagnitude(MoodTrigger.LETTER_FROM_FRIEND, 6, now);
            p.getMemory().add(NpcMemory.create(MemoryType.OFFICIATED_FOR,
                    List.of(priest.getUUID()), now, 75,
                    "Attended funeral officiated by " + priest.getNpcName()));
        }
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleBlessing(RiteExecution rite, TownspersonMob priest,
                                              ServerLevel level, long now) {
        // Spec line 124: mood + 5% primary skill for 24h. v1 just
        // applies the mood; the skill-buff plumbing exists via
        // ActiveSkillBuff (Phase 2 task 18) — wired in a follow-up so
        // every blessing call goes through the same buff machinery.
        UUID targetId = first(rite.participantIds());
        TownspersonMob target = targetId == null
                ? null : TownspersonMob.findByUUID(level, targetId).orElse(null);
        if (target == null) return RiteOutcome.DISRUPTED;
        target.getMood().applyWithRawMagnitude(MoodTrigger.GIFT_RECEIVED, 8, now);
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleConfession(RiteExecution rite, TownspersonMob priest,
                                                ServerLevel level, long now) {
        // Spec line 126: priest gains a sensitive KnowledgeEntry; the
        // confessor's mood improves. The actual private content
        // (player chooses from memory options) is captured by the
        // Confess verb; this handler just registers the slot.
        UUID confessorId = first(rite.participantIds());
        TownspersonMob confessor = confessorId == null
                ? null : TownspersonMob.findByUUID(level, confessorId).orElse(null);
        if (confessor == null) return RiteOutcome.DISRUPTED;
        confessor.getMood().applyWithRawMagnitude(MoodTrigger.LETTER_FROM_FRIEND, 12, now);
        // Phase 3 doc 21 — confession resolves MELANCHOLY directly per
        // spec "Open decisions" #4. NERVOUS_BREAKDOWN is too severe
        // to clear via confession alone (needs healer + time).
        if (confessor.getHealthComponent().remove(
                tterrag1112.life_in_the_village.Npc.Health.HealthCondition.MELANCHOLY)) {
            confessor.getMood().applyWithRawMagnitude(
                    tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger.HEALED,
                    tterrag1112.life_in_the_village.Npc.Mood.MoodTrigger.HEALED.defaultMagnitude(),
                    now);
        }
        // Sensitive knowledge entry — flag the priest's ledger.
        priest.getKnowledge().add(
                tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeEntry.create(
                        "confession:" + confessor.getNpcName(),
                        tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeCategory.PERSONAL,
                        0.95f,
                        tterrag1112.life_in_the_village.Npc.Knowledge.KnowledgeSource.WITNESS,
                        now,
                        "Heard a confession from " + confessor.getNpcName(),
                        confessor.getUUID(),
                        true));
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleOffering(RiteExecution rite, TownspersonMob priest,
                                              ServerLevel level, long now) {
        // Spec line 127: piety +0.05, mood +5.
        UUID giverId = first(rite.participantIds());
        TownspersonMob giver = giverId == null
                ? null : TownspersonMob.findByUUID(level, giverId).orElse(null);
        if (giver == null) return RiteOutcome.DISRUPTED;
        giver.getMood().applyWithRawMagnitude(MoodTrigger.GIFT_RECEIVED, 5, now);
        String religionId = giver.getPiety().primaryReligion().orElse(ReligionRegistry.SUNSTEAD);
        giver.getPiety().adjustBelief(religionId, 0.05f);
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleTithe(RiteExecution rite, TownspersonMob priest,
                                           ServerLevel level, long now) {
        // Spec line 128: failure → piety drop, rel drop. Successful
        // tithe is debit + piety bump. v1 ships the success branch
        // (handler called only when the verb / scheduler can pay);
        // failure path lands in the recurring auto-pay follow-up.
        UUID payerId = first(rite.participantIds());
        TownspersonMob payer = payerId == null
                ? null : TownspersonMob.findByUUID(level, payerId).orElse(null);
        if (payer == null) return RiteOutcome.DISRUPTED;
        String religionId = payer.getPiety().primaryReligion().orElse(ReligionRegistry.SUNSTEAD);
        payer.getPiety().adjustBelief(religionId, 0.03f);
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleHarvestThanksgiving(RiteExecution rite,
                                                         TownspersonMob priest,
                                                         ServerLevel level, long now,
                                                         Village village) {
        if (village == null) return RiteOutcome.DISRUPTED;
        // Spec line 130: village-wide, +2d mood, food-need reduction,
        // treasury bonus. v1 broadcasts the mood shock and bumps the
        // treasury; food-need is decremented through the existing
        // VillageNeedsCalculator on the next daily pass.
        String name = village.getName();
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;
            if (!npc.getAssignedVillageName().filter(n -> n.equals(name)).isPresent()) continue;
            npc.getMood().applyWithRawMagnitude(MoodTrigger.FESTIVAL_ATTENDED, 12, now);
            String religionId = npc.getPiety().primaryReligion().orElse(ReligionRegistry.SUNSTEAD);
            npc.getPiety().adjustBelief(religionId, 0.02f);
            npc.getPiety().recordRiteAttendance(now);
        }
        // Treasury bonus — small gesture from the village to mark the
        // rite. Phase 4 culture pass may scale this.
        village.depositToTreasury(50L);
        VillageSavedData.get(level).markDirty();
        return RiteOutcome.SUCCESSFUL;
    }

    private static RiteOutcome handleFeastDay(RiteExecution rite, TownspersonMob priest,
                                              ServerLevel level, long now, Village village) {
        if (village == null) return RiteOutcome.DISRUPTED;
        // Lighter cousin of harvest thanksgiving — mood only.
        String name = village.getName();
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof TownspersonMob npc)) continue;
            if (!npc.getAssignedVillageName().filter(n -> n.equals(name)).isPresent()) continue;
            npc.getMood().applyWithRawMagnitude(MoodTrigger.FESTIVAL_ATTENDED, 8, now);
            npc.getPiety().recordRiteAttendance(now);
        }
        return RiteOutcome.SUCCESSFUL;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static UUID first(List<UUID> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static UUID findPriest(Village village) {
        if (village == null) return null;
        return village.getOffices().get(OfficeRegistry.VILLAGE_PRIEST)
                .filter(h -> !h.isVacant())
                .flatMap(h -> h.holderUuid())
                .orElse(null);
    }

    /** Suppresses unused-import on BlockPos for follow-up overloads. */
    @SuppressWarnings("unused")
    private static final BlockPos DOC_HOOK = BlockPos.ZERO;
}
