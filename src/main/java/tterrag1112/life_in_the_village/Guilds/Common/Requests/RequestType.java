package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 28 line 47 — six request shapes covering production
 * (GATHER / CRAFT), logistics (DELIVER), and travel (HUNT / SURVEY /
 * ESCORT). Spec "Open decisions" #2: GATHER / CRAFT / DELIVER support
 * partial fulfilment; HUNT / SURVEY / ESCORT are binary.
 */
public enum RequestType {
    GATHER,
    CRAFT,
    DELIVER,
    HUNT,
    SURVEY,
    ESCORT;

    public boolean isProduction() {
        return this == GATHER || this == CRAFT || this == DELIVER;
    }

    public boolean isTravel() {
        return this == HUNT || this == SURVEY || this == ESCORT;
    }

    /** Spec "Open decisions" #2 — partial fulfilment allowed for
     *  production/logistics types. HUNT/SURVEY/ESCORT are binary
     *  (you either killed it / mapped it / delivered them, or you
     *  didn't). */
    public boolean supportsPartialFulfilment() {
        return isProduction();
    }

    public static final Codec<RequestType> CODEC = Codec.STRING.xmap(
            s -> RequestType.valueOf(s.toUpperCase(Locale.ROOT)),
            RequestType::name);
}
