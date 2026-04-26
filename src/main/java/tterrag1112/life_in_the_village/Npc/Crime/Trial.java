package tterrag1112.life_in_the_village.Npc.Crime;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trial record. Spec line 67. Created on TRIAL_SCHEDULED status,
 * stamped with verdict + punishment on completion.
 *
 * @param trialId             primary key
 * @param reportId            backreference to the {@link CrimeReport}
 * @param presidingOfficerId  bailiff (MINOR/MODERATE) or leader
 *                            (SERIOUS/CAPITAL); v1 trial executor reads
 *                            from {@link
 *                            tterrag1112.life_in_the_village.Npc.Office.OfficeRegistry}
 * @param jurors              empty in v1; record kept for forward
 *                            compatibility with Phase 4 jury trials
 * @param evidence            spec-weighted evidence list
 * @param testimonies         witness testimonies recorded at trial
 * @param scheduledTick       when the trial fires
 * @param completedTick       0 until the trial completes
 * @param verdict             {@link TrialVerdict#PENDING} until ruled
 * @param punishment          set iff verdict is GUILTY
 */
public record Trial(
        UUID trialId,
        UUID reportId,
        Optional<UUID> presidingOfficerId,
        List<UUID> jurors,
        List<TrialEvidence> evidence,
        List<TrialTestimony> testimonies,
        long scheduledTick,
        long completedTick,
        TrialVerdict verdict,
        Optional<Punishment> punishment
) {
    public Trial {
        if (trialId == null) trialId = UUID.randomUUID();
        if (reportId == null) throw new IllegalArgumentException("reportId required");
        if (presidingOfficerId == null) presidingOfficerId = Optional.empty();
        jurors      = jurors      == null ? List.of() : List.copyOf(jurors);
        evidence    = evidence    == null ? List.of() : List.copyOf(evidence);
        testimonies = testimonies == null ? List.of() : List.copyOf(testimonies);
        if (verdict == null) verdict = TrialVerdict.PENDING;
        if (punishment == null) punishment = Optional.empty();
    }

    public Trial withVerdict(TrialVerdict next, long completedTick, Punishment punishmentOrNull) {
        return new Trial(trialId, reportId, presidingOfficerId, jurors,
                evidence, testimonies, scheduledTick, completedTick,
                next, Optional.ofNullable(punishmentOrNull));
    }

    public Trial withEvidence(List<TrialEvidence> ev, List<TrialTestimony> tt) {
        return new Trial(trialId, reportId, presidingOfficerId, jurors,
                ev, tt, scheduledTick, completedTick, verdict, punishment);
    }

    public Trial withPresider(UUID id) {
        return new Trial(trialId, reportId, Optional.ofNullable(id), jurors,
                evidence, testimonies, scheduledTick, completedTick,
                verdict, punishment);
    }

    public static final Codec<Trial> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("trialId").forGetter(Trial::trialId),
            UUIDUtil.CODEC.fieldOf("reportId").forGetter(Trial::reportId),
            UUIDUtil.CODEC.optionalFieldOf("presider").forGetter(Trial::presidingOfficerId),
            UUIDUtil.CODEC.listOf().optionalFieldOf("jurors", List.of())
                    .forGetter(Trial::jurors),
            TrialEvidence.CODEC.listOf().optionalFieldOf("evidence", List.of())
                    .forGetter(Trial::evidence),
            TrialTestimony.CODEC.listOf().optionalFieldOf("testimonies", List.of())
                    .forGetter(Trial::testimonies),
            Codec.LONG.fieldOf("scheduledTick").forGetter(Trial::scheduledTick),
            Codec.LONG.optionalFieldOf("completedTick", 0L).forGetter(Trial::completedTick),
            TrialVerdict.CODEC.optionalFieldOf("verdict", TrialVerdict.PENDING)
                    .forGetter(Trial::verdict),
            Punishment.CODEC.optionalFieldOf("punishment").forGetter(Trial::punishment)
    ).apply(i, Trial::new));
}
