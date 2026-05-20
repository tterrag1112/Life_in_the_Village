package tterrag1112.life_in_the_village.Items;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import tterrag1112.life_in_the_village.Profession.Profession;

import java.util.Optional;
import java.util.UUID;

/**
 * Data-component payload for a {@link JobContractItem} stack. Encodes
 * the four flavours the universal hire / commission / apprenticeship
 * item can take:
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
 * </ul>
 *
 * <p>Filled by SCRIBE via the ScribeCounter compose flow; consumed on
 * right-click against a profession-matching NPC. The dispatch routing
 * lives in {@code NpcInteractionHandler}.</p>
 */
public record JobContractTerms(
        Profession profession,
        Rank rank,
        Optional<UUID> masterUuid,
        long salaryBronze,
        Optional<String> targetItemId,
        long durationTicks,
        Optional<UUID> commissionerUuid
) {

    /** Apprentice/journeyman/master encoding for the rank field. NONE = plain hire. */
    public enum Rank {
        NONE, APPRENTICE, JOURNEYMAN, MASTER;
        public static final Codec<Rank> CODEC =
                Codec.STRING.xmap(Rank::valueOf, Rank::name);
    }

    public JobContractTerms {
        if (rank == null) rank = Rank.NONE;
        if (masterUuid == null) masterUuid = Optional.empty();
        if (targetItemId == null) targetItemId = Optional.empty();
        if (commissionerUuid == null) commissionerUuid = Optional.empty();
        if (salaryBronze < 0) salaryBronze = 0;
        if (durationTicks < 0) durationTicks = 0;
    }

    public boolean isApprenticeship() { return rank == Rank.APPRENTICE; }
    public boolean isCommission()     { return targetItemId.isPresent(); }
    public boolean isHire()           { return rank == Rank.NONE && targetItemId.isEmpty(); }

    public static JobContractTerms hire(Profession profession, long salary) {
        return new JobContractTerms(profession, Rank.NONE,
                Optional.empty(), salary, Optional.empty(), 0L, Optional.empty());
    }

    public static JobContractTerms apprenticeship(Profession profession,
                                                  UUID master,
                                                  long durationTicks) {
        return new JobContractTerms(profession, Rank.APPRENTICE,
                Optional.ofNullable(master), 0L, Optional.empty(),
                durationTicks, Optional.empty());
    }

    public static JobContractTerms commission(Profession profession,
                                              String targetItemId,
                                              long bounty,
                                              UUID commissioner) {
        return new JobContractTerms(profession, Rank.NONE,
                Optional.empty(), bounty,
                Optional.of(targetItemId), 0L,
                Optional.ofNullable(commissioner));
    }

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
                    .forGetter(JobContractTerms::commissionerUuid)
    ).apply(i, JobContractTerms::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, JobContractTerms> STREAM_CODEC =
            ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
