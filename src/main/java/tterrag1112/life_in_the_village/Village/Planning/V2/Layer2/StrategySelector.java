package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import tterrag1112.life_in_the_village.Village.Planning.V2.Inclination;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Track E1B — picks the best {@link LayoutStrategy} for a site
 * given its inclination, tier, and detected anchor list.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Pull all strategies registered for the site's
 *       inclination from {@link LayoutStrategyRegistry}.</li>
 *   <li>Process candidates in registry order. For each:
 *     <ul>
 *       <li>Reject if tier outside conditions band.</li>
 *       <li>Reject if any {@code incompatibleAnchors} type is
 *           present at quality ≥ 0.5.</li>
 *       <li>Reject if no anchor of {@code primaryTypes} exists
 *           at quality ≥ {@code minPrimaryQuality}.</li>
 *       <li>Reject if {@code requireLinearFeature} and primary
 *           isn't a linear anchor.</li>
 *       <li>Otherwise score: see formula below.</li>
 *     </ul>
 *   </li>
 *   <li>If at least one scored candidate, pick highest score
 *       (ties broken by registry order).</li>
 *   <li>If zero scored candidates, fall back to the matching
 *       {@code <inclination>_cluster_fallback} strategy with
 *       score {@code 0}.</li>
 * </ol>
 *
 * <h3>Scoring formula</h3>
 * {@code score = primary.quality * 100}<br>
 * {@code      + 20 * min(3, matched_secondary_type_count)}<br>
 * {@code      + 25 if (requireLinearFeature && primary.isLinear)}<br>
 * {@code      + 80 if (primary.type().isResourceAnchor())}  — Track E1 prompt 5<br>
 * Penalty path is handled by the incompatible-anchor reject above.
 * Resource-anchor bonus (CLIFF_FACE / FOREST_EDGE / WATER_EDGE /
 * RIVER_BEND, explicitly not FLAT_FERTILE) keeps resource-anchored
 * strategies competitive against the universally-eligible haufendorf
 * fallbacks even when the fallback's FLAT_FERTILE primary is
 * high-quality.
 *
 * <h3>Determinism</h3>
 * Pure function of inputs. Registry iteration order is stable.
 * Same anchors + tier + inclination → same selection.
 */
public final class StrategySelector {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Quality threshold for considering an anchor "dominant" (used by
     *  {@code incompatibleAnchors} reject). */
    public static final double DOMINANT_ANCHOR_QUALITY = 0.5;

    /** Block-distance window for the secondary-proximity bonus. Matches
     *  the V2FeatureMap scan radius so we never penalise anchors that
     *  the detector found. */
    public static final int SECONDARY_PROXIMITY_BLOCKS = 96;

    /** Scoring weights — kept as constants for tunability + doc visibility. */
    public static final double PRIMARY_WEIGHT          = 100.0;
    public static final double SECONDARY_PER_TYPE      = 20.0;
    public static final int    SECONDARY_TYPE_CAP      = 3;
    public static final double LINEAR_FEATURE_BONUS    = 25.0;
    /** Track E1 prompt 5 — flat bonus added when a strategy's primary
     *  anchor type is a {@linkplain AnchorType#isResourceAnchor()
     *  resource anchor} (cliff / forest edge / waterline) and a site
     *  anchor of that type is present at quality above the strategy's
     *  primary-quality floor. Sized to flip a moderate-quality cliff
     *  / forest at q=0.3 (score 30) past a high-quality flat at q=0.8
     *  (score 80) so resource-anchored strategies win whenever their
     *  preferred feature exists at all; the haufendorf fallbacks
     *  (FLAT_FERTILE primary) only win when no resource anchor of
     *  any quality is present. FLAT_FERTILE is universal and so
     *  excluded from the bonus deliberately — otherwise every site
     *  would get the bonus and the term would collapse to noise. */
    public static final double RESOURCE_PRESENCE_BONUS = 80.0;

    private StrategySelector() {}

