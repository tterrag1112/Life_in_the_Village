package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 28 line 65 — visibility scope. Requests escalate up the
 * ladder when unfulfilled, gated on the daily ticker per spec line
 * 185-187.
 */
public enum RequestScope {
    /** Only members of the origin guild are eligible to accept. */
    VILLAGE_INTERNAL(0),
    /** Any guild in the same village can accept. */
    VILLAGE_PUBLIC(1),
    /** Any guild in the same kingdom. */
    KINGDOM_WIDE(2),
    /** Any guild anywhere. */
    GLOBAL(3);

    private final int rank;

    RequestScope(int rank) { this.rank = rank; }

    public int rank() { return rank; }

    public boolean isAtLeast(RequestScope other) {
        return other != null && rank >= other.rank;
    }

    /** Returns the next escalation step or null if already at GLOBAL. */
    public RequestScope nextEscalation() {
        return switch (this) {
            case VILLAGE_INTERNAL -> VILLAGE_PUBLIC;
            case VILLAGE_PUBLIC   -> KINGDOM_WIDE;
            case KINGDOM_WIDE     -> GLOBAL;
            case GLOBAL           -> null;
        };
    }

    /** Days at the current scope before automatic escalation per spec
     *  lines 185-187. GLOBAL never escalates further. */
    public int escalationDays() {
        return switch (this) {
            case VILLAGE_INTERNAL -> 3;
            case VILLAGE_PUBLIC   -> 7;
            case KINGDOM_WIDE     -> 14;
            case GLOBAL           -> Integer.MAX_VALUE;
        };
    }

    public static final Codec<RequestScope> CODEC = Codec.STRING.xmap(
            s -> RequestScope.valueOf(s.toUpperCase(Locale.ROOT)),
            RequestScope::name);
}
