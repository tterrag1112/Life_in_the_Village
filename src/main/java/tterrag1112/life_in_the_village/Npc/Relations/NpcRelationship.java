package tterrag1112.life_in_the_village.Npc.Relations;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * One entry in {@link NpcRelationshipLedger}. Spec
 * {@code 11-npc-relationship-ledger.md} (line 49).
 *
 * <p>{@code score} clamped to {@code [-100, +100]}; mode is derived
 * from score. Records are immutable; {@link #withScore} and
 * {@link #withInteraction} return new instances and the ledger
 * replaces in place.</p>
 */
public record NpcRelationship(
        UUID otherId,
        int score,
        long firstMetTick,
        long lastInteractionTick,
        int interactionCount,
        RelationshipOrigin origin
) {

    public static final int MIN_SCORE = -100;
    public static final int MAX_SCORE = 100;

    public NpcRelationship {
        if (score < MIN_SCORE) score = MIN_SCORE;
        if (score > MAX_SCORE) score = MAX_SCORE;
        if (origin == null) origin = RelationshipOrigin.MET_SOCIALLY;
    }

    /** Convenience factory for new relationships at the given tick. */
    public static NpcRelationship newAt(UUID otherId, int score, long tick,
                                        RelationshipOrigin origin) {
        return new NpcRelationship(otherId, score, tick, tick, 1, origin);
    }

    public RelationshipMode mode() { return RelationshipMode.fromScore(score); }

    public NpcRelationship withScore(int newScore) {
        return new NpcRelationship(otherId, newScore,
                firstMetTick, lastInteractionTick, interactionCount, origin);
    }

    /** Bumps interactionCount and sets lastInteractionTick to {@code tick}. */
    public NpcRelationship withInteraction(long tick) {
        return new NpcRelationship(otherId, score,
                firstMetTick, tick, interactionCount + 1, origin);
    }

    public NpcRelationship withScoreAndInteraction(int newScore, long tick) {
        return new NpcRelationship(otherId, newScore,
                firstMetTick, tick, interactionCount + 1, origin);
    }

    public static final Codec<NpcRelationship> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("otherId").forGetter(NpcRelationship::otherId),
            Codec.INT.fieldOf("score").forGetter(NpcRelationship::score),
            Codec.LONG.fieldOf("firstMetTick").forGetter(NpcRelationship::firstMetTick),
            Codec.LONG.fieldOf("lastInteractionTick").forGetter(NpcRelationship::lastInteractionTick),
            Codec.INT.optionalFieldOf("interactionCount", 0).forGetter(NpcRelationship::interactionCount),
            RelationshipOrigin.CODEC.optionalFieldOf("origin", RelationshipOrigin.MET_SOCIALLY)
                    .forGetter(NpcRelationship::origin)
    ).apply(i, NpcRelationship::new));
}
