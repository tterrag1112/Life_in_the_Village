package tterrag1112.life_in_the_village.Npc.Office.Selection;

import java.util.Comparator;

/**
 * Deterministic tiebreak rule shared by every {@link OfficeSelectionEngine}.
 * Spec {@code 06-office-framework.md} "Edge cases":
 *
 * <pre>
 * Multiple eligible candidates tied on skill. Tiebreak: higher Ambition,
 * then older age, then UUID order. Deterministic.
 * </pre>
 *
 * <p>Engines compose this comparator AFTER their primary score so the
 * selection result is stable across saves and identical inputs always
 * yield the same winner.</p>
 */
public final class SelectionTiebreaker {

    private SelectionTiebreaker() {}

    /** Score-then-tiebreak comparator. Higher score wins. */
    public static Comparator<OfficeCandidate> byScoreThenTiebreak() {
        return Comparator.comparingDouble(OfficeCandidate::score).reversed()
                .thenComparing(byTiebreakOnly());
    }

    /** Pure tiebreak comparator (used when scores are equal upstream). */
    public static Comparator<OfficeCandidate> byTiebreakOnly() {
        return Comparator.comparingDouble(OfficeCandidate::ambition).reversed()
                .thenComparingInt(c -> -c.age())
                .thenComparing(c -> c.uuid().toString());
    }
}
