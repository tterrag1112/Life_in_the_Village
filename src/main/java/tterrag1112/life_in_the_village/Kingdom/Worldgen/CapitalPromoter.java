package tterrag1112.life_in_the_village.Kingdom.Worldgen;

import tterrag1112.life_in_the_village.Kingdom.Kingdom;
import tterrag1112.life_in_the_village.Kingdom.KingdomClaim;
import tterrag1112.life_in_the_village.Kingdom.Settlement.CharterDigest;
import tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharter;
import tterrag1112.life_in_the_village.Kingdom.Settlement.SettlementCharterStage;
import tterrag1112.life_in_the_village.Village.Decoration.VillageSizeTier;
import tterrag1112.life_in_the_village.World.Atlas.AtlasCell;
import tterrag1112.life_in_the_village.World.Atlas.WorldAtlas;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Track V3 -- capital promotion.
 *
 * <p>A kingdom's capital is <b>not sited separately</b>. After
 * {@link ClaimVillageEnumerator} has persisted the owned-village charters
 * (V2), this promotes the best-suited one to capital -- a flag on the
 * existing {@link SettlementCharter}, not a new charter. This permanently
 * removes the capital-placement-failure class (doc 16 sec.2 item 4 / sec.7
 * item 2): there is nothing left to fail because the village already placed
 * successfully via the deterministic V1 spread.
 *
 * <h3>"Best-suited" (kept simple, sec.4/V3)</h3>
 * Among the kingdom's owned charters, pick the one whose target cell is
 * nearest the claim origin, breaking ties toward a better digest score
 * (higher centre, lower relief, freshwater/coast bonus). Simple and
 * deterministic.
 *
 * <h3>Never fails (sec.2/sec.8)</h3>
 * If the claim produced no owned village near the origin (a sparse claim
 * where no region's V1 candidate landed in-claim), this issues ONE fallback
 * charter at the claim's origin cell and promotes it -- the kingdom always
 * has a capital, or stays a shell, but never errors. The origin cell is the
 * seeder's {@code AtlasSiteSelector.findBest} pick (buildable, non-ocean), so
 * the fallback is sound.
 *
 * <h3>Cost-tier compliance</h3>
 * Reads only already-persisted charter records + (for the fallback digest)
 * one already-present atlas cell. No column scan, no atlas-fill forcing, no
 * V2 call.
 */
public final class CapitalPromoter {

    private CapitalPromoter() {}

    /** The capital's stage-0 size-band estimate (realization refines). */
    private static final VillageSizeTier CAPITAL_BAND = VillageSizeTier.TOWN;

    /**
     * Promotes the best-suited owned charter of {@code kingdom} to capital,
     * issuing an origin-cell fallback charter first if the kingdom owns none.
     * Sets {@code charter.capital()} -- the existing flag is the capital
     * handle (no new codec field); {@code kingdom.getCapitalCharter()} reads it.
     *
     * @return the promoted (capital) charter.
     */
    public static SettlementCharter promote(
            Kingdom kingdom,
            net.minecraft.server.level.ServerLevel level,
            String capitalName,
            String capitalVillageType,
            long tick,
            Consumer<String> progress) {

        KingdomClaim claim = kingdom.getTerritorialClaim().orElseThrow(
                () -> new IllegalStateException(
                        "CapitalPromoter: kingdom has no claim"));
        long originCellKey = claim.originCellKey();

        // Pick the best-suited owned charter (nearest origin, best digest).
        SettlementCharter best = pickBest(kingdom, originCellKey);

        if (best == null) {
            // Sparse-claim fallback: issue one capital charter at the origin
            // cell so the kingdom always has a capital. Never fail.
            WorldAtlas atlas = WorldAtlas.get(level);
            AtlasCell originCell = atlas.getCellByCoord(
                    AtlasCell.unpackX(originCellKey),
                    AtlasCell.unpackZ(originCellKey));
            CharterDigest digest = CharterDigest.fromCell(originCell, CAPITAL_BAND);
            SettlementCharter capital = kingdom.issueSettlementCharter(
                    capitalName, originCellKey, SettlementCharter.ROLE_CAPITAL,
                    capitalVillageType, CAPITAL_BAND, true, digest, tick);
            progress.accept("CapitalPromoter: no owned village near origin -- "
                    + "issued fallback capital charter '" + capitalName
                    + "' at origin cell " + AtlasCell.unpackX(originCellKey) + ","
                    + AtlasCell.unpackZ(originCellKey) + " (kingdom not a shell).");
            return capital;
        }

        // Promote: re-issue the best charter with capital=true + the capital
        // name + the capital village type. Copy-with via withCapital (keeps
        // id/cell/stage so the map pin and any realization in flight survive).
        SettlementCharter promoted = best.withCapital(
                capitalName, capitalVillageType);
        kingdom.updateSettlementCharter(promoted);

        progress.accept("CapitalPromoter: promoted owned village '" + best.name()
                + "' (cell " + AtlasCell.unpackX(best.targetCellKey()) + ","
                + AtlasCell.unpackZ(best.targetCellKey())
                + ") to CAPITAL '" + capitalName + "'.");
        return promoted;
    }

    /**
     * Best-suited owned charter: nearest target cell to the claim origin,
     * ties broken by a higher digest score. Returns null if the kingdom owns
     * no charters yet.
     */
    static SettlementCharter pickBest(Kingdom kingdom, long originCellKey) {
        int ox = AtlasCell.unpackX(originCellKey);
        int oz = AtlasCell.unpackZ(originCellKey);

        SettlementCharter best = null;
        long bestDistSq = Long.MAX_VALUE;
        double bestScore = -Double.MAX_VALUE;

        for (SettlementCharter c : kingdom.getSettlementCharters()) {
            // Only owned (unrealized stage-0/1) charters are promotion targets;
            // a realized village is fine too, but capital is normally chosen at
            // birth before realization.
            int cx = AtlasCell.unpackX(c.targetCellKey());
            int cz = AtlasCell.unpackZ(c.targetCellKey());
            long dx = cx - ox, dz = cz - oz;
            long distSq = dx * dx + dz * dz;
            double score = digestScore(c.digest());

            if (distSq < bestDistSq
                    || (distSq == bestDistSq && score > bestScore)) {
                bestDistSq = distSq;
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** A simple "good capital site" score from the stage-0 digest. */
    private static double digestScore(CharterDigest d) {
        if (d == null) return 0;
        double s = -d.relief() * 0.5;       // flatter preferred
        s += d.estPopulation() * 0.1;       // a bigger band is a better capital
        if (d.freshwater()) s += 8;         // water access is good for a capital
        if (d.coastal())    s += 4;         // coastal trade bonus
        return s;
    }
}
