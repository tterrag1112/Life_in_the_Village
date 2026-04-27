package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Phase 4 doc 27 — uniform guild member entry across all subtypes.
 *
 * <p>Distinct from the existing {@code GuildMember} record which is
 * adventurer-specific (player UUID, completed-quest list, registered
 * time). The new {@link AbstractGuild} hierarchy works on any member
 * — NPC or player — keyed by UUID with rank + contribution score.
 * Adventurer-specific data continues to live on
 * {@code PlayerGuildData} / {@code AdventurerSavedData} alongside.</p>
 *
 * @param memberId UUID — NPC or player
 * @param rank     current {@link GuildRankTier}
 * @param contribution integer point score driving rank promotions; the
 *                 unit varies per guild type (quests, masterpieces,
 *                 trade volume, etc.)
 * @param joinedTick game tick when the member entered the guild
 */
public record GuildMemberRef(
        UUID memberId,
        GuildRankTier rank,
        int contribution,
        long joinedTick
) {
    public GuildMemberRef {
        if (memberId == null) throw new IllegalArgumentException("memberId required");
        if (rank == null) rank = GuildRankTier.APPLICANT;
        if (contribution < 0) contribution = 0;
    }

    public GuildMemberRef withRank(GuildRankTier newRank) {
        return new GuildMemberRef(memberId, newRank, contribution, joinedTick);
    }

    public GuildMemberRef withContribution(int newContribution) {
        return new GuildMemberRef(memberId, rank, Math.max(0, newContribution), joinedTick);
    }

    public GuildMemberRef addContribution(int delta) {
        return withContribution(contribution + delta);
    }

    public static final Codec<GuildMemberRef> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("memberId").forGetter(GuildMemberRef::memberId),
            GuildRankTier.CODEC.optionalFieldOf("rank", GuildRankTier.APPLICANT)
                    .forGetter(GuildMemberRef::rank),
            Codec.INT.optionalFieldOf("contribution", 0).forGetter(GuildMemberRef::contribution),
            Codec.LONG.optionalFieldOf("joinedTick", 0L).forGetter(GuildMemberRef::joinedTick)
    ).apply(i, GuildMemberRef::new));
}
