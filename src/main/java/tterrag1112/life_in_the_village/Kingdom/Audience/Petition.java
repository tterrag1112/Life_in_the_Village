package tterrag1112.life_in_the_village.Kingdom.Audience;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Track D3.5A — single petition submitted by a player to a
 * kingdom's audience chamber.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li><b>PENDING</b> — submitted, awaiting ruler decision.</li>
 *   <li><b>APPROVED</b> — ruler approved; effects applied.</li>
 *   <li><b>DENIED</b> — ruler denied; standing dip applied.</li>
 *   <li><b>EXPIRED</b> — auto-expired after
 *       {@link #DEFAULT_EXPIRY_TICKS}; standing untouched.</li>
 *   <li><b>WITHDRAWN</b> — player withdrew before resolution;
 *       standing untouched.</li>
 * </ol>
 *
 * @param id              stable UUID; primary key.
 * @param playerUuid      submitter (player).
 * @param kingdomId       target kingdom (audience chamber owner).
 * @param kind            discriminator selecting the
 *                        {@link PetitionPayload} variant.
 * @param payload         kind-specific payload.
 * @param submittedTick   server tick at submission.
 * @param status          current lifecycle state.
 * @param resolvedTick    tick the ruler resolved (or 0 if pending).
 * @param resolvedBy      ruler NPC / player UUID; empty for
 *                        EXPIRED.
 * @param resolutionNote  one-line ruler reason for the decision
 *                        (debug / GUI surface). Stable across
 *                        reloads.
 */
public record Petition(
        UUID id,
        UUID playerUuid,
        UUID kingdomId,
        PetitionKind kind,
        PetitionPayload payload,
        long submittedTick,
        Status status,
        long resolvedTick,
        Optional<UUID> resolvedBy,
        String resolutionNote
) {

    public enum Status { PENDING, APPROVED, DENIED, EXPIRED, WITHDRAWN }

    /** 7 in-game days before a pending petition auto-expires. */
    public static final long DEFAULT_EXPIRY_TICKS = 7L * 24000L;

    public Petition {
        if (resolutionNote == null) resolutionNote = "";
    }

    public static final Codec<Petition> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Petition::id),
            UUIDUtil.CODEC.fieldOf("playerUuid").forGetter(Petition::playerUuid),
            UUIDUtil.CODEC.fieldOf("kingdomId").forGetter(Petition::kingdomId),
            Codec.STRING.xmap(PetitionKind::valueOf, Enum::name)
                    .fieldOf("kind").forGetter(Petition::kind),
            PetitionPayload.CODEC.fieldOf("payload").forGetter(Petition::payload),
            Codec.LONG.fieldOf("submittedTick").forGetter(Petition::submittedTick),
            Codec.STRING.xmap(Status::valueOf, Enum::name)
                    .optionalFieldOf("status", Status.PENDING)
                    .forGetter(Petition::status),
            Codec.LONG.optionalFieldOf("resolvedTick", 0L).forGetter(Petition::resolvedTick),
            UUIDUtil.CODEC.optionalFieldOf("resolvedBy").forGetter(Petition::resolvedBy),
            Codec.STRING.optionalFieldOf("resolutionNote", "").forGetter(Petition::resolutionNote)
    ).apply(i, Petition::new));

    public boolean isPending() { return status == Status.PENDING; }
    public boolean isResolved() { return status != Status.PENDING; }

    /** True when {@code currentTick} is past the submission expiry. */
    public boolean isExpiredAt(long currentTick) {
        return status == Status.PENDING
                && currentTick - submittedTick >= DEFAULT_EXPIRY_TICKS;
    }

    // ── Lifecycle copy helpers ─────────────────────────────────────────────

    public Petition asApproved(UUID by, long tick, String note) {
        return new Petition(id, playerUuid, kingdomId, kind, payload,
                submittedTick, Status.APPROVED, tick,
                Optional.ofNullable(by), note == null ? "" : note);
    }

    public Petition asDenied(UUID by, long tick, String note) {
        return new Petition(id, playerUuid, kingdomId, kind, payload,
                submittedTick, Status.DENIED, tick,
                Optional.ofNullable(by), note == null ? "" : note);
    }

    public Petition asExpired(long tick) {
        return new Petition(id, playerUuid, kingdomId, kind, payload,
                submittedTick, Status.EXPIRED, tick,
                Optional.empty(), "auto-expired");
    }

    public Petition asWithdrawn(long tick) {
        return new Petition(id, playerUuid, kingdomId, kind, payload,
                submittedTick, Status.WITHDRAWN, tick,
                Optional.empty(), "withdrawn by petitioner");
    }

    public static Petition freshSubmission(UUID id, UUID playerUuid, UUID kingdomId,
                                           PetitionPayload payload, long tick) {
        return new Petition(id, playerUuid, kingdomId, payload.kind(), payload,
                tick, Status.PENDING, 0L, Optional.empty(), "");
    }
}
