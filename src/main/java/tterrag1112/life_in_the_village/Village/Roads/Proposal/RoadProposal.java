package tterrag1112.life_in_the_village.Village.Roads.Proposal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import tterrag1112.life_in_the_village.Village.Roads.Graph.RoadEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Track C3.1 (Phase 11) — a player's standing request to construct a
 * road connector between two existing villages.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link Status#PROPOSED} — created by the dev command and stored
 *       on {@code WorldRoadSavedData}; the player has paid the upfront
 *       currency fee.</li>
 *   <li>{@link Status#IN_PROGRESS} — the cross-tick loop is consuming
 *       progress against {@code totalBlocks}.</li>
 *   <li>{@link Status#COMPLETE} — {@code progressBlocks &gt;= totalBlocks};
 *       a real {@link RoadEdge} has been added to the world graph and
 *       realised. XP and reputation have been awarded.</li>
 *   <li>{@link Status#CANCELLED} — the player or admin abandoned the
 *       proposal. Currency is not refunded; partial progress (if any)
 *       is discarded.</li>
 * </ol>
 *
 * <h3>Persisted shape</h3>
 * Records are immutable; advancement and status changes are done by
 * constructing a copy via {@link #withProgress} / {@link #withStatus}.
 * The owning {@code RoadProposalManager} stores them on
 * {@code WorldRoadSavedData}'s snapshot.
 *
 * @param id                 unique proposal id (used as map key)
 * @param proposingPlayerId  UUID of the submitting player
 * @param fromVillageId      origin village
 * @param toVillageId        destination village
 * @param cellPath           atlas cell-keys for the routed path (deterministic
 *                           given identical world state)
 * @param tier               edge tier the connector will land at
 * @param currencyFeeBronze  upfront fee paid by the player (in bronze units)
 * @param totalBlocks        total block-count budget (= cellPath.size() × 16)
 * @param progressBlocks     consumed budget; advanced by the cross-tick loop
 * @param status             current lifecycle position
 * @param createdAtTick      server tick when the proposal was submitted
 */
public record RoadProposal(
        UUID id,
        UUID proposingPlayerId,
        UUID fromVillageId,
        UUID toVillageId,
        List<Long> cellPath,
        RoadEdge.EdgeTier tier,
        long currencyFeeBronze,
        int totalBlocks,
        int progressBlocks,
        Status status,
        long createdAtTick
) {

    public enum Status {
        PROPOSED,
        IN_PROGRESS,
        COMPLETE,
        CANCELLED;

        public static final Codec<Status> CODEC =
                Codec.STRING.xmap(Status::valueOf, Status::name);
    }

    private static final Codec<UUID> UUID_CODEC =
            Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<RoadProposal> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUID_CODEC.fieldOf("id").forGetter(RoadProposal::id),
            UUID_CODEC.fieldOf("proposingPlayerId").forGetter(RoadProposal::proposingPlayerId),
            UUID_CODEC.fieldOf("fromVillageId").forGetter(RoadProposal::fromVillageId),
            UUID_CODEC.fieldOf("toVillageId").forGetter(RoadProposal::toVillageId),
            Codec.LONG.listOf().optionalFieldOf("cellPath", new ArrayList<>())
                    .forGetter(RoadProposal::cellPath),
            RoadEdge.EdgeTier.CODEC.optionalFieldOf("tier", RoadEdge.EdgeTier.CONNECTOR)
                    .forGetter(RoadProposal::tier),
            Codec.LONG.optionalFieldOf("currencyFeeBronze", 0L)
                    .forGetter(RoadProposal::currencyFeeBronze),
            Codec.INT.optionalFieldOf("totalBlocks", 0)
                    .forGetter(RoadProposal::totalBlocks),
            Codec.INT.optionalFieldOf("progressBlocks", 0)
                    .forGetter(RoadProposal::progressBlocks),
            Status.CODEC.optionalFieldOf("status", Status.PROPOSED)
                    .forGetter(RoadProposal::status),
            Codec.LONG.optionalFieldOf("createdAtTick", 0L)
                    .forGetter(RoadProposal::createdAtTick)
    ).apply(i, RoadProposal::new));

    public RoadProposal withProgress(int newProgressBlocks) {
        int clamped = Math.max(0, Math.min(newProgressBlocks, totalBlocks));
        return new RoadProposal(id, proposingPlayerId, fromVillageId, toVillageId,
                cellPath, tier, currencyFeeBronze, totalBlocks, clamped,
                status, createdAtTick);
    }

    public RoadProposal withStatus(Status newStatus) {
        return new RoadProposal(id, proposingPlayerId, fromVillageId, toVillageId,
                cellPath, tier, currencyFeeBronze, totalBlocks, progressBlocks,
                newStatus, createdAtTick);
    }

    public float progressFraction() {
        return totalBlocks == 0 ? 0f : (float) progressBlocks / totalBlocks;
    }
}
