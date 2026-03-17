package tterrag1112.life_in_the_village.Guild;

public enum GuildRank {
    BRONZE(0, 100),
    SILVER(100, 300),
    GOLD(300, 600),
    PLATINUM(600, 1000),
    DIAMOND(1000, Integer.MAX_VALUE);

    private final int minXp;
    private final int maxXp;

    GuildRank(int minXp, int maxXp) {
        this.minXp = minXp;
        this.maxXp = maxXp;
    }

    public int getMinXp()  { return minXp; }
    public int getMaxXp()  { return maxXp; }

    public static GuildRank fromXp(int xp) {
        for (GuildRank rank : values()) {
            if (xp >= rank.minXp && xp < rank.maxXp) return rank;
        }
        return DIAMOND;
    }

    public boolean canAcceptQuest(Quest.QuestDifficulty difficulty) {
        return difficulty.minRank().ordinal() <= this.ordinal();
    }

    public GuildRank next() {
        GuildRank[] values = values();
        int next = ordinal() + 1;
        return next < values.length ? values[next] : DIAMOND;
    }

    public boolean isMaxRank() { return this == DIAMOND; }

    public String getDisplayName() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }
}