package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One reported crime. Spec line 42.
 *
 * <p>Witness UUIDs are stored as a list (not set) because the order is
 * the in-village ranking that the constable uses to decide whom to
 * interview first; the constable's investigation goal walks the list
 * in-order.</p>
 *
 * @param reportId         primary key in {@link CrimeSavedData}
 * @param type             which crime
 * @param perpetratorId    null when unknown — the constable may
 *                         identify one during investigation
 * @param victimId         null for victimless crimes (TAX_EVASION,
 *                         TRESPASSING into civic spaces)
 * @param witnessIds       UUIDs of NPCs (or players) who witnessed
 * @param reportedById     UUID of who reported (NPC, player, or the
 *                         crime-detection auto-filer; the latter uses
 *                         the village leader's UUID as a stand-in)
 * @param location         block coords of the crime scene
 * @param occurredTick     tick at which the crime happened
 * @param reportedTick     tick at which the report was filed
 * @param evidenceNotes    free-form annotations added during
 *                         detection / investigation
 * @param status           current {@link ReportStatus}
 * @param investigatorId   constable who picked it up (when
 *                         INVESTIGATING)
 * @param trialId          set once a trial is scheduled
 */
public record CrimeReport(
        UUID reportId,
        CrimeType type,
        Optional<UUID> perpetratorId,
        Optional<UUID> victimId,
        List<UUID> witnessIds,
        UUID reportedById,
        BlockPos location,
        long occurredTick,
        long reportedTick,
        List<String> evidenceNotes,
        ReportStatus status,
        Optional<UUID> investigatorId,
        Optional<UUID> trialId,
        UUID villageId
) {
    public CrimeReport {
        if (reportId == null) reportId = UUID.randomUUID();
        if (type == null) throw new IllegalArgumentException("type required");
        if (perpetratorId == null) perpetratorId = Optional.empty();
        if (victimId == null)      victimId      = Optional.empty();
        witnessIds    = witnessIds    == null ? List.of() : List.copyOf(witnessIds);
        evidenceNotes = evidenceNotes == null ? List.of() : List.copyOf(evidenceNotes);
        if (reportedById == null) throw new IllegalArgumentException("reportedById required");
        if (location == null) location = BlockPos.ZERO;
        if (status == null) status = ReportStatus.FILED;
        if (investigatorId == null) investigatorId = Optional.empty();
        if (trialId == null)        trialId        = Optional.empty();
        if (villageId == null) throw new IllegalArgumentException("villageId required");
    }

    public CrimeReport withStatus(ReportStatus next) {
        return new CrimeReport(reportId, type, perpetratorId, victimId, witnessIds,
                reportedById, location, occurredTick, reportedTick, evidenceNotes,
                next, investigatorId, trialId, villageId);
    }

    public CrimeReport withInvestigator(UUID id) {
        return new CrimeReport(reportId, type, perpetratorId, victimId, witnessIds,
                reportedById, location, occurredTick, reportedTick, evidenceNotes,
                ReportStatus.INVESTIGATING, Optional.ofNullable(id), trialId, villageId);
    }

    public CrimeReport withTrial(UUID id) {
        return new CrimeReport(reportId, type, perpetratorId, victimId, witnessIds,
                reportedById, location, occurredTick, reportedTick, evidenceNotes,
                ReportStatus.TRIAL_SCHEDULED, investigatorId,
                Optional.ofNullable(id), villageId);
    }

    public CrimeReport withPerpetrator(UUID id) {
        return new CrimeReport(reportId, type, Optional.ofNullable(id), victimId, witnessIds,
                reportedById, location, occurredTick, reportedTick, evidenceNotes,
                status, investigatorId, trialId, villageId);
    }

    public CrimeReport withNote(String note) {
        java.util.List<String> next = new java.util.ArrayList<>(evidenceNotes);
        next.add(note);
        return new CrimeReport(reportId, type, perpetratorId, victimId, witnessIds,
                reportedById, location, occurredTick, reportedTick, next,
                status, investigatorId, trialId, villageId);
    }

    public static final Codec<CrimeReport> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("reportId").forGetter(CrimeReport::reportId),
            CrimeType.CODEC.fieldOf("type").forGetter(CrimeReport::type),
            UUIDUtil.CODEC.optionalFieldOf("perpetratorId").forGetter(CrimeReport::perpetratorId),
            UUIDUtil.CODEC.optionalFieldOf("victimId").forGetter(CrimeReport::victimId),
            UUIDUtil.CODEC.listOf().optionalFieldOf("witnessIds", List.of())
                    .forGetter(CrimeReport::witnessIds),
            UUIDUtil.CODEC.fieldOf("reportedById").forGetter(CrimeReport::reportedById),
            BlockPos.CODEC.optionalFieldOf("location", BlockPos.ZERO).forGetter(CrimeReport::location),
            Codec.LONG.fieldOf("occurredTick").forGetter(CrimeReport::occurredTick),
            Codec.LONG.fieldOf("reportedTick").forGetter(CrimeReport::reportedTick),
            Codec.STRING.listOf().optionalFieldOf("evidenceNotes", List.of())
                    .forGetter(CrimeReport::evidenceNotes),
            ReportStatus.CODEC.optionalFieldOf("status", ReportStatus.FILED)
                    .forGetter(CrimeReport::status),
            UUIDUtil.CODEC.optionalFieldOf("investigatorId").forGetter(CrimeReport::investigatorId),
            UUIDUtil.CODEC.optionalFieldOf("trialId").forGetter(CrimeReport::trialId),
            UUIDUtil.CODEC.fieldOf("villageId").forGetter(CrimeReport::villageId)
    ).apply(i, CrimeReport::new));
}
