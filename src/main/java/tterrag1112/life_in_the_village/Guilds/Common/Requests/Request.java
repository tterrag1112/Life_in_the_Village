package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 28 — generalised job posting visible across guilds.
 * Compound record packed into two sub-records to stay under DFU's
 * 16-field {@code RecordCodecBuilder} cap (the doc 26 codec ran into
 * the same wall, see Business.OwnershipInfo).
 *
 * <p>Field bundles:</p>
 * <ul>
 *   <li>{@link Target} — what the request asks for: type-keyed
 *   {@link Item} or mob/biome string + count + optional location.</li>
 *   <li>{@link Progress} — fulfilment-side state: accepting guild,
 *   fulfiller npc, current/total count, accepted-tick.</li>
 * </ul>
 */
public record Request(
        UUID requestId,
        RequestType type,
        UUID originGuildId,
        UUID originVillageId,
        Optional<UUID> originatorNpcId,
        long bronzeReward,
        long postedTick,
        long deadlineTick,
        RequestStatus status,
        RequestScope scope,
        long lastEscalatedTick,
        Target target,
        Progress progress
) {
    public Request {
        if (requestId == null) throw new IllegalArgumentException("requestId required");
        if (type == null) throw new IllegalArgumentException("type required");
        if (originGuildId == null) throw new IllegalArgumentException("originGuildId required");
        if (originVillageId == null) throw new IllegalArgumentException("originVillageId required");
        if (originatorNpcId == null) originatorNpcId = Optional.empty();
        if (status == null) status = RequestStatus.OPEN;
        if (scope == null) scope = RequestScope.VILLAGE_INTERNAL;
        if (target == null) target = Target.EMPTY;
        if (progress == null) progress = Progress.EMPTY;
        if (bronzeReward < 0L) bronzeReward = 0L;
    }

    /** Bundled "what the request wants" — keeps the outer codec under
     *  the field-arity ceiling. */
    public record Target(
            Item targetItem,
            int targetCount,
            String targetMobId,
            String targetBiome,
            Optional<BlockPos> targetLocation
    ) {
        public Target {
            if (targetItem == null) targetItem = Items.AIR;
            if (targetMobId == null) targetMobId = "";
            if (targetBiome == null) targetBiome = "";
            if (targetLocation == null) targetLocation = Optional.empty();
            if (targetCount < 0) targetCount = 0;
        }

        public static final Target EMPTY = new Target(Items.AIR, 0, "", "", Optional.empty());

        public static final Codec<Target> CODEC = RecordCodecBuilder.create(i -> i.group(
                BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("targetItem", Items.AIR)
                        .forGetter(Target::targetItem),
                Codec.INT.optionalFieldOf("targetCount", 0).forGetter(Target::targetCount),
                Codec.STRING.optionalFieldOf("targetMobId", "").forGetter(Target::targetMobId),
                Codec.STRING.optionalFieldOf("targetBiome", "").forGetter(Target::targetBiome),
                BlockPos.CODEC.optionalFieldOf("targetLocation").forGetter(Target::targetLocation)
        ).apply(i, Target::new));
    }

    /** Fulfilment-side state, packed similarly. */
    public record Progress(
            Optional<UUID> acceptingGuildId,
            Optional<UUID> fulfillerNpcId,
            long acceptedTick,
            int currentProgress,
            int totalProgress
    ) {
        public Progress {
            if (acceptingGuildId == null) acceptingGuildId = Optional.empty();
            if (fulfillerNpcId   == null) fulfillerNpcId   = Optional.empty();
            if (currentProgress < 0) currentProgress = 0;
            if (totalProgress   < 0) totalProgress   = 0;
        }

        public static final Progress EMPTY = new Progress(
                Optional.empty(), Optional.empty(), 0L, 0, 0);

        public Progress withAccepted(UUID acceptingGuild, UUID fulfiller, long tick, int total) {
            return new Progress(Optional.ofNullable(acceptingGuild),
                    Optional.ofNullable(fulfiller), tick, currentProgress,
                    Math.max(totalProgress, total));
        }

        public Progress withCurrent(int current) {
            return new Progress(acceptingGuildId, fulfillerNpcId, acceptedTick,
                    Math.max(0, current), totalProgress);
        }

        public boolean isComplete() {
            return totalProgress > 0 && currentProgress >= totalProgress;
        }

        public static final Codec<Progress> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.optionalFieldOf("acceptingGuildId").forGetter(Progress::acceptingGuildId),
                UUIDUtil.CODEC.optionalFieldOf("fulfillerNpcId").forGetter(Progress::fulfillerNpcId),
                Codec.LONG.optionalFieldOf("acceptedTick", 0L).forGetter(Progress::acceptedTick),
                Codec.INT.optionalFieldOf("currentProgress", 0).forGetter(Progress::currentProgress),
                Codec.INT.optionalFieldOf("totalProgress", 0).forGetter(Progress::totalProgress)
        ).apply(i, Progress::new));
    }

    // ── Convenience mutators (returns a new Request) ────────────────────

    public Request withStatus(RequestStatus newStatus) {
        return new Request(requestId, type, originGuildId, originVillageId,
                originatorNpcId, bronzeReward, postedTick, deadlineTick,
                newStatus, scope, lastEscalatedTick, target, progress);
    }

    public Request withScope(RequestScope newScope, long tick) {
        return new Request(requestId, type, originGuildId, originVillageId,
                originatorNpcId, bronzeReward, postedTick, deadlineTick,
                status, newScope, tick, target, progress);
    }

    public Request withProgress(Progress newProgress) {
        return new Request(requestId, type, originGuildId, originVillageId,
                originatorNpcId, bronzeReward, postedTick, deadlineTick,
                status, scope, lastEscalatedTick, target, newProgress);
    }

    public boolean isExpired(long currentTick) {
        return deadlineTick > 0L && currentTick > deadlineTick;
    }

    // ── Codec ────────────────────────────────────────────────────────────

    public static final Codec<Request> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("requestId").forGetter(Request::requestId),
            RequestType.CODEC.fieldOf("type").forGetter(Request::type),
            UUIDUtil.CODEC.fieldOf("originGuildId").forGetter(Request::originGuildId),
            UUIDUtil.CODEC.fieldOf("originVillageId").forGetter(Request::originVillageId),
            UUIDUtil.CODEC.optionalFieldOf("originatorNpcId").forGetter(Request::originatorNpcId),
            Codec.LONG.optionalFieldOf("bronzeReward", 0L).forGetter(Request::bronzeReward),
            Codec.LONG.fieldOf("postedTick").forGetter(Request::postedTick),
            Codec.LONG.optionalFieldOf("deadlineTick", 0L).forGetter(Request::deadlineTick),
            RequestStatus.CODEC.optionalFieldOf("status", RequestStatus.OPEN)
                    .forGetter(Request::status),
            RequestScope.CODEC.optionalFieldOf("scope", RequestScope.VILLAGE_INTERNAL)
                    .forGetter(Request::scope),
            Codec.LONG.optionalFieldOf("lastEscalatedTick", 0L).forGetter(Request::lastEscalatedTick),
            Target.CODEC.optionalFieldOf("target", Target.EMPTY).forGetter(Request::target),
            Progress.CODEC.optionalFieldOf("progress", Progress.EMPTY).forGetter(Request::progress)
    ).apply(i, Request::new));
}