    public static StrategySelectionResult select(SiteContext ctx, List<Anchor> anchors) {
        if (ctx == null) {
            throw new IllegalArgumentException("SiteContext required");
        }
        Inclination inc = ctx.inclination();
        ViabilityTier tier = ctx.tier();
        List<Anchor> safeAnchors = anchors == null ? List.of() : anchors;

        List<LayoutStrategy> candidates = LayoutStrategyRegistry.byInclination(inc);
        List<String> log = new ArrayList<>();

        LayoutStrategy winner = null;
        double winnerScore = Double.NEGATIVE_INFINITY;
        Anchor winnerPrimary = null;
        List<Anchor> winnerSecondaries = List.of();
        int winnerLogIndex = -1;

        for (LayoutStrategy candidate : candidates) {
            // Tier band.
            if (!candidate.conditions().tierInBand(tier)) {
                log.add("Candidate " + candidate.id()
                        + ": rejected, tier " + tier
                        + " outside band ["
                        + candidate.conditions().tierMin()
                        + ", " + candidate.conditions().tierMax() + "]");
                continue;
            }
            // Incompatible anchor present.
            Anchor blocker = firstDominantAnchorOfTypes(safeAnchors,
                    candidate.conditions().incompatibleAnchors());
            if (blocker != null) {
                log.add("Candidate " + candidate.id()
                        + ": rejected, incompatible anchor " + blocker.type()
                        + " (" + blocker.id() + " q="
                        + fmt(blocker.quality()) + ") dominates site");
                continue;
            }
            // Primary anchor.
            Anchor primary = bestPrimaryAnchor(safeAnchors,
                    candidate.anchorPrefs().primaryTypes());
            if (primary == null) {
                if (candidate.anchorPrefs().primaryTypes().isEmpty()) {
                    // Fallback strategy with no anchor reqs — eligible but
                    // scored at 0 so any scored candidate beats it.
                    log.add("Candidate " + candidate.id()
                            + ": eligible (no primary required), score 0.0");
                    if (winner == null) {
                        winner = candidate;
                        winnerScore = 0.0;
                        winnerPrimary = null;
                        winnerSecondaries = List.of();
                        winnerLogIndex = log.size() - 1;
                    }
                    continue;
                }
                log.add("Candidate " + candidate.id()
                        + ": rejected, no primary anchor of types "
                        + candidate.anchorPrefs().primaryTypes());
                continue;
            }
            if (primary.quality() < candidate.anchorPrefs().minPrimaryQuality()) {
                log.add("Candidate " + candidate.id()
                        + ": rejected, primary " + primary.id()
                        + " quality " + fmt(primary.quality())
                        + " < required " + fmt(candidate.anchorPrefs().minPrimaryQuality()));
                continue;
            }
            if (candidate.anchorPrefs().requireLinearFeature()
                    && !primary.type().isLinear()) {
                log.add("Candidate " + candidate.id()
                        + ": rejected, requires linear feature; primary "
                        + primary.type() + " is non-linear");
                continue;
            }

            // Score.
            double score = primary.quality() * PRIMARY_WEIGHT;
            Set<AnchorType> matchedSecondaryTypes = new HashSet<>();
            List<Anchor> secondaries = new ArrayList<>();
            for (Anchor other : safeAnchors) {
                if (other == primary) continue;
                if (!candidate.anchorPrefs().secondaryTypes().contains(other.type())) continue;
                if (!withinProximity(primary, other,
                        SECONDARY_PROXIMITY_BLOCKS)) continue;
                if (matchedSecondaryTypes.add(other.type())) {
                    secondaries.add(other);
                }
            }
            int secondaryCount = Math.min(SECONDARY_TYPE_CAP,
                    matchedSecondaryTypes.size());
            score += SECONDARY_PER_TYPE * secondaryCount;
            if (candidate.anchorPrefs().requireLinearFeature()
                    && primary.type().isLinear()) {
                score += LINEAR_FEATURE_BONUS;
            }
            // Track E1 prompt 5 — resource-anchor presence bonus.
            // The strategy declared a resource-anchor primary
            // (cliff / forest / water) and a matching anchor exists
            // at the site above the strategy's quality floor: this
            // is what makes resource-anchored strategies reliably
            // beat the haufendorf fallback whose FLAT_FERTILE
            // primary score otherwise dominates on most sites.
            boolean appliesResourceBonus = primary.type().isResourceAnchor();
            if (appliesResourceBonus) {
                score += RESOURCE_PRESENCE_BONUS;
            }

            log.add("Candidate " + candidate.id() + ": eligible, score "
                    + fmt(score) + " (primary " + primary.type() + " "
                    + primary.id() + " q=" + fmt(primary.quality())
                    + "; +" + (int) (SECONDARY_PER_TYPE * secondaryCount)
                    + " from " + secondaryCount + " secondary types"
                    + (candidate.anchorPrefs().requireLinearFeature()
                            && primary.type().isLinear()
                            ? "; +" + (int) LINEAR_FEATURE_BONUS + " linear" : "")
                    + (appliesResourceBonus
                            ? "; +" + (int) RESOURCE_PRESENCE_BONUS + " resource-anchor" : "")
                    + ")");

            if (score > winnerScore) {
                winner = candidate;
                winnerScore = score;
                winnerPrimary = primary;
                winnerSecondaries = secondaries;
                winnerLogIndex = log.size() - 1;
            }
        }

        if (winner == null) {
            // Should not happen — registry always carries a fallback per
            // inclination — but defend defensively.
            throw new IllegalStateException("No strategy for inclination " + inc);
        }

        // Replace the winner's log line with a SELECTED variant.
        String entry = log.get(winnerLogIndex);
        int colonIdx = entry.indexOf(':');
        String head = colonIdx > 0 ? entry.substring(0, colonIdx) : entry;
        log.set(winnerLogIndex, head + ": SELECTED, score " + fmt(winnerScore));

        LOGGER.info("V2 strategy: {} for {}/{} — primary {}; {} candidates considered",
                winner.id(),
                inc, tier,
                winnerPrimary != null ? winnerPrimary.type() + "@" + winnerPrimary.id() : "none",
                candidates.size());

        return new StrategySelectionResult(winner, winnerPrimary,
                winnerSecondaries, log, winnerScore);
    }

    private static Anchor bestPrimaryAnchor(List<Anchor> anchors, Set<AnchorType> types) {
        Anchor best = null;
        for (Anchor a : anchors) {
            if (!types.contains(a.type())) continue;
            if (best == null || a.quality() > best.quality()) best = a;
        }
        return best;
    }

    private static Anchor firstDominantAnchorOfTypes(List<Anchor> anchors,
                                                      Set<AnchorType> types) {
        if (types == null || types.isEmpty()) return null;
        for (Anchor a : anchors) {
            if (!types.contains(a.type())) continue;
            if (a.quality() >= DOMINANT_ANCHOR_QUALITY) return a;
        }
        return null;
    }

    private static boolean withinProximity(Anchor a, Anchor b, int blocks) {
        long dx = a.centre().getX() - b.centre().getX();
        long dz = a.centre().getZ() - b.centre().getZ();
        return dx * dx + dz * dz <= (long) blocks * blocks;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
