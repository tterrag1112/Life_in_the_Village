package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.serialization.Codec;

import java.util.Locale;

/**
 * Phase 4 doc 28 line 56 — request lifecycle states.
 * <pre>
 *   OPEN ──accept──▶ ACCEPTED ──progress──▶ IN_PROGRESS
 *     │                                          │
 *     │ deadline                                 │ progress = total
 *     ▼                                          ▼
 *   EXPIRED                                   FULFILLED
 *
 *   ACCEPTED / IN_PROGRESS ──fail──▶ FAILED
 * </pre>
 */
public enum RequestStatus {
    OPEN,
    ACCEPTED,
    IN_PROGRESS,
    FULFILLED,
    FAILED,
    EXPIRED;

    /** True when the request is still actionable (not in a terminal state). */
    public boolean isActive() {
        return this == OPEN || this == ACCEPTED || this == IN_PROGRESS;
    }

    public boolean isTerminal() {
        return this == FULFILLED || this == FAILED || this == EXPIRED;
    }

    public static final Codec<RequestStatus> CODEC = Codec.STRING.xmap(
            s -> RequestStatus.valueOf(s.toUpperCase(Locale.ROOT)),
            RequestStatus::name);
}
