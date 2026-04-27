package tterrag1112.life_in_the_village.Guilds.Common;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * Phase 4 doc 27 — guild-side analogue of {@code VillageTreasury}.
 * Tracks bronze on the guild itself separately from member personal
 * wallets and from the village treasury.
 *
 * <p>Income sources (wired in later phases):</p>
 * <ul>
 *   <li>Membership dues (Phase 5).</li>
 *   <li>Request fulfilment fees (Phase 4 doc 28 — request board).</li>
 *   <li>Donations.</li>
 * </ul>
 *
 * <p>Expense sources:</p>
 * <ul>
 *   <li>Officer wages (guild_master, treasurer, registrar).</li>
 *   <li>Hall maintenance.</li>
 *   <li>Request bounties paid to fulfillers.</li>
 * </ul>
 *
 * <p>Implicit (L0) guilds have a treasury record but it stays at the
 * starting balance — they have no hall to draw expenses against.</p>
 */
public final class GuildTreasury {

    /** Default balance handed to a freshly-promoted L1 guild. Spec
     *  doesn't pin this; v1 ships 200 bronze so a small guild can
     *  fund a request bounty in its first week. */
    public static final long L1_STARTING_BALANCE = 200L;

    private final UUID guildId;
    private long balance;
    private long totalIncome;
    private long totalExpenses;

    public GuildTreasury(UUID guildId) {
        this(guildId, 0L, 0L, 0L);
    }

    public GuildTreasury(UUID guildId, long balance,
                         long totalIncome, long totalExpenses) {
        this.guildId       = guildId;
        this.balance       = balance;
        this.totalIncome   = totalIncome;
        this.totalExpenses = totalExpenses;
    }

    public UUID guildId()        { return guildId; }
    public long balance()        { return balance; }
    public long totalIncome()    { return totalIncome; }
    public long totalExpenses()  { return totalExpenses; }

    /** Adds bronze to the treasury. Returns true; never refuses. */
    public boolean deposit(long bronze) {
        if (bronze <= 0L) return false;
        balance     += bronze;
        totalIncome += bronze;
        return true;
    }

    /** Removes bronze; refuses (returns false) when the treasury
     *  can't cover the request. Spec says deficits warn the guild
     *  master; that warning lives on the daily-tick subsystem (not
     *  shipped this session). */
    public boolean withdraw(long bronze) {
        if (bronze <= 0L) return false;
        if (balance < bronze) return false;
        balance       -= bronze;
        totalExpenses += bronze;
        return true;
    }

    /** Direct setter used only by the codec's load path. Bypasses the
     *  income/expense counters. */
    public void setBalance(long bronze) {
        this.balance = Math.max(0L, bronze);
    }

    public static final Codec<GuildTreasury> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("guildId").forGetter(GuildTreasury::guildId),
            Codec.LONG.optionalFieldOf("balance", 0L).forGetter(GuildTreasury::balance),
            Codec.LONG.optionalFieldOf("totalIncome", 0L).forGetter(GuildTreasury::totalIncome),
            Codec.LONG.optionalFieldOf("totalExpenses", 0L).forGetter(GuildTreasury::totalExpenses)
    ).apply(i, GuildTreasury::new));
}
