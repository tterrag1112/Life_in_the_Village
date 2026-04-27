package tterrag1112.life_in_the_village.Guilds.Common.Requests;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Entities.custom.TownspersonMob;
import tterrag1112.life_in_the_village.Guilds.Common.AbstractGuild;
import tterrag1112.life_in_the_village.Guilds.Common.GuildSavedData;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Village.Economy.VillageTreasury;
import tterrag1112.life_in_the_village.Village.Village;

/**
 * Phase 4 doc 28 — payment + refund flows on terminal request
 * states. Spec lines 164-179.
 *
 * <p>Numbers ({@link RequestPosting#ACCEPTING_GUILD_SHARE}, etc.)
 * live on {@link RequestPosting} so posting and settlement read the
 * same constants.</p>
 */
public final class RequestSettlement {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RequestSettlement() {}

    /**
     * Releases the escrow on a FULFILLED request:
     * <ol>
     *   <li>Accepting guild treasury gets 80% of the bounty.</li>
     *   <li>Fulfiller NPC wallet gets 70% of the accepting guild's cut.</li>
     *   <li>Village treasury (origin) receives the remaining 10%
     *   platform fee — kingdom split is Phase 5 polish.</li>
     * </ol>
     */
    public static void settleFulfilled(ServerLevel level, Request request) {
        if (request == null) return;
        long bounty = request.bronzeReward();
        if (bounty <= 0L) return;
        GuildSavedData gdata = GuildSavedData.get(level);
        AbstractGuild origin = gdata.get(request.originGuildId()).orElse(null);
        AbstractGuild accepting = request.progress().acceptingGuildId()
                .flatMap(gdata::get).orElse(null);

        long acceptingCut = (long) Math.floor(bounty * RequestPosting.ACCEPTING_GUILD_SHARE);
        long platformFee  = (long) Math.ceil(bounty * RequestPosting.PLATFORM_FEE_RATE);
        // Origin already paid bounty + fee on post; both came out of
        // its treasury. Settlement releases acceptingCut to the
        // accepting guild + platformFee to the village; the residue
        // (bounty - acceptingCut) stays with the platform/village.
        if (accepting != null) {
            accepting.treasury().deposit(acceptingCut);
            // Fulfiller's slice off the accepting guild's deposit.
            long fulfillerCut = (long) Math.floor(acceptingCut * RequestPosting.FULFILLER_SHARE);
            request.progress().fulfillerNpcId().ifPresent(npcId -> {
                if (fulfillerCut > 0L
                        && accepting.treasury().withdraw(fulfillerCut)) {
                    TownspersonMob fulfiller = TownspersonMob.findByUUID(level, npcId).orElse(null);
                    if (fulfiller != null) {
                        fulfiller.getWallet().receive(fulfillerCut);
                    } else {
                        // Fulfiller not loaded — refund to accepting guild.
                        accepting.treasury().deposit(fulfillerCut);
                    }
                }
            });
            gdata.putGuild(accepting);
        }

        // Platform fee goes to the originating village's treasury so the
        // economy benefits whether or not the accepting side is loaded.
        Village v = VillageSavedData.get(level).getVillageById(request.originVillageId())
                .orElse(null);
        if (v != null && platformFee > 0L) {
            VillageSavedData.get(level).getTreasury(v.getId()).ifPresent(treasury -> {
                treasury.deposit(platformFee);
                VillageSavedData.get(level).putTreasury(treasury);
            });
        }

        if (origin != null) {
            // Originating guild gets a contribution-bump for the originator NPC
            // when known; the accepting guild's fulfiller earns contribution
            // from their own guild via the existing rank ladder (spec line 268).
            request.originatorNpcId().ifPresent(npc ->
                    origin.addContribution(npc, 5));
            gdata.putGuild(origin);
        }
        LOGGER.info("[RequestSettlement] FULFILLED {} — accepting+={} br, platformFee={} br",
                request.requestId(), acceptingCut, platformFee);
    }

    /**
     * Refund flow on FAILED. Spec lines 173-176.
     * <ul>
     *   <li>Accepting guild keeps {@link RequestPosting#FAILURE_REFUND_RATE}
     *   of the bounty (penalty for non-delivery).</li>
     *   <li>Originating guild gets the remainder back.</li>
     *   <li>Reputation hit on the accepting guild — modelled here as
     *   −5 contribution to the fulfiller, since v1 doesn't yet track
     *   per-guild reputation tiers.</li>
     * </ul>
     */
    public static void settleFailed(ServerLevel level, Request request) {
        if (request == null) return;
        long bounty = request.bronzeReward();
        if (bounty <= 0L) return;
        GuildSavedData gdata = GuildSavedData.get(level);
        AbstractGuild origin = gdata.get(request.originGuildId()).orElse(null);
        AbstractGuild accepting = request.progress().acceptingGuildId()
                .flatMap(gdata::get).orElse(null);

        long acceptingPenaltyCut = (long) Math.floor(bounty * RequestPosting.FAILURE_REFUND_RATE);
        long originRefund = bounty - acceptingPenaltyCut;
        if (accepting != null) {
            accepting.treasury().deposit(acceptingPenaltyCut);
            request.progress().fulfillerNpcId().ifPresent(npc ->
                    accepting.addContribution(npc, -5));
            gdata.putGuild(accepting);
        }
        if (origin != null && originRefund > 0L) {
            origin.treasury().deposit(originRefund);
            gdata.putGuild(origin);
        }
        LOGGER.info("[RequestSettlement] FAILED {} — origin refund={} br, accepting penalty cut={} br",
                request.requestId(), originRefund, acceptingPenaltyCut);
    }

    /** Refund flow on EXPIRED — bounty + fee returned to originating
     *  guild. No accepting guild involved. */
    public static void settleExpired(ServerLevel level, Request request) {
        if (request == null) return;
        long total = request.bronzeReward()
                + (long) Math.ceil(request.bronzeReward() * RequestPosting.PLATFORM_FEE_RATE);
        if (total <= 0L) return;
        GuildSavedData gdata = GuildSavedData.get(level);
        AbstractGuild origin = gdata.get(request.originGuildId()).orElse(null);
        if (origin != null) {
            origin.treasury().deposit(total);
            gdata.putGuild(origin);
        }
        LOGGER.info("[RequestSettlement] EXPIRED {} — origin refund={} br",
                request.requestId(), total);
    }

    /** Suppresses unused-import warning on VillageTreasury. */
    @SuppressWarnings("unused")
    private static final VillageTreasury DOC_HOOK = null;
}
