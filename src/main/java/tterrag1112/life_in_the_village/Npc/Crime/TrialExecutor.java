package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry;
import tterrag1112.life_in_the_village.Npc.Skills.Skill;
import tterrag1112.life_in_the_village.Npc.Traits.TraitAxis;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs a trial. Spec lines 209-218.
 *
 * <p>v1 trials run as immediate "ad-hoc activities" on the
 * server-thread daily tick (spec "Things to flag" #4 explicitly notes
 * Phase 5 events expanded will formalize trials as scheduled events).
 * Each due trial:</p>
 * <ol>
 *   <li>Selects a presider (bailiff for MINOR/MODERATE; leader for
 *   SERIOUS/CAPITAL).</li>
 *   <li>Builds testimonies from the existing TrialEvidence list.
 *   Credibility per spec line 215.</li>
 *   <li>Sums weighted evidence, applies presider Honesty + Compassion
 *   bias, compares against {@link CrimeSeverity#convictionThreshold}.</li>
 *   <li>Records GUILTY / NOT_GUILTY + Punishment via
 *   {@link PunishmentSelector} + {@link PunishmentExecutor}.</li>
 * </ol>
 */
public final class TrialExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TrialExecutor() {}

    public static void runDue(ServerLevel level) {
        if (level == null) return;
        CrimeSavedData crimes = CrimeSavedData.get(level);
        long now = level.getGameTime();
        for (Trial trial : crimes.dueTrials(now)) {
            try { runOne(trial, crimes, level); }
            catch (Throwable t) {
                LOGGER.warn("[TrialExecutor] Trial {} threw: {}", trial.trialId(), t.getMessage());
            }
        }
        // COLD report transition: any FILED report older than 30 days
        // gets marked COLD per spec line 336.
        for (CrimeReport r : crimes.allReports()) {
            if (r.status() == ReportStatus.FILED
                    && now - r.reportedTick() > 30L * 24000L) {
                crimes.putReport(r.withStatus(ReportStatus.COLD));
            }
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private static void runOne(Trial trial, CrimeSavedData crimes, ServerLevel level) {
        CrimeReport report = crimes.getReport(trial.reportId()).orElse(null);
        if (report == null) return;

        VillageSavedData vdata = VillageSavedData.get(level);
        Village village = vdata.getVillageById(report.villageId()).orElse(null);

        // Pick presider. If the office is vacant, the trial misfires →
        // MISTRIAL recorded; spec edge case "No eligible presider"
        // routes here.
        UUID presiderId = pickPresider(village, report);
        if (presiderId == null) {
            crimes.putTrial(trial.withVerdict(TrialVerdict.MISTRIAL,
                    level.getGameTime(), null));
            crimes.putReport(report.withStatus(ReportStatus.TRIAL_COMPLETE));
            return;
        }
        TownspersonMob presider = TownspersonMob.findByUUID(level, presiderId).orElse(null);

        // Build testimonies from existing evidence list (witness
        // entries) + presider applies Compassion bias to the
        // conviction sum.
        List<TrialTestimony> testimonies = new ArrayList<>();
        float totalWeight = 0f;
        for (TrialEvidence ev : trial.evidence()) {
            totalWeight += ev.weight();
            ev.providedBy().ifPresent(witnessId -> {
                TownspersonMob w = TownspersonMob.findByUUID(level, witnessId).orElse(null);
                if (w == null) return;
                float credibility = computeCredibility(w, report.perpetratorId().orElse(null));
                testimonies.add(new TrialTestimony(witnessId,
                        w.getNpcName() + " testifies",
                        credibility, true));
            });
        }

        // Judge skill modifier: SOCIAL skill / 50 (range ~0..2). Honest
        // judges are slightly less likely to convict on weak evidence;
        // Compassionate judges nudge toward acquittal on borderline
        // cases. Spec line 217.
        float judgeMod = 1f;
        if (presider != null) {
            int social = presider.getSkills().getLevel(Skill.SOCIAL);
            judgeMod += (social - 50) / 100f;
            float compassion = presider.getTraitVector().get(TraitAxis.COMPASSION);
            // Compassion nudges DOWN the effective evidence by up to 0.5
            // (range −0.5..+0.5 * Compassion = -0.5..0).
            float compassionNudge = -0.5f * Math.max(0f, compassion);
            totalWeight += compassionNudge;
        }
        float effectiveEvidence = totalWeight * judgeMod;
        float threshold = report.type().severity().convictionThreshold();

        TrialVerdict verdict = effectiveEvidence >= threshold
                ? TrialVerdict.GUILTY : TrialVerdict.NOT_GUILTY;
        Punishment punishment = null;
        if (verdict == TrialVerdict.GUILTY) {
            punishment = PunishmentSelector.select(report, crimes, village, level);
        }

        Trial completed = trial.withPresider(presiderId)
                .withEvidence(trial.evidence(), testimonies)
                .withVerdict(verdict, level.getGameTime(), punishment);
        crimes.putTrial(completed);
        crimes.putReport(report.withStatus(ReportStatus.TRIAL_COMPLETE));

        announceVerdict(report, completed, level);
        if (verdict == TrialVerdict.GUILTY && punishment != null) {
            PunishmentExecutor.execute(punishment, report, level);
        }
        // Phase 4 doc 30 archival hook — every trial gets one entry,
        // SERIOUS+ verdicts bumped to LEGENDARY when the punishment
        // is execution / exile.
        if (village != null) {
            archiveTrial(level, village, report, completed, punishment);
        }
    }

    private static void archiveTrial(net.minecraft.server.level.ServerLevel level,
                                     Village village, CrimeReport report,
                                     Trial trial, Punishment punishment) {
        java.util.Map<String, String> details = new java.util.LinkedHashMap<>();
        details.put("village_name", village.getName());
        details.put("crime_type", report.type().name());
        details.put("verdict", trial.verdict() != null ? trial.verdict().name() : "PENDING");
        String accusedName = report.perpetratorId()
                .flatMap(id -> tterrag1112.life_in_the_village.Entities.custom.TownspersonMob
                        .findByUUID(level, id)
                        .map(tterrag1112.life_in_the_village.Entities.custom.TownspersonMob::getNpcName))
                .orElse("(unknown)");
        details.put("accused_name", accusedName);
        java.util.List<UUID> related = report.perpetratorId()
                .map(java.util.List::of).orElse(java.util.List.of());
        // SERIOUS+ severity → MAJOR (default) or LEGENDARY for capital
        // punishment.
        tterrag1112.life_in_the_village.Village.History.HistoryImportance importance =
                tterrag1112.life_in_the_village.Village.History.HistoryEventType.TRIAL_HELD
                        .defaultImportance();
        if (punishment != null
                && (punishment.type() == PunishmentType.EXECUTION
                    || punishment.type() == PunishmentType.EXILE)) {
            importance = tterrag1112.life_in_the_village.Village.History.HistoryImportance.LEGENDARY;
        }
        tterrag1112.life_in_the_village.Village.History.HistoryProducer.record(
                level, village,
                tterrag1112.life_in_the_village.Village.History.HistoryEventType.TRIAL_HELD,
                level.getGameTime(), details, related, importance);
    }

    private static UUID pickPresider(Village village, CrimeReport report) {
        if (village == null) return null;
        var state = village.getOffices();
        String officeId = switch (report.type().severity()) {
            case MINOR, MODERATE -> OfficeRegistry.VILLAGE_BAILIFF;
            case SERIOUS, CAPITAL -> OfficeRegistry.VILLAGE_LEADER;
        };
        return state.get(officeId)
                .filter(h -> !h.isVacant())
                .flatMap(h -> h.holderUuid())
                .orElseGet(() -> state.get(OfficeRegistry.VILLAGE_LEADER)
                        .filter(h -> !h.isVacant())
                        .flatMap(h -> h.holderUuid())
                        .orElse(null));
    }

    /** Spec line 215 — credibility formula. */
    private static float computeCredibility(TownspersonMob witness, UUID accusedId) {
        float honesty = witness.getTraitVector().get(TraitAxis.HONESTY);
        int rel = accusedId == null
                ? 0
                : witness.getNpcRelationships().getScore(accusedId);
        float c = 0.5f + honesty * 0.3f - Math.abs(rel) * 0.003f;
        return Math.max(0f, Math.min(1f, c));
    }

    private static void announceVerdict(CrimeReport report, Trial trial, ServerLevel level) {
        UUID accused = report.perpetratorId().orElse(null);
        if (accused == null) return;
        var server = level.getServer();
        var player = server.getPlayerList().getPlayer(accused);
        if (player != null) {
            player.displayClientMessage(Component.literal(
                    "[Court] Verdict on " + report.type().name() + ": "
                            + trial.verdict().name()
                            + (trial.punishment().isPresent()
                                    ? " — " + trial.punishment().get().details()
                                    : "")), false);
        }
    }

    /** Suppresses unused-import on Optional for follow-up overloads. */
    @SuppressWarnings("unused")
    private static final Optional<UUID> DOC_HOOK = Optional.empty();
}
