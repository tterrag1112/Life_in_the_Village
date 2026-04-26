package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-world persistent crime ledger. Spec line 117.
 *
 * <p>Indexes built on load + maintained on add for cheap lookup:</p>
 * <ul>
 *   <li>{@link #reportsByVillage} — drives the constable's report
 *   queue.</li>
 *   <li>{@link #reportsAgainst} — repeat-offender check during
 *   sentencing.</li>
 *   <li>{@link #convictionsAgainst} — count of trials with
 *   {@link TrialVerdict#GUILTY} per accused; also used by repeat-
 *   offender escalation in {@link PunishmentSelector}.</li>
 * </ul>
 */
public class CrimeSavedData extends SavedData {

    public static final SavedDataType<CrimeSavedData> TYPE = new SavedDataType<>(
            "life_in_the_village_crime",
            CrimeSavedData::new,
            RecordCodecBuilder.create(i -> i.group(
                    CrimeReport.CODEC.listOf().optionalFieldOf("reports", List.of())
                            .forGetter(d -> new ArrayList<>(d.reports.values())),
                    Trial.CODEC.listOf().optionalFieldOf("trials", List.of())
                            .forGetter(d -> new ArrayList<>(d.trials.values()))
            ).apply(i, CrimeSavedData::fromCodec))
    );

    private final Map<UUID, CrimeReport> reports        = new HashMap<>();
    private final Map<UUID, Trial>       trials         = new HashMap<>();
    private final Map<UUID, List<UUID>>  reportsByVillage  = new HashMap<>();
    private final Map<UUID, List<UUID>>  reportsAgainst    = new HashMap<>();
    private final Map<UUID, Integer>     convictionsAgainst = new HashMap<>();

    public CrimeSavedData() {}

    private static CrimeSavedData fromCodec(List<CrimeReport> reports, List<Trial> trials) {
        CrimeSavedData d = new CrimeSavedData();
        for (CrimeReport r : reports) d.indexAdd(r);
        for (Trial t : trials) {
            d.trials.put(t.trialId(), t);
            if (t.verdict() == TrialVerdict.GUILTY) {
                CrimeReport report = d.reports.get(t.reportId());
                if (report != null) {
                    report.perpetratorId().ifPresent(p ->
                            d.convictionsAgainst.merge(p, 1, Integer::sum));
                }
            }
        }
        return d;
    }

    public static CrimeSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ── Reports ────────────────────────────────────────────────────────────

    public void addReport(CrimeReport report) {
        if (report == null) return;
        indexAdd(report);
        setDirty();
    }

    /** Replaces an existing report (e.g. status transition). */
    public void putReport(CrimeReport report) {
        if (report == null) return;
        CrimeReport prev = reports.put(report.reportId(), report);
        if (prev == null) {
            // First time we've seen it — also add to village + accused
            // indexes.
            byVillageList(report.villageId()).add(report.reportId());
            report.perpetratorId().ifPresent(p ->
                    againstList(p).add(report.reportId()));
        }
        setDirty();
    }

    public Optional<CrimeReport> getReport(UUID id) {
        return Optional.ofNullable(reports.get(id));
    }

    public List<CrimeReport> reportsForVillage(UUID villageId) {
        if (villageId == null) return List.of();
        List<UUID> ids = reportsByVillage.getOrDefault(villageId, List.of());
        List<CrimeReport> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            CrimeReport r = reports.get(id);
            if (r != null) out.add(r);
        }
        return out;
    }

    public List<CrimeReport> reportsByStatus(UUID villageId, ReportStatus status) {
        return reportsForVillage(villageId).stream()
                .filter(r -> r.status() == status).toList();
    }

    public List<CrimeReport> allReports() { return List.copyOf(reports.values()); }

    public int priorConvictions(UUID accusedId) {
        if (accusedId == null) return 0;
        return convictionsAgainst.getOrDefault(accusedId, 0);
    }

    // ── Trials ─────────────────────────────────────────────────────────────

    public void putTrial(Trial trial) {
        if (trial == null) return;
        Trial prev = trials.put(trial.trialId(), trial);
        if (prev != null && prev.verdict() != TrialVerdict.GUILTY
                && trial.verdict() == TrialVerdict.GUILTY) {
            CrimeReport report = reports.get(trial.reportId());
            if (report != null) {
                report.perpetratorId().ifPresent(p ->
                        convictionsAgainst.merge(p, 1, Integer::sum));
            }
        }
        setDirty();
    }

    public Optional<Trial> getTrial(UUID id) {
        return Optional.ofNullable(trials.get(id));
    }

    public List<Trial> allTrials() { return List.copyOf(trials.values()); }

    /** Trials whose scheduledTick has passed and are still PENDING. */
    public List<Trial> dueTrials(long currentTick) {
        return trials.values().stream()
                .filter(t -> t.verdict() == TrialVerdict.PENDING)
                .filter(t -> t.scheduledTick() <= currentTick)
                .toList();
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private void indexAdd(CrimeReport report) {
        reports.put(report.reportId(), report);
        byVillageList(report.villageId()).add(report.reportId());
        report.perpetratorId().ifPresent(p -> againstList(p).add(report.reportId()));
    }

    private List<UUID> byVillageList(UUID villageId) {
        return reportsByVillage.computeIfAbsent(villageId, k -> new ArrayList<>());
    }

    private List<UUID> againstList(UUID accused) {
        return reportsAgainst.computeIfAbsent(accused, k -> new ArrayList<>());
    }

    /** Public dirty-flag for external mutators. */
    public void markDirty() { setDirty(); }
}
