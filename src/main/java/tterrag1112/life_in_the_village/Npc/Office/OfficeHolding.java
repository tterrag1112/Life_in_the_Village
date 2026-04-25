package tterrag1112.life_in_the_village.Npc.Office;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Dynamic record of who currently holds an office. Spec section
 * "OfficeHolding".
 *
 * <p>Exactly one of {@link #holderNpcId} and {@link #holderPlayerId}
 * should be present for a held office; both empty means vacant. The
 * spec keeps them as separate optional fields rather than tagging the
 * UUID, so player-vs-NPC lookups don't require extra disambiguation.</p>
 *
 * <p>{@code termEndTick == 0} means an indefinite term (lifelong, no
 * scheduled vacate). Phase 3 wires up term-end checks; Phase 0 just
 * stores the value.</p>
 */
public record OfficeHolding(
        String officeId,
        UUID orgId,
        Optional<UUID> holderNpcId,
        Optional<UUID> holderPlayerId,
        long termStartTick,
        long termEndTick,
        SelectionMethod actualSelection
) {
    /** True when neither an NPC nor a player holds this office. */
    public boolean isVacant() {
        return holderNpcId.isEmpty() && holderPlayerId.isEmpty();
    }

    public boolean holderIsPlayer() { return holderPlayerId.isPresent(); }
    public boolean holderIsNpc()    { return holderNpcId.isPresent(); }

    /** Returns the holder UUID irrespective of player/NPC, or empty if vacant. */
    public Optional<UUID> holderUuid() {
        return holderPlayerId.isPresent() ? holderPlayerId : holderNpcId;
    }

    /** Days remaining in the term, or {@code -1} for indefinite/never-ending. */
    public long daysRemaining(long currentTick) {
        if (termEndTick <= 0L) return -1L;
        long ticksLeft = termEndTick - currentTick;
        if (ticksLeft <= 0L) return 0L;
        return ticksLeft / 24000L;
    }

    public boolean isHeldBy(UUID uuid) {
        if (uuid == null) return false;
        return holderNpcId.filter(uuid::equals).isPresent()
                || holderPlayerId.filter(uuid::equals).isPresent();
    }

    // ── Construction helpers ───────────────────────────────────────────────

    /** Vacant holding for the given (officeId, orgId). */
    public static OfficeHolding vacant(String officeId, UUID orgId,
                                       SelectionMethod selection) {
        return new OfficeHolding(officeId, orgId,
                Optional.empty(), Optional.empty(),
                0L, 0L, selection);
    }

    /** Holding seated by an NPC. */
    public static OfficeHolding heldByNpc(String officeId, UUID orgId,
                                          UUID npcId,
                                          long termStartTick, long termEndTick,
                                          SelectionMethod selection) {
        return new OfficeHolding(officeId, orgId,
                Optional.of(npcId), Optional.empty(),
                termStartTick, termEndTick, selection);
    }

    /** Holding seated by a player. */
    public static OfficeHolding heldByPlayer(String officeId, UUID orgId,
                                             UUID playerId,
                                             long termStartTick, long termEndTick,
                                             SelectionMethod selection) {
        return new OfficeHolding(officeId, orgId,
                Optional.empty(), Optional.of(playerId),
                termStartTick, termEndTick, selection);
    }

    public static final Codec<OfficeHolding> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("officeId").forGetter(OfficeHolding::officeId),
            UUIDUtil.CODEC.fieldOf("orgId").forGetter(OfficeHolding::orgId),
            UUIDUtil.CODEC.optionalFieldOf("holderNpcId").forGetter(OfficeHolding::holderNpcId),
            UUIDUtil.CODEC.optionalFieldOf("holderPlayerId").forGetter(OfficeHolding::holderPlayerId),
            Codec.LONG.optionalFieldOf("termStartTick", 0L).forGetter(OfficeHolding::termStartTick),
            Codec.LONG.optionalFieldOf("termEndTick", 0L).forGetter(OfficeHolding::termEndTick),
            SelectionMethod.CODEC.optionalFieldOf("actualSelection", SelectionMethod.MERITOCRATIC)
                    .forGetter(OfficeHolding::actualSelection)
    ).apply(i, OfficeHolding::new));
}
