package tterrag1112.life_in_the_village.Items;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import tterrag1112.life_in_the_village.Guilds.Companies.Business;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-component payload for a {@link JobContractItem} stack. Encodes
 * the flavours the universal hire / commission / apprenticeship / business-
 * recruit item can take:
 *
 * <ul>
 *   <li>HIRE   (rank=NONE)        — assign player as worker of a
 *       profession at the target NPC's workplace.</li>
 *   <li>APPRENTICE (master=NPC)   — bind player as apprentice to the
 *       target NPC master.</li>
 *   <li>APPRENTICE (master=player) — bind target NPC as apprentice
 *       under the player master.</li>
 *   <li>COMMISSION (targetItem set) — crafting commission with
 *       optional target-item filter; matched against the NPC's
 *       profession.</li>
 *   <li>BUSINESS_RECRUIT (recruitingBusinessId present) — hire an
 *       unemployed NPC into a specific player-owned business.
 *       Minted by {@code BusinessActionPacket.MINT_HIRE_CONTRACT};
 *       dispatched in {@code NpcInteractionHandler.handleJobContract}
 *       BEFORE the profession-match gate.</li>
 * </ul>
 *
 * <p>Filled by SCRIBE via the ScribeCounter compose flow (hire/apprentice/
 * commission); OR minted directly by the business management GUI
 * (business-recruit). Consumed on right-click against an NPC.
 * The dispatch routing lives in {@code NpcInteractionHandler}.</p>
 *
 * <p>Field count: 9 (under the 16-field RecordCodecBuilder cap).
 * {@code recruitingBusinessId} and {@code recruiterRole} are
 * {@code optionalFieldOf} so pre-feature saves load cleanly.</p>
 */
public record JobContractTerms(
        Profession profession,
        Rank rank,
        Optional<UUID> masterUuid,
        long salaryBronze,
        Optional<String> targetItemId,
        long durationTicks,
        Optional<UUID> commissionerUuid,
        // PB-hire: business-recruit flavor
        Optional<UUID> recruitingBusinessId,
        Optional<String> recruiterRole          // Business.WorkerRole name
) {

    /** Apprentice/journeyman/master encoding for the rank field. NONE = plain hire. */
    public enum Rank {
        NONE, APPRENTICE, JOURNEYMAN, MASTER;
        public static final Codec<Rank> CODEC =
                Codec.STRING.xmap(Rank::valueOf, Rank::name);
    }

    public JobContractTerms {
        if (rank == null)                rank = Rank.NONE;
        if (masterUuid == null)          masterUuid = Optional.empty();
        if (targetItemId == null)        targetItemId = Optional.empty();
        if (commissionerUuid == null)    commissionerUuid = Optional.empty();
        if (recruitingBusinessId == null) recruitingBusinessId = Optional.empty();
        if (recruiterRole == null)       recruiterRole = Optional.empty();
        if (salaryBronze < 0)            salaryBronze = 0;
        if (durationTicks < 0)           durationTicks = 0;
    }

    public boolean isApprenticeship()  { return rank == Rank.APPRENTICE; }
    public boolean isCommission()      { return targetItemId.isPresent(); }
    public boolean isHire()            { return rank == Rank.NONE && targetItemId.isEmpty()
                                                && recruitingBusinessId.isEmpty(); }
    /** True when this contract is a business-recruit slip minted by the
     *  business management GUI.  Checked FIRST in NpcInteractionHandler
     *  so the profession-match gate is bypassed. */
    public boolean isBusinessRecruit() { return recruitingBusinessId.isPresent(); }

    // ── Factories ────────────────────────────────────────────────────────────

    public static JobContractTerms hire(Profession profession, long salary) {
        return new JobContractTerms(profession, Rank.NONE,
                Optional.empty(), salary, Optional.empty(), 0L,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static JobContractTerms apprenticeship(Profession profession,
                                                  UUID master,
                                                  long durationTicks) {
        return new JobContractTerms(profession, Rank.APPRENTICE,
                Optional.ofNullable(master), 0L, Optional.empty(),
                durationTicks, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static JobContractTerms commission(Profession profession,
                                              String targetItemId,
                                              long bounty,
                                              UUID commissioner) {
        return new JobContractTerms(profession, Rank.NONE,
                Optional.empty(), bounty,
                Optional.of(targetItemId), 0L,
                Optional.ofNullable(commissioner), Optional.empty(), Optional.empty());
    }

    /**
     * Minted by the business management GUI — recruits an unemployed NPC
     * into the specified business with the given role.
     *
     * @param businessId  UUID of the owning business.
     * @param role        initial role for the new worker.
     */
    public static JobContractTerms businessRecruit(UUID businessId,
                                                   Business.WorkerRole role) {
        return new JobContractTerms(
                Profession.NONE,      // profession irrelevant — bypasses profession gate
                Rank.NONE,
                Optional.empty(),
                0L,
                Optional.empty(),
                0L,
                Optional.empty(),
                Optional.of(businessId),
                Optional.of(role.name()));
    }

    // ── Codec ────────────────────────────────────────────────────────────────

    public static final Codec<JobContractTerms> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.xmap(Profession::valueOf, Profession::name)
                    .fieldOf("profession").forGetter(JobContractTerms::profession),
            Rank.CODEC.optionalFieldOf("rank", Rank.NONE)
                    .forGetter(JobContractTerms::rank),
            UUIDUtil.CODEC.optionalFieldOf("masterUuid")
                    .forGetter(JobContractTerms::masterUuid),
            Codec.LONG.optionalFieldOf("salaryBronze", 0L)
                    .forGetter(JobContractTerms::salaryBronze),
            Codec.STRING.optionalFieldOf("targetItemId")
                    .forGetter(JobContractTerms::targetItemId),
            Codec.LONG.optionalFieldOf("durationTicks", 0L)
                    .forGetter(JobContractTerms::durationTicks),
            UUIDUtil.CODEC.optionalFieldOf("commissionerUuid")
                    .forGetter(JobContractTerms::commissionerUuid),
            UUIDUtil.CODEC.optionalFieldOf("recruitingBusinessId")
                    .forGetter(JobContractTerms::recruitingBusinessId),
            Codec.STRING.optionalFieldOf("recruiterRole")
                    .forGetter(JobContractTerms::recruiterRole)
    ).apply(i, JobContractTerms::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, JobContractTerms> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
