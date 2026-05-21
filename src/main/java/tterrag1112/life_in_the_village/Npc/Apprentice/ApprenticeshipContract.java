package tterrag1112.life_in_the_village.Npc.Apprentice;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.UUID;

/**
 * Formal master ↔ apprentice contract. Spec line 39.
 *
 * <p>Phase 2 implementation rolls the masterpiece-phase fields
 * (target item id, deadline, attempts) directly into this record
 * rather than introducing a {@code COMMISSION_MASTERPIECE} order
 * type on the existing CraftingOrder. The spec's "specialized
 * CraftingOrder" framing is a description of the user-visible
 * concept; mechanically the masterpiece is just the contract's
 * own milestone-4 phase. See 16 Revision Notes.</p>
 */
public record ApprenticeshipContract(
        UUID contractId,
        UUID masterId,
        boolean masterIsPlayer,
        UUID apprenticeId,
        boolean apprenticeIsPlayer,
        UUID buildingId,
        Profession profession,
        long startTick,
        long expectedDurationTicks,
        ContractStatus status,
        int progressMilestones,
        int masterpieceAttempts,
        long masterpieceDeadlineTick,
        String masterpieceTarget,
        long lastProgressTick,
        String contractTerms
) {
    /** 2 in-game years, the spec's "typical" duration. */
    public static final long DEFAULT_DURATION_TICKS = 730L * 24000L;
    /** 30 in-game days for the masterpiece deadline. */
    public static final long MASTERPIECE_DEADLINE_TICKS = 30L * 24000L;
    /** Spec line 173: max retries before hard-fail. */
    public static final int MAX_MASTERPIECE_ATTEMPTS = 2;
    /** Master max apprentices (spec line 114). */
    public static final int MAX_APPRENTICES_PER_MASTER = 2;
    /** Skill threshold to qualify as a master (spec line 96).
     *  Phase 6.3.2.b — sourced from {@link tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds}. */
    public static final int MASTER_SKILL_THRESHOLD =
            tterrag1112.life_in_the_village.Npc.Skills.SkillThresholds.MASTER_SKILL_THRESHOLD;
    /** Pass threshold for the masterpiece (spec line 169). */
    public static final int MASTERPIECE_PASS_SKILL = 75;

    public ApprenticeshipContract {
        if (contractId == null) contractId = UUID.randomUUID();
        if (masterId == null) throw new IllegalArgumentException("masterId required");
        if (apprenticeId == null) throw new IllegalArgumentException("apprenticeId required");
        if (profession == null) profession = Profession.NONE;
        if (status == null) status = ContractStatus.ACTIVE;
        if (expectedDurationTicks <= 0) expectedDurationTicks = DEFAULT_DURATION_TICKS;
        if (contractTerms == null) contractTerms = "";
        if (masterpieceTarget == null) masterpieceTarget = "";
    }

    /** Convenience factory for newly-created contracts. */
    public static ApprenticeshipContract create(UUID masterId, boolean masterIsPlayer,
                                                UUID apprenticeId, boolean apprenticeIsPlayer,
                                                UUID buildingId, Profession profession,
                                                long startTick, String terms) {
        return new ApprenticeshipContract(
                UUID.randomUUID(),
                masterId, masterIsPlayer,
                apprenticeId, apprenticeIsPlayer,
                buildingId, profession,
                startTick, DEFAULT_DURATION_TICKS,
                ContractStatus.ACTIVE,
                0, 0, 0L, "",
                startTick,
                terms == null ? "Standard. 2 years." : terms);
    }

    public boolean isActive() { return status == ContractStatus.ACTIVE; }
    public boolean masterpieceUnlocked() { return progressMilestones >= 4; }
    public boolean masterpieceAssigned() {
        return masterpieceUnlocked() && !masterpieceTarget.isEmpty();
    }

    public ApprenticeshipContract withStatus(ContractStatus newStatus) {
        return new ApprenticeshipContract(contractId, masterId, masterIsPlayer,
                apprenticeId, apprenticeIsPlayer, buildingId, profession,
                startTick, expectedDurationTicks, newStatus,
                progressMilestones, masterpieceAttempts, masterpieceDeadlineTick,
                masterpieceTarget, lastProgressTick, contractTerms);
    }

    public ApprenticeshipContract withMilestone(int newMilestone, long currentTick) {
        return new ApprenticeshipContract(contractId, masterId, masterIsPlayer,
                apprenticeId, apprenticeIsPlayer, buildingId, profession,
                startTick, expectedDurationTicks, status,
                Math.max(progressMilestones, Math.max(0, Math.min(4, newMilestone))),
                masterpieceAttempts, masterpieceDeadlineTick,
                masterpieceTarget, currentTick, contractTerms);
    }

    public ApprenticeshipContract withMasterpieceAssigned(String target, long deadline) {
        return new ApprenticeshipContract(contractId, masterId, masterIsPlayer,
                apprenticeId, apprenticeIsPlayer, buildingId, profession,
                startTick, expectedDurationTicks, status,
                Math.max(progressMilestones, 4),
                masterpieceAttempts, deadline,
                target == null ? "" : target,
                lastProgressTick, contractTerms);
    }

    public ApprenticeshipContract withMasterpieceAttempt() {
        return new ApprenticeshipContract(contractId, masterId, masterIsPlayer,
                apprenticeId, apprenticeIsPlayer, buildingId, profession,
                startTick, expectedDurationTicks, status,
                progressMilestones,
                masterpieceAttempts + 1, masterpieceDeadlineTick,
                masterpieceTarget, lastProgressTick, contractTerms);
    }

    private static final Codec<Profession> PROFESSION_CODEC =
            Codec.STRING.xmap(Profession::valueOf, Profession::name);

    public static final Codec<ApprenticeshipContract> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("contractId").forGetter(ApprenticeshipContract::contractId),
            UUIDUtil.CODEC.fieldOf("masterId").forGetter(ApprenticeshipContract::masterId),
            Codec.BOOL.optionalFieldOf("masterIsPlayer", false)
                    .forGetter(ApprenticeshipContract::masterIsPlayer),
            UUIDUtil.CODEC.fieldOf("apprenticeId").forGetter(ApprenticeshipContract::apprenticeId),
            Codec.BOOL.optionalFieldOf("apprenticeIsPlayer", false)
                    .forGetter(ApprenticeshipContract::apprenticeIsPlayer),
            UUIDUtil.CODEC.fieldOf("buildingId").forGetter(ApprenticeshipContract::buildingId),
            PROFESSION_CODEC.fieldOf("profession").forGetter(ApprenticeshipContract::profession),
            Codec.LONG.fieldOf("startTick").forGetter(ApprenticeshipContract::startTick),
            Codec.LONG.optionalFieldOf("expectedDurationTicks", DEFAULT_DURATION_TICKS)
                    .forGetter(ApprenticeshipContract::expectedDurationTicks),
            ContractStatus.CODEC.optionalFieldOf("status", ContractStatus.ACTIVE)
                    .forGetter(ApprenticeshipContract::status),
            Codec.INT.optionalFieldOf("progressMilestones", 0)
                    .forGetter(ApprenticeshipContract::progressMilestones),
            Codec.INT.optionalFieldOf("masterpieceAttempts", 0)
                    .forGetter(ApprenticeshipContract::masterpieceAttempts),
            Codec.LONG.optionalFieldOf("masterpieceDeadlineTick", 0L)
                    .forGetter(ApprenticeshipContract::masterpieceDeadlineTick),
            Codec.STRING.optionalFieldOf("masterpieceTarget", "")
                    .forGetter(ApprenticeshipContract::masterpieceTarget),
            Codec.LONG.optionalFieldOf("lastProgressTick", 0L)
                    .forGetter(ApprenticeshipContract::lastProgressTick),
            Codec.STRING.optionalFieldOf("contractTerms", "")
                    .forGetter(ApprenticeshipContract::contractTerms)
    ).apply(i, ApprenticeshipContract::new));
}
