package tterrag1112.life_in_the_village.Quests;

import com.mojang.serialization.Codec;
import tterrag1112.life_in_the_village.Guilds.Adventurer.GuildRank;

import java.util.Locale;

/**
 * F2b-1 — optional quest difficulty metadata on the F2 base, mirroring the legacy guild
 * {@code Quest.QuestDifficulty} (same names + the {@link GuildRank} accept-gate + base
 * reward magnitudes), so F2b-2's re-seat is a straight name map. Generic religious
 * quests leave the difficulty unset (NONE).
 */
public enum QuestDifficulty {
    NONE(GuildRank.BRONZE, 0, 0),
    EASY(GuildRank.BRONZE, 10, 32),
    MEDIUM(GuildRank.SILVER, 25, 64),
    HARD(GuildRank.GOLD, 50, 128),
    ELITE(GuildRank.PLATINUM, 100, 256),
    LEGENDARY(GuildRank.DIAMOND, 200, 512);

    private final GuildRank minRank;
    private final int baseXp;
    private final long baseCoinReward;

    QuestDifficulty(GuildRank minRank, int baseXp, long baseCoinReward) {
        this.minRank = minRank;
        this.baseXp = baseXp;
        this.baseCoinReward = baseCoinReward;
    }

    public GuildRank minRank()      { return minRank; }
    public int baseXp()             { return baseXp; }
    public long baseCoinReward()    { return baseCoinReward; }

    public static final Codec<QuestDifficulty> CODEC = Codec.STRING.xmap(
            s -> QuestDifficulty.valueOf(s.toUpperCase(Locale.ROOT)), QuestDifficulty::name);
}
