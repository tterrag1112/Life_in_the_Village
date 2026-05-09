package tterrag1112.life_in_the_village.Village.Roads.Proposal;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import tterrag1112.life_in_the_village.Networking.VillageSavedData;
import tterrag1112.life_in_the_village.Networking.WorldRoadSavedData;
import tterrag1112.life_in_the_village.DataAttachments.ModData;
import tterrag1112.life_in_the_village.Profession.PlayerProfession;
import tterrag1112.life_in_the_village.Profession.PlayerProfessionData;
import tterrag1112.life_in_the_village.Profession.ProfessionEvents;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CoinHelper;
import tterrag1112.life_in_the_village.Village.Economy.Currency.CurrencyValue;
import tterrag1112.life_in_the_village.Village.Reputation.ReputationManager;
import tterrag1112.life_in_the_village.Village.Reputation.VillageReputation;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;
import tterrag1112.life_in_the_village.Village.Roads.Graph.WorldRoadGraph;
import tterrag1112.life_in_the_village.Village.Roads.Realization.EdgeRealizer;
import tterrag1112.life_in_the_village.Village.Village;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Track C3.1 — orchestrates the lifecycle of a {@link RoadProposal}.
 *
 * <h3>Submission</h3>
 * {@link #submit} dry-runs the route, computes the upfront fee via
 * {@link RoadProposalCalculator}, deducts the fee from the player's
 * coin inventory using {@link CoinHelper}, then persists a fresh
 * {@link RoadProposal} in {@code IN_PROGRESS} state to
 * {@link WorldRoadSavedData}.
 *
 * <h3>Per-tick advancement</h3>
 * {@link #tick} is called every second by
 * {@code RoadProposalTickSystem} (interval = 20). It scans all
 * {@code IN_PROGRESS} proposals and adds {@code blocksPerSecond}
 * progress, scaled by the proposing player's
 * {@link PlayerProfession#ROAD_ENGINEER} level. When a proposal hits
 * its {@code totalBlocks} budget, it is committed.
 *
 * <h3>Completion</h3>
 * {@link #complete} routes the cellPath into a real {@link RoadEdge}
 * via {@link RoadProposalRouter#commit}, calls
 * {@link EdgeRealizer#realizeEdge} to materialise the blocks in the
 * world, grants reputation to both endpoint villages, awards XP to
 * the proposing player via the dedicated
 * {@link PlayerProfession.XpSource#ROAD_PROPOSAL_COMPLETE} source,
 * and marks the proposal {@code COMPLETE}.
 */
public final class RoadProposalManager {

    private RoadProposalManager() {}

    /** Per-second block-count progress at level 0; multiplied by (1 + 0.5 × level). */
    public static final int BASE_BLOCKS_PER_SECOND = 1;

    public sealed interface SubmitResult {
        record Submitted(RoadProposal proposal) implements SubmitResult {}
        record Rejected(String reason)          implements SubmitResult {}
    }

    /**
     * Submits a new road proposal. Performs validation, cost computation,
     * and currency deduction in one server-side step. The returned
     * proposal is already persisted on {@link WorldRoadSavedData}.
     */
    public static SubmitResult submit(ServerLevel level,
                                       ServerPlayer player,
                                       Village villageA,
                                       Village villageB,
                                       RoadEdge.EdgeTier tier) {
        // Track C3.1 — prevent two outstanding proposals for the same pair.
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        for (RoadProposal existing : saved.getAllProposals().values()) {
            if (existing.status() == RoadProposal.Status.COMPLETE
                    || existing.status() == RoadProposal.Status.CANCELLED) continue;
            boolean samePair =
                    (existing.fromVillageId().equals(villageA.getId())
                            && existing.toVillageId().equals(villageB.getId()))
                    || (existing.fromVillageId().equals(villageB.getId())
                            && existing.toVillageId().equals(villageA.getId()));
            if (samePair) {
                return new SubmitResult.Rejected(
                        "A road proposal between these villages is already in progress.");
            }
        }

        RoadProposalRouter.RouteResult routed = RoadProposalRouter.dryRun(level, villageA, villageB);
        if (routed instanceof RoadProposalRouter.RouteResult.NoRoute(String reason)) {
            return new SubmitResult.Rejected(reason);
        }
        RoadProposalRouter.RouteResult.Routed ok =
                (RoadProposalRouter.RouteResult.Routed) routed;

        RoadProposalCalculator.Estimate est =
                RoadProposalCalculator.estimate(ok.cellPath(), tier);

        if (!CoinHelper.playerCanAfford(player, est.currencyFee())) {
            return new SubmitResult.Rejected(
                    "Insufficient currency. Required: " + est.currencyFee()
                            + ", available: " + CoinHelper.getPlayerWealth(player));
        }
        if (!CoinHelper.playerPay(player, est.currencyFee())) {
            return new SubmitResult.Rejected(
                    "Payment could not be deducted (transient error).");
        }

        RoadProposal proposal = new RoadProposal(
                UUID.randomUUID(),
                player.getUUID(),
                villageA.getId(),
                villageB.getId(),
                ok.cellPath(),
                tier,
                est.currencyFeeBronze(),
                est.totalBlocks(),
                0,
                RoadProposal.Status.IN_PROGRESS,
                level.getGameTime());
        saved.putProposal(proposal);

        return new SubmitResult.Submitted(proposal);
    }

    /**
     * Per-second tick. Advances every {@code IN_PROGRESS} proposal and
     * commits any that hit their budget.
     */
    public static void tick(ServerLevel level) {
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        // Snapshot to avoid concurrent-modification when we replace
        // proposals in the underlying map.
        List<RoadProposal> snapshot =
                new ArrayList<>(saved.getAllProposals().values());

        // Cache player levels by player id so we don't re-resolve per-proposal
        // when the player owns multiple in-progress proposals.
        Map<UUID, Integer> levelCache = new HashMap<>();

        for (RoadProposal p : snapshot) {
            if (p.status() != RoadProposal.Status.IN_PROGRESS) continue;

            int playerLevel = levelCache.computeIfAbsent(p.proposingPlayerId(),
                    pid -> resolvePlayerLevel(level, pid));
            int delta = (int) Math.max(1,
                    Math.round(BASE_BLOCKS_PER_SECOND * (1.0 + 0.5 * playerLevel)));

            int newProgress = Math.min(p.totalBlocks(), p.progressBlocks() + delta);
            RoadProposal advanced = p.withProgress(newProgress);
            saved.putProposal(advanced);

            if (newProgress >= advanced.totalBlocks()) {
                complete(level, advanced);
            }
        }
    }

    /**
     * Forces a proposal to completion regardless of current progress.
     * Used by {@code /litv road debug complete_proposal} and by the
     * normal tick loop when a proposal hits 100%.
     */
    public static void complete(ServerLevel level, RoadProposal proposal) {
        WorldRoadSavedData saved = WorldRoadSavedData.get(level);
        if (proposal.status() == RoadProposal.Status.COMPLETE) return;

        VillageSavedData vdata = VillageSavedData.get(level);
        Village vA = vdata.getVillageById(proposal.fromVillageId()).orElse(null);
        Village vB = vdata.getVillageById(proposal.toVillageId()).orElse(null);
        if (vA == null || vB == null) {
            saved.putProposal(proposal.withStatus(RoadProposal.Status.CANCELLED));
            System.out.println("[RoadProposalManager] proposal "
                    + proposal.id().toString().substring(0, 8)
                    + " cancelled — endpoint village missing.");
            return;
        }

        UUID edgeId = RoadProposalRouter.commit(level, vA, vB,
                proposal.cellPath(), proposal.tier());

        // Realise the new edge so the blocks appear in the world. Skip
        // if the edge is missing for any reason (defensive — commit()
        // just created it, so this should always succeed).
        WorldRoadGraph graph = saved.getGraph();
        RoadEdge edge = graph.getEdge(edgeId);
        if (edge != null) {
            EdgeRealizer.realizeEdge(level, edge, graph, vdata, saved);
        }

        // Reputation grant to both endpoint villages, plus an XP grant
        // to the proposing player. Both are no-ops if the player is
        // offline (ReputationManager.onRoadProposalCompleted accepts
        // only an online ServerPlayer because it sends a tier-change
        // toast; a player who logs back in still sees their stored
        // record next time the modifier is queried).
        ServerPlayer onlinePlayer = level.getServer().getPlayerList()
                .getPlayer(proposal.proposingPlayerId());
        if (onlinePlayer != null) {
            ReputationManager.onRoadProposalCompleted(onlinePlayer, vA.getId(), level);
            ReputationManager.onRoadProposalCompleted(onlinePlayer, vB.getId(), level);

            PlayerProfessionData profData =
                    onlinePlayer.getData(ModData.PROFESSION_DATA.get());
            int xpReward = PlayerProfession.ROAD_ENGINEER.getXpReward(
                    PlayerProfession.XpSource.ROAD_PROPOSAL_COMPLETE);
            ProfessionEvents.awardXp(onlinePlayer, profData,
                    PlayerProfession.ROAD_ENGINEER, xpReward);
            onlinePlayer.displayClientMessage(Component.literal(
                    "Road completed: " + vA.getName() + " ↔ " + vB.getName()
                    + " (+" + xpReward + " XP, +"
                    + VillageReputation.ChangeReason.ROAD_PROPOSAL_COMPLETE.scoreDelta
                    + " reputation at each village)."), false);
        }

        saved.putProposal(proposal
                .withProgress(proposal.totalBlocks())
                .withStatus(RoadProposal.Status.COMPLETE));

        System.out.println("[RoadProposalManager] proposal "
                + proposal.id().toString().substring(0, 8)
                + " complete — edge=" + edgeId.toString().substring(0, 8)
                + " (" + proposal.totalBlocks() + " blocks budgeted)");
    }

    /** Marks the proposal CANCELLED. No currency refund. */
    public static void cancel(ServerLevel level, RoadProposal proposal) {
        WorldRoadSavedData.get(level).putProposal(
                proposal.withStatus(RoadProposal.Status.CANCELLED));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int resolvePlayerLevel(ServerLevel level, UUID playerId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return 0; // offline player — minimal advancement
        PlayerProfessionData data =
                player.getData(ModData.PROFESSION_DATA.get());
        int xp = data.getXp(PlayerProfession.ROAD_ENGINEER);
        return PlayerProfession.ROAD_ENGINEER.getLevelFromXp(xp);
    }

    /** Returns all proposals owned by {@code playerId}, in submission order. */
    public static List<RoadProposal> proposalsForPlayer(ServerLevel level, UUID playerId) {
        return WorldRoadSavedData.get(level).getAllProposals().values().stream()
                .filter(p -> p.proposingPlayerId().equals(playerId))
                .sorted((a, b) -> Long.compare(a.createdAtTick(), b.createdAtTick()))
                .toList();
    }

    public static Optional<RoadProposal> get(ServerLevel level, UUID id) {
        return WorldRoadSavedData.get(level).getProposal(id);
    }
}
