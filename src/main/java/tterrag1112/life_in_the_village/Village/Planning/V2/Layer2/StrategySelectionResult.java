package tterrag1112.life_in_the_village.Village.Planning.V2.Layer2;

import java.util.List;

/**
 * Track E1B — output of {@link StrategySelector#select}.
 *
 * <p>Carries the winning strategy, the anchors that satisfied
 * its preferences (when scored; null/empty for the fallback
 * path), the per-candidate selection log, and the final score.
 *
 * <p>Selection always produces a result — when no scored
 * candidate fits, the matching {@code *_cluster_fallback}
 * strategy is selected with {@code score = 0} and the log
 * records why each scoring candidate was rejected.
 *
 * @param strategy
 *      The winning {@link LayoutStrategy}. Never null.
 * @param primaryAnchor
 *      Anchor that satisfied {@code anchorPrefs.primaryTypes}.
 *      Null when the fallback path was taken.
 * @param secondaryAnchors
 *      Anchors of {@code anchorPrefs.secondaryTypes} within the
 *      proximity radius of the primary. Empty when no secondaries
 *      matched or on fallback.
 * @param selectionLog
 *      Human-readable per-candidate outcome lines in registry
 *      order. Each line ends with one of:
 *      <code>rejected, &lt;reason&gt;</code>,
 *      <code>eligible, score &lt;n&gt;</code>, or
 *      <code>SELECTED, score &lt;n&gt;</code>.
 * @param score
 *      Final score of the winner. {@code 0} for fallback.
 */
public record StrategySelectionResult(
        LayoutStrategy strategy,
        Anchor primaryAnchor,
        List<Anchor> secondaryAnchors,
        List<String> selectionLog,
        double score) {

    public StrategySelectionResult {
        if (strategy == null) {
            throw new IllegalArgumentException("StrategySelectionResult.strategy required");
        }
        secondaryAnchors = secondaryAnchors == null
                ? List.of()
                : List.copyOf(secondaryAnchors);
        selectionLog = selectionLog == null
                ? List.of()
                : List.copyOf(selectionLog);
    }
}
