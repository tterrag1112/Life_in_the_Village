package tterrag1112.life_in_the_village.Npc.Health;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.Optional;
import java.util.UUID;

/**
 * Spec line 168. A single PLAGUE_OUTBREAK event for one village.
 *
 * <p>Lifecycle:</p>
 * <ul>
 *   <li>{@link Stage#ACTIVE} — daily 10% infection roll for every
 *   non-infected, non-immune villager (spec line 172). Quarantine
 *   law cuts the spread by 60% (spec line 174).</li>
 *   <li>{@link Stage#RESOLVED} — once the infection count hits zero
 *   or the 30-day max duration is reached (spec line 175).</li>
 * </ul>
 */
public record Plague(
        UUID plagueId,
        UUID villageId,
        long startTick,
        long endTick,
        Stage stage,
        int infectedCount,
        int recoveredCount,
        int diedCount,
        Optional<UUID> healerOnDuty
) {
    /** Spec line 175 — 30 day cap. */
    public static final long MAX_DURATION_TICKS = 30L * HealthDurations.DAY;

    public enum Stage {
        ACTIVE,
        RESOLVED;

        public static final Codec<Stage> CODEC = Codec.STRING.xmap(
                Stage::valueOf, Stage::name);
    }

    public Plague {
        if (plagueId  == null) throw new IllegalArgumentException("plagueId required");
        if (villageId == null) throw new IllegalArgumentException("villageId required");
        if (stage     == null) stage = Stage.ACTIVE;
        if (healerOnDuty == null) healerOnDuty = Optional.empty();
    }

    public static Plague start(UUID villageId, long currentTick) {
        return new Plague(UUID.randomUUID(), villageId, currentTick,
                currentTick + MAX_DURATION_TICKS,
                Stage.ACTIVE, 0, 0, 0, Optional.empty());
    }

    public Plague withCounts(int infected, int recovered, int died) {
        return new Plague(plagueId, villageId, startTick, endTick, stage,
                Math.max(0, infected), Math.max(0, recovered), Math.max(0, died),
                healerOnDuty);
    }

    public Plague withHealer(UUID healerId) {
        return new Plague(plagueId, villageId, startTick, endTick, stage,
                infectedCount, recoveredCount, diedCount,
                Optional.ofNullable(healerId));
    }

    public Plague resolve(long currentTick) {
        return new Plague(plagueId, villageId, startTick, currentTick,
                Stage.RESOLVED, infectedCount, recoveredCount, diedCount,
                healerOnDuty);
    }

    public boolean isActive() { return stage == Stage.ACTIVE; }

    public boolean isExpired(long currentTick) {
        return currentTick >= endTick;
    }

    public static final Codec<Plague> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("plagueId").forGetter(Plague::plagueId),
            UUIDUtil.CODEC.fieldOf("villageId").forGetter(Plague::villageId),
            Codec.LONG.fieldOf("startTick").forGetter(Plague::startTick),
            Codec.LONG.fieldOf("endTick").forGetter(Plague::endTick),
            Stage.CODEC.optionalFieldOf("stage", Stage.ACTIVE).forGetter(Plague::stage),
            Codec.INT.optionalFieldOf("infectedCount", 0).forGetter(Plague::infectedCount),
            Codec.INT.optionalFieldOf("recoveredCount", 0).forGetter(Plague::recoveredCount),
            Codec.INT.optionalFieldOf("diedCount", 0).forGetter(Plague::diedCount),
            UUIDUtil.CODEC.optionalFieldOf("healerOnDuty").forGetter(Plague::healerOnDuty)
    ).apply(i, Plague::new));
}
