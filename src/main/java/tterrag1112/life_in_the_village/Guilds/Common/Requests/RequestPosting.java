package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;
import tterrag1112.life_in_the_village.Guilds.Common.GuildTreasury;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 4 doc 28 — posting helpers. Lives outside {@link AbstractGuild}
 * so the abstract class stays free of {@code RequestBoard} +
 * {@code ServerLevel} dependencies (the codec layer is shared with
 * client-side preview code).
 *
 * <p>Posting cost = {@link #PLATFORM_FEE_RATE} × bounty held in
 * escrow on the originating guild's treasury (spec line 113-114).
 * The full bronze stays inside the guild's treasury record; the
 * settlement layer in {@link RequestSettlement} handles the
 * fee + accepting-guild + fulfiller split on terminal states.</p>
 */
public final class RequestPosting {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Spec line 114 — platform fee fraction held aside on post. */
    public static final float PLATFORM_FEE_RATE = 0.10f;
    /** Spec line 167 — accepting guild's share on FULFILLED. */
    public static final float ACCEPTING_GUILD_SHARE = 0.80f;
    /** Spec line 169 — fulfiller's share OF the accepting guild's cut. */
    public static final float FULFILLER_SHARE = 0.70f;
    /** Spec line 175 — accepting-guild refund on FAILED. */
    public static final float FAILURE_REFUND_RATE = 0.50f;

    private RequestPosting() {}

    /**
     * Posts a request on behalf of {@code originGuild}. Pulls the
     * platform fee from the guild treasury and locks the rest in
     * escrow (kept inside the same {@link GuildTreasury} until
     * settlement). Returns the new {@link Request} or empty when:
     *
     * <ul>
     *   <li>The guild can't post (level &lt; ESTABLISHED).</li>
     *   <li>Treasury can't cover the bounty + fee.</li>
     * </ul>
     */
    public static Optional<Request> post(ServerLevel level,
                                         AbstractGuild originGuild,
                                         RequestType type,
                                         RequestSpec spec) {
        if (originGuild == null || spec == null) return Optional.empty();
        if (!originGuild.canPostRequests()) {
            LOGGER.debug("[RequestPosting] {} can't post — level={}",
                    originGuild.name(), originGuild.level());
            return Optional.empty();
        }
        long fee   = (long) Math.ceil(spec.bronzeReward() * PLATFORM_FEE_RATE);
        long total = spec.bronzeReward() + fee;
        if (!originGuild.treasury().withdraw(total)) {
            LOGGER.debug("[RequestPosting] {} treasury too small ({}) for posting (need {})",
                    originGuild.name(), originGuild.treasury().balance(), total);
            return Optional.empty();
        }
        long now = level.getGameTime();
        Request req = new Request(
                UUID.randomUUID(),
                type,
                originGuild.guildId(),
                originGuild.homeVillageId(),
                Optional.ofNullable(spec.originatorNpcId()),
                spec.bronzeReward(),
                now,
                spec.deadlineTick(),
                RequestStatus.OPEN,
                spec.scope(),
                now,
                new Request.Target(
                        spec.targetItem() != null ? spec.targetItem() : Items.AIR,
                        spec.targetCount(),
                        spec.targetMobId() != null ? spec.targetMobId() : "",
                        spec.targetBiome() != null ? spec.targetBiome() : "",
                        Optional.ofNullable(spec.targetLocation())),
                Request.Progress.EMPTY);
        RequestBoard.get(level).post(req);
        GuildSavedData.get(level).putGuild(originGuild);
        LOGGER.info("[RequestPosting] {} posted {} ({} br, scope={}, id={})",
                originGuild.name(), type, spec.bronzeReward(), spec.scope(), req.requestId());
        return Optional.of(req);
    }

    /**
     * Convenience builder for the post() arguments. Records keep call
     * sites tidy and let me ship one builder per call site without
     * polluting the main Request record.
     */
    public record RequestSpec(
            Item targetItem,
            int targetCount,
            String targetMobId,
            String targetBiome,
            BlockPos targetLocation,
            long bronzeReward,
            long deadlineTick,
            RequestScope scope,
            UUID originatorNpcId
    ) {
        /** GATHER / CRAFT spec — item + count + bounty. */
        public static RequestSpec items(Item item, int count, long reward,
                                        long deadlineTick, RequestScope scope) {
            return new RequestSpec(item, count, "", "", null,
                    reward, deadlineTick, scope, null);
        }

        /** HUNT spec — mob id + bounty. */
        public static RequestSpec hunt(String mobId, int count, long reward,
                                       long deadlineTick, RequestScope scope) {
            return new RequestSpec(Items.AIR, count, mobId, "", null,
                    reward, deadlineTick, scope, null);
        }

        /** SURVEY / ESCORT spec — biome or location-bound. */
        public static RequestSpec atLocation(String biome, BlockPos location,
                                             long reward, long deadlineTick,
                                             RequestScope scope) {
            return new RequestSpec(Items.AIR, 1, "", biome, location,
                    reward, deadlineTick, scope, null);
        }
    }
}
